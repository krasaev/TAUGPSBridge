package ru.krasaev.taugpsbridge.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.FtdiSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.ProlificSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import ru.krasaev.taugpsbridge.model.UsbDeviceInfo
import java.io.ByteArrayOutputStream
import java.io.IOException

class UsbSerialManager(
    private val context: Context,
    private val onGpsDataUpdated: ((GpsData) -> Unit)? = null
) : SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "UsbSerialManager"
        const val ACTION_USB_PERMISSION = "ru.krasaev.taugpsbridge.USB_PERMISSION"

        // F1 D9 0A 04 00 00 0E 34
        val CMD_REQUEST_VERSION = byteArrayOf(
            0xF1.toByte(), 0xD9.toByte(), 0x0A.toByte(), 0x04.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x0E.toByte(), 0x34.toByte()
        )

        // F1 D9 06 01 03 00 F0 20 01 1B 50 (Enable antenna status)
        val CMD_ENABLE_ANTENNA = byteArrayOf(
            0xF1.toByte(), 0xD9.toByte(), 0x06.toByte(), 0x01.toByte(),
            0x03.toByte(), 0x00.toByte(), 0xF0.toByte(), 0x20.toByte(),
            0x01.toByte(), 0x1B.toByte(), 0x50.toByte()
        )
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nmeaParser = NmeaParser()

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _gpsData = MutableStateFlow(GpsData())
    val gpsData: StateFlow<GpsData> = _gpsData.asStateFlow()

    private val _rawLogs = MutableSharedFlow<String>(replay = 50, extraBufferCapacity = 200)
    val rawLogs: SharedFlow<String> = _rawLogs.asSharedFlow()

    private val _availableDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<UsbDeviceInfo>> = _availableDevices.asStateFlow()

    private val rawByteStream = ByteArrayOutputStream()
    private val lineBuffer = StringBuilder()

    private var selectedDeviceName: String? = null
    private var selectedBaudRate: Int = 115200

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    ACTION_USB_PERMISSION -> {
                        synchronized(this) {
                            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                            }
                            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            if (granted && device != null) {
                                Log.i(TAG, "USB Permission granted for device: ${device.deviceName}")
                                connectDevice(device, selectedBaudRate)
                            } else {
                                Log.w(TAG, "USB Permission denied for device: ${device?.deviceName}")
                                _connectionStatus.value = ConnectionStatus.Error("Разрешение USB отклонено")
                            }
                            scanDevices()
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Log.i(TAG, "USB Device attached")
                        scanDevices()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Log.i(TAG, "USB Device detached")
                        scanDevices()
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        if (device != null && serialPort?.driver?.device?.deviceId == device.deviceId) {
                            disconnect()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in usbReceiver", e)
            }
        }
    }

    init {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            scanDevices()
        } catch (e: Exception) {
            Log.e(TAG, "Error during UsbSerialManager init", e)
        }
    }

    private fun getCustomProber(): UsbSerialProber {
        val customTable = ProbeTable().apply {
            addProduct(0x1a86, 0x7523, Ch34xSerialDriver::class.java) // CH340
            addProduct(0x1a86, 0x5523, Ch34xSerialDriver::class.java) // CH341
            addProduct(0x10c4, 0xea60, Cp21xxSerialDriver::class.java) // CP2102
            addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)   // FT232R
            addProduct(0x067b, 0x2303, ProlificSerialDriver::class.java) // PL2303
        }
        return UsbSerialProber(customTable)
    }

    fun scanDevices(): List<UsbDeviceInfo> {
        return try {
            val deviceList = usbManager.deviceList
            val customProber = getCustomProber()
            val defaultProber = UsbSerialProber.getDefaultProber()

            val list = mutableListOf<UsbDeviceInfo>()
            for (device in deviceList.values) {
                val driver = defaultProber.probeDevice(device) ?: customProber.probeDevice(device)
                val portCount = driver?.ports?.size ?: 1
                val hasPerm = usbManager.hasPermission(device)
                list.add(
                    UsbDeviceInfo(
                        deviceName = device.deviceName,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        manufacturerName = device.manufacturerName,
                        productName = device.productName,
                        serialNumber = if (hasPerm) try { device.serialNumber } catch (e: Exception) { null } else null,
                        portCount = portCount,
                        hasPermission = hasPerm
                    )
                )
            }
            _availableDevices.value = list
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning USB devices", e)
            emptyList()
        }
    }

    fun connect(deviceName: String? = null, baudRate: Int = 115200) {
        selectedBaudRate = baudRate
        try {
            val deviceList = usbManager.deviceList

            val targetDevice = if (deviceName != null) {
                deviceList[deviceName] ?: deviceList.values.firstOrNull { it.deviceName == deviceName }
            } else {
                deviceList.values.firstOrNull()
            }

            if (targetDevice == null) {
                _connectionStatus.value = ConnectionStatus.Error("USB устройство не найдено")
                return
            }

            selectedDeviceName = targetDevice.deviceName

            if (!usbManager.hasPermission(targetDevice)) {
                _connectionStatus.value = ConnectionStatus.Connecting
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                    flags
                )
                usbManager.requestPermission(targetDevice, permissionIntent)
                return
            }

            connectDevice(targetDevice, baudRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error in connect()", e)
            _connectionStatus.value = ConnectionStatus.Error("Ошибка подключения: ${e.message ?: "Unknown"}")
        }
    }

    private fun connectDevice(device: UsbDevice, baudRate: Int) {
        _connectionStatus.value = ConnectionStatus.Connecting
        scope.launch {
            try {
                disconnectInternal()

                val driver: UsbSerialDriver =
                    UsbSerialProber.getDefaultProber().probeDevice(device)
                        ?: getCustomProber().probeDevice(device)
                        ?: CdcAcmSerialDriver(device)

                val connection = usbManager.openDevice(device)
                    ?: throw IOException("Не удалось открыть USB соединение (null connection)")

                val port = driver.ports.firstOrNull()
                    ?: throw IOException("Не найден последовательный порт на USB устройстве")

                port.open(connection)
                port.setParameters(
                    baudRate,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )

                try {
                    port.dtr = true
                    port.rts = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set DTR/RTS", e)
                }

                serialPort = port
                nmeaParser.reset()
                nmeaParser.onConnected()
                synchronized(rawByteStream) { rawByteStream.reset() }
                synchronized(lineBuffer) { lineBuffer.clear() }

                val manager = SerialInputOutputManager(port, this@UsbSerialManager)
                manager.readTimeout = 200
                manager.writeTimeout = 200
                ioManager = manager
                manager.start()

                val displayName = device.productName ?: device.deviceName
                _connectionStatus.value = ConnectionStatus.Connected(displayName, baudRate)
                emitLog("Подключено: $displayName (${baudRate} бод)")

                // Automatically send query for module version and antenna status
                delay(150)
                sendRawBytes(CMD_REQUEST_VERSION, "Запрос версии модуля")
                delay(100)
                sendRawBytes(CMD_ENABLE_ANTENNA, "Включение статуса антенны")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _connectionStatus.value = ConnectionStatus.Error(e.message ?: "Ошибка подключения")
                emitLog("Ошибка подключения: ${e.message}")
                disconnectInternal()
            }
        }
    }

    fun disconnect() {
        scope.launch {
            disconnectInternal()
            _connectionStatus.value = ConnectionStatus.Disconnected
            emitLog("Отключено")
        }
    }

    private fun disconnectInternal() {
        try {
            ioManager?.listener = null
            ioManager?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping ioManager", e)
        } finally {
            ioManager = null
        }

        try {
            serialPort?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing serialPort", e)
        } finally {
            serialPort = null
        }
    }

    fun sendCommand(command: String, lineEnding: String = "\r\n"): Boolean {
        val port = serialPort ?: return false
        return try {
            val trimmed = command.trim()
            val hexBytes = parseHexCommand(trimmed)
            val payload = if (hexBytes != null) {
                hexBytes
            } else {
                (command + lineEnding).toByteArray(Charsets.US_ASCII)
            }
            port.write(payload, 1000)
            emitLog("TX: $command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command: $command", e)
            emitLog("TX Error: ${e.message}")
            false
        }
    }

    private fun sendRawBytes(bytes: ByteArray, desc: String): Boolean {
        val port = serialPort ?: return false
        return try {
            port.write(bytes, 1000)
            val hexString = bytes.joinToString(" ") { "%02X".format(it) }
            emitLog("TX [$desc]: $hexString")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send raw bytes: $desc", e)
            emitLog("TX Error ($desc): ${e.message}")
            false
        }
    }

    private fun parseHexCommand(cmd: String): ByteArray? {
        val cleaned = cmd.replace(" ", "").replace(",", "").uppercase()
        if (cleaned.length >= 4 && cleaned.length % 2 == 0 && cleaned.all { it in "0123456789ABCDEF" } && (cleaned.startsWith("F1D9") || cleaned.startsWith("B562"))) {
            return ByteArray(cleaned.length / 2) { i ->
                cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
        return null
    }

    override fun onNewData(data: ByteArray?) {
        if (data == null || data.isEmpty()) return

        // 1. Check for binary packets (F1 D9 0A 04 20 00 ...)
        synchronized(rawByteStream) {
            rawByteStream.write(data)
            checkAndProcessBinaryPackets()
        }

        // 2. Process ASCII NMEA stream
        val text = String(data, Charsets.US_ASCII)
        synchronized(lineBuffer) {
            lineBuffer.append(text)
            var newlineIndex: Int
            while (true) {
                val crIndex = lineBuffer.indexOf("\r")
                val lfIndex = lineBuffer.indexOf("\n")
                if (crIndex == -1 && lfIndex == -1) break

                newlineIndex = when {
                    crIndex != -1 && lfIndex != -1 -> minOf(crIndex, lfIndex)
                    crIndex != -1 -> crIndex
                    else -> lfIndex
                }

                val line = lineBuffer.substring(0, newlineIndex).trim()
                lineBuffer.delete(0, newlineIndex + 1)

                if (line.isNotEmpty()) {
                    processIncomingLine(line)
                }
            }
        }
    }

    private fun checkAndProcessBinaryPackets() {
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

    private fun processIncomingLine(line: String) {
        try {
            if (line.startsWith("$") || line.startsWith("!")) {
                val updated = nmeaParser.parseSentence(line)
                _gpsData.value = updated
                onGpsDataUpdated?.invoke(updated)
            }
            emitLog("RX: $line")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming line: $line", e)
        }
    }

    override fun onRunError(e: Exception?) {
        Log.e(TAG, "Serial IO run error", e)
        scope.launch {
            _connectionStatus.value = ConnectionStatus.Error(e?.message ?: "Ошибка ввода-вывода")
            emitLog("IO Error: ${e?.message}")
            disconnectInternal()
        }
    }

    private fun emitLog(log: String) {
        scope.launch {
            _rawLogs.emit(log)
        }
    }

    fun release() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        disconnectInternal()
    }
}
