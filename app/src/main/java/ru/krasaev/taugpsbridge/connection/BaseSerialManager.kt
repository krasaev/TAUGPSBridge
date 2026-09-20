package ru.krasaev.taugpsbridge.connection

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.krasaev.taugpsbridge.gps.NmeaParser
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.GpsData
import java.io.ByteArrayOutputStream

/**
 * Common abstract base class for Serial communication managers (USB, Bluetooth SPP).
 */
abstract class BaseSerialManager(
    protected val context: Context,
    protected val onGpsDataUpdated: ((GpsData) -> Unit)? = null
) {
    companion object {
        // F1 D9 0A 04 00 00 0E 34 (Query version: SW and HW)
        val CMD_REQUEST_VERSION = byteArrayOf(
            0xF1.toByte(), 0xD9.toByte(), 0x0A.toByte(), 0x04.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x0E.toByte(), 0x34.toByte()
        )

        // F1 D9 06 01 03 00 F0 20 01 1B 50 (Enable antenna status in NMEA $GNTXT)
        val CMD_ENABLE_ANTENNA = byteArrayOf(
            0xF1.toByte(), 0xD9.toByte(), 0x06.toByte(), 0x01.toByte(),
            0x03.toByte(), 0x00.toByte(), 0xF0.toByte(), 0x20.toByte(),
            0x01.toByte(), 0x1B.toByte(), 0x50.toByte()
        )
    }

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    protected val nmeaParser = NmeaParser()

    protected val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    protected val _gpsData = MutableStateFlow(GpsData())
    val gpsData: StateFlow<GpsData> = _gpsData.asStateFlow()

    protected val _rawLogs = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 500)
    val rawLogs: SharedFlow<String> = _rawLogs.asSharedFlow()

    protected val rawByteStream = ByteArrayOutputStream()
    protected val lineBuffer = StringBuilder()

    abstract fun disconnect()
    abstract fun sendCommand(command: String, lineEnding: String = "\r\n"): Boolean
    abstract fun release()

    /**
     * Common handler for incoming byte stream from serial source.
     */
    protected open fun handleIncomingBytes(data: ByteArray?) {
        if (data == null || data.isEmpty()) return

        // 1. Process binary packets (e.g. TAU proprietary F1 D9 or UBX B5 62)
        synchronized(rawByteStream) {
            rawByteStream.write(data)
            checkAndProcessBinaryPackets()
        }

        // 2. Process ASCII NMEA stream or raw HEX
        val text = String(data, Charsets.US_ASCII)
        synchronized(lineBuffer) {
            lineBuffer.append(text)
            while (true) {
                val crIndex = lineBuffer.indexOf("\r")
                val lfIndex = lineBuffer.indexOf("\n")
                if (crIndex == -1 && lfIndex == -1) break

                val newlineIndex = when {
                    crIndex != -1 && lfIndex != -1 -> minOf(crIndex, lfIndex)
                    crIndex != -1 -> crIndex
                    else -> lfIndex
                }

                val rawLine = lineBuffer.substring(0, newlineIndex).trim()
                if (newlineIndex + 1 < lineBuffer.length &&
                    ((lineBuffer[newlineIndex] == '\r' && lineBuffer[newlineIndex + 1] == '\n') ||
                     (lineBuffer[newlineIndex] == '\n' && lineBuffer[newlineIndex + 1] == '\r'))
                ) {
                    lineBuffer.delete(0, newlineIndex + 2)
                } else {
                    lineBuffer.delete(0, newlineIndex + 1)
                }

                if (rawLine.isNotEmpty()) {
                    if (rawLine.startsWith("$") || rawLine.startsWith("!")) {
                        processIncomingLine(rawLine)
                    } else if (rawLine.all { it in ' '..'~' }) {
                        emitLog("RX: $rawLine")
                    } else {
                        val hex = rawLine.toByteArray(Charsets.US_ASCII).joinToString(" ") { "%02X".format(it) }
                        emitLog("RX [HEX]: $hex")
                    }
                }
            }

            if (lineBuffer.length > 2048) {
                lineBuffer.clear()
            }
        }
    }

    protected open fun checkAndProcessBinaryPackets() {
        val bytes = rawByteStream.toByteArray()
        if (bytes.size < 6) return

        var i = 0
        while (i <= bytes.size - 6) {
            // Check for header F1 D9 0A 04 20 00 (Version response: 40 bytes total)
            if (bytes[i] == 0xF1.toByte() &&
                bytes[i + 1] == 0xD9.toByte() &&
                bytes[i + 2] == 0x0A.toByte() &&
                bytes[i + 3] == 0x04.toByte() &&
                bytes[i + 4] == 0x20.toByte() &&
                bytes[i + 5] == 0x00.toByte()
            ) {
                val packetLen = 40 // 6 header + 32 payload + 2 checksum
                if (bytes.size >= i + packetLen) {
                    val packet = bytes.copyOfRange(i, i + packetLen)
                    val modInfo = nmeaParser.parseBinaryVersionResponse(packet)
                    if (modInfo != null) {
                        val updated = nmeaParser.getCurrentData()
                        _gpsData.value = updated
                        onGpsDataUpdated?.invoke(updated)
                        emitLog("RX [VERSION]: SW='${modInfo.swVersion}', HW='${modInfo.hwVersion}' (${modInfo.typeDescription})")
                    }
                    rawByteStream.reset()
                    if (bytes.size > i + packetLen) {
                        rawByteStream.write(bytes, i + packetLen, bytes.size - (i + packetLen))
                    }
                    return
                }
            } else if ((bytes[i] == 0xF1.toByte() && bytes[i + 1] == 0xD9.toByte()) ||
                       (bytes[i] == 0xB5.toByte() && bytes[i + 1] == 0x62.toByte())
            ) {
                if (bytes.size >= i + 6) {
                    val payloadLen = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8)
                    val totalLen = 6 + payloadLen + 2
                    if (totalLen in 8..512 && bytes.size >= i + totalLen) {
                        val packet = bytes.copyOfRange(i, i + totalLen)
                        val hex = packet.joinToString(" ") { "%02X".format(it) }
                        emitLog("RX [HEX]: $hex")
                        rawByteStream.reset()
                        if (bytes.size > i + totalLen) {
                            rawByteStream.write(bytes, i + totalLen, bytes.size - (i + totalLen))
                        }
                        return
                    }
                }
            }
            i++
        }

        // Keep buffer bounded
        if (rawByteStream.size() > 2048) {
            val tail = bytes.takeLast(512).toByteArray()
            rawByteStream.reset()
            rawByteStream.write(tail)
        }
    }

    protected open fun processIncomingLine(line: String) {
        try {
            if (line.startsWith("$") || line.startsWith("!")) {
                val updated = nmeaParser.parseSentence(line)
                _gpsData.value = updated
                onGpsDataUpdated?.invoke(updated)
            }
            emitLog("RX: $line")
        } catch (e: Exception) {
            emitLog("RX Error: ${e.message}")
        }
    }

    protected fun parseHexCommand(cmd: String): ByteArray? {
        val cleaned = cmd.replace(" ", "").replace(",", "").uppercase()
        if (cleaned.length >= 4 && cleaned.length % 2 == 0 && cleaned.all { it in "0123456789ABCDEF" } &&
            (cleaned.startsWith("F1D9") || cleaned.startsWith("B562"))
        ) {
            return ByteArray(cleaned.length / 2) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        return null
    }

    fun emitLog(log: String) {
        scope.launch {
            _rawLogs.emit(log)
        }
    }
}
