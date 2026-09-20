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
import kotlinx.coroutines.withContext
import ru.krasaev.taugpsbridge.gps.NmeaParser
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.GpsData
import java.io.ByteArrayOutputStream

/**
 * Общий базовый класс для USB и Bluetooth SPP менеджеров.
 *
 * Обеспечивает:
 *  - разбор NMEA-строк;
 *  - разбор бинарных пакетов Allystar (F1 D9);
 *  - публикацию потоков connectionStatus, gpsData, rawLogs;
 *  - вызов onGpsDataUpdated из Main-потока.
 */
abstract class BaseSerialManager(
    protected val context: Context,
    protected val onGpsDataUpdated: ((GpsData) -> Unit)? = null
) {
    companion object {
        // F1 D9 0A 04 00 00 0E 34 — запрос версии ПО/железа
        val CMD_REQUEST_VERSION = byteArrayOf(
            0xF1.toByte(), 0xD9.toByte(), 0x0A.toByte(), 0x04.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x0E.toByte(), 0x34.toByte()
        )

        // F1 D9 06 01 03 00 F0 20 01 1B 50 — включить статус антенны в GNTXT
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

    // ─── Абстрактные методы ───
    abstract fun disconnect()
    abstract fun sendCommand(command: String, lineEnding: String = "\r\n"): Boolean

    /**
     * Освобождение ресурсов. Наследники вызывают `super.release()`.
     */
    open fun release() {
        scope.cancel()
    }

    // ═══════════════════════════════════════════════════════════
    //  Обработка входящих байт
    // ═══════════════════════════════════════════════════════════

    /**
     * Вызывается из IO-потока (SerialInputOutputManager / BT read-loop).
     */
    protected open fun handleIncomingBytes(data: ByteArray?) {
        if (data == null || data.isEmpty()) return

        // 1. Бинарные пакеты Allystar (F1 D9)
        synchronized(rawByteStream) {
            rawByteStream.write(data)
            checkAndProcessBinaryPackets()
        }

        // 2. ASCII-поток (NMEA). Фильтруем non-printable байты, чтобы
        //    бинарные пакеты не попадали в ASCII-обработку повторно.
        val filtered = filterPrintable(data)
        if (filtered.isEmpty()) return

        val text = String(filtered, Charsets.US_ASCII)
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

                val skipLen = if (newlineIndex + 1 < lineBuffer.length &&
                    ((lineBuffer[newlineIndex] == '\r' && lineBuffer[newlineIndex + 1] == '\n') ||
                            (lineBuffer[newlineIndex] == '\n' && lineBuffer[newlineIndex + 1] == '\r'))
                ) 2 else 1
                lineBuffer.delete(0, newlineIndex + skipLen)

                if (rawLine.isNotEmpty()) {
                    if (rawLine.startsWith("$") || rawLine.startsWith("!")) {
                        processIncomingLine(rawLine)
                    } else {
                        emitLog("RX: $rawLine")
                    }
                }
            }

            if (lineBuffer.length > 4096) lineBuffer.clear()
        }
    }

    /**
     * Оставляет только печатаемые ASCII + CR/LF. Non-printable байты
     * (включая бинарные пакеты F1 D9) отбрасываются — они уже обработаны
     * бинарным парсером.
     */
    private fun filterPrintable(data: ByteArray): ByteArray {
        var count = 0
        for (b in data) {
            val u = b.toInt() and 0xFF
            if (u in 0x20..0x7E || u == 0x0D || u == 0x0A) count++
        }
        if (count == data.size) return data

        val out = ByteArray(count)
        var i = 0
        for (b in data) {
            val u = b.toInt() and 0xFF
            if (u in 0x20..0x7E || u == 0x0D || u == 0x0A) out[i++] = b
        }
        return out
    }

    // ═══════════════════════════════════════════════════════════
    //  Разбор бинарных пакетов Allystar
    // ═══════════════════════════════════════════════════════════

    protected open fun checkAndProcessBinaryPackets() {
        val bytes = rawByteStream.toByteArray()
        if (bytes.size < 6) return

        var i = 0
        while (i <= bytes.size - 6) {
            // Стартовая последовательность только Allystar
            if (bytes[i] != 0xF1.toByte() || bytes[i + 1] != 0xD9.toByte()) {
                i++
                continue
            }

            // 1) Ответ на запрос версии: F1 D9 0A 04 20 00 ...
            if (bytes[i + 2] == 0x0A.toByte() && bytes[i + 3] == 0x04.toByte() &&
                bytes[i + 4] == 0x20.toByte() && bytes[i + 5] == 0x00.toByte()
            ) {
                val packetLen = 40 // 6 header + 32 payload + 2 checksum
                if (bytes.size >= i + packetLen) {
                    val packet = bytes.copyOfRange(i, i + packetLen)
                    val modInfo = nmeaParser.parseBinaryVersionResponse(packet)
                    if (modInfo != null) {
                        val updated = nmeaParser.getCurrentData()
                        publishGpsData(updated)
                        emitLog(
                            "RX [VERSION]: SW='${modInfo.swVersion}', " +
                                    "HW='${modInfo.hwVersion}' (${modInfo.typeDescription})"
                        )
                    }
                    consumeBinaryBytes(bytes, i + packetLen)
                    return
                }
                i++
                continue
            }

            // 2) Прочие бинарные пакеты Allystar: F1 D9 <group> <sub> <len_L> <len_H> ...
            val payloadLen = (bytes[i + 4].toInt() and 0xFF) or
                    ((bytes[i + 5].toInt() and 0xFF) shl 8)
            val totalLen = 6 + payloadLen + 2
            if (totalLen in 8..512 && bytes.size >= i + totalLen) {
                val packet = bytes.copyOfRange(i, i + totalLen)
                val hex = packet.joinToString(" ") { "%02X".format(it) }
                emitLog("RX [HEX]: $hex")
                consumeBinaryBytes(bytes, i + totalLen)
                return
            }

            i++
        }

        // Буфер разросся — оставляем хвост на случай прихода следующего пакета
        if (rawByteStream.size() > 4096) {
            val tail = bytes.takeLast(1024).toByteArray()
            rawByteStream.reset()
            rawByteStream.write(tail)
        }
    }

    /** Удаляет обработанные байты из rawByteStream, оставляя хвост. */
    private fun consumeBinaryBytes(bytes: ByteArray, consumedUpTo: Int) {
        rawByteStream.reset()
        if (bytes.size > consumedUpTo) {
            rawByteStream.write(bytes, consumedUpTo, bytes.size - consumedUpTo)
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Разбор NMEA-строк
    // ═══════════════════════════════════════════════════════════

    protected open fun processIncomingLine(line: String) {
        try {
            val updated = nmeaParser.parseSentence(line)
            publishGpsData(updated)
            emitLog("RX: $line")
        } catch (e: Exception) {
            emitLog("RX Error: ${e.message}")
        }
    }

    /** Обновляет StateFlow и вызывает callback в Main-потоке. */
    protected fun publishGpsData(data: GpsData) {
        _gpsData.value = data
        val callback = onGpsDataUpdated
        if (callback != null) {
            scope.launch {
                withContext(Dispatchers.Main) {
                    callback.invoke(data)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  HEX-команды
    // ═══════════════════════════════════════════════════════════

    protected fun parseHexCommand(cmd: String): ByteArray? {
        val cleaned = cmd.replace(" ", "").replace(",", "").uppercase()
        if (cleaned.length >= 4 && cleaned.length % 2 == 0 &&
            cleaned.all { it in "0123456789ABCDEF" } &&
            cleaned.startsWith("F1D9")
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