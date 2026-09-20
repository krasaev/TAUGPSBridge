package ru.krasaev.taugpsbridge.connection

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.GpsData
import ru.krasaev.taugpsbridge.model.UsbDeviceInfo
import java.io.IOException

class UsbSerialManager(
    context: Context,
    onGpsDataUpdated: ((GpsData) -> Unit)? = null,
    /** Вызывается при физическом подключении USB-устройства. */
    private val onDeviceAttached: ((UsbDeviceInfo) -> Unit)? = null,
    /** Вызывается при физическом отключении USB-устройства. */
    private val onDeviceDetached: ((UsbDevice) -> Unit)? = null
) : BaseSerialManager(context, onGpsDataUpdated), SerialInputOutputManager.Listener {

    companion object {
        private const val TAG = "UsbSerialManager"
        const val ACTION_USB_PERMISSION = "ru.krasaev.taugpsbridge.USB_PERMISSION"
        private const val ATTACH_SCAN_DELAY_MS = 300L
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var serialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null

    private val _availableDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<UsbDeviceInfo>> = _availableDevices.asStateFlow()

    private var selectedDeviceName: String? = null
    private var selectedBaudRate: Int = 115200

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    ACTION_USB_PERMISSION -> handlePermissionResult(intent)
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleDeviceAttached(intent)
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDeviceDetached(intent)
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
                context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            scope.launch { scanDevices() }
        } catch (e: Exception) {
            Log.e(TAG, "Error during UsbSerialManager init", e)
        }
    }

    // ─── Обработчики broadcast ───

    private fun handlePermissionResult(intent: Intent) {
        synchronized(this) {
            val device = intent.getUsbDeviceExtra()
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                Log.i(TAG, "USB permission granted: ${device.deviceName}")
                connectDevice(device, selectedBaudRate)
            } else {
                _connectionStatus.value = ConnectionStatus.Error("Разрешение USB отклонено")
            }
            scope.launch { scanDevices() }
        }
    }

    private fun handleDeviceAttached(intent: Intent) {
        val device = intent.getUsbDeviceExtra()
        Log.i(TAG, "USB attached: ${device?.deviceName}")

        scope.launch {
            delay(ATTACH_SCAN_DELAY_MS)  // дать системе время обновить deviceList
            scanDevices()
            if (device != null) {
                onDeviceAttached?.invoke(buildDeviceInfo(device))
            }
        }
    }

    private fun handleDeviceDetached(intent: Intent) {
        val device = intent.getUsbDeviceExtra()
        Log.i(TAG, "USB detached: ${device?.deviceName}")
        scanDevices()

        if (device != null && serialPort?.driver?.device?.deviceId == device.deviceId) {
            disconnect()
        }
        if (device != null) {
            onDeviceDetached?.invoke(device)
        }
    }

    private fun Intent.getUsbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    // ─── Prober / scan ───

    private fun getCustomProber(): UsbSerialProber {
        val customTable = ProbeTable().apply {
            addProduct(0x1a86, 0x7523, Ch34xSerialDriver::class.java)
            addProduct(0x1a86, 0x5523, Ch34xSerialDriver::class.java)
            addProduct(0x10c4, 0xea60, Cp21xxSerialDriver::class.java)
            addProduct(0x0403, 0x6001, FtdiSerialDriver::class.java)
            addProduct(0x067b, 0x2303, ProlificSerialDriver::class.java)
        }
        return UsbSerialProber(customTable)
    }

    fun scanDevices(): List<UsbDeviceInfo> {
        return try {
            val deviceList = usbManager.deviceList
            val list = deviceList.values.map { buildDeviceInfo(it) }
            _availableDevices.value = list
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning USB devices", e)
            emptyList()
        }
    }

    private fun buildDeviceInfo(
        device: UsbDevice,
        defaultProber: UsbSerialProber = UsbSerialProber.getDefaultProber(),
        customProber: UsbSerialProber = getCustomProber()
    ): UsbDeviceInfo {
        val driver = defaultProber.probeDevice(device) ?: customProber.probeDevice(device)
        val portCount = driver?.ports?.size ?: 1
        val hasPerm = usbManager.hasPermission(device)

        return UsbDeviceInfo(
            deviceName = device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            manufacturerName = device.manufacturerName,
            productName = device.productName,
            serialNumber = if (hasPerm) {
                try { device.serialNumber } catch (_: Exception) { null }
            } else null,
            portCount = portCount,
            hasPermission = hasPerm
        )
    }

    // ─── Connect ───

    fun connect(deviceName: String? = null, baudRate: Int = 115200) {
        selectedBaudRate = baudRate
        try {
            val deviceList = usbManager.deviceList
            val target = if (deviceName != null) {
                deviceList[deviceName]
                    ?: deviceList.values.firstOrNull { it.deviceName == deviceName }
            } else {
                deviceList.values.firstOrNull()
            }

            if (target == null) {
                _connectionStatus.value = ConnectionStatus.Error("USB устройство не найдено")
                return
            }
            selectedDeviceName = target.deviceName

            if (!usbManager.hasPermission(target)) {
                requestPermission(target)
                return
            }
            connectDevice(target, baudRate)
        } catch (e: Exception) {
            Log.e(TAG, "Error in connect()", e)
            _connectionStatus.value =
                ConnectionStatus.Error("Ошибка подключения: ${e.message ?: "Unknown"}")
        }
    }

    private fun requestPermission(device: UsbDevice) {
        _connectionStatus.value = ConnectionStatus.Connecting
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(device, intent)
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
                    ?: throw IOException("Не удалось открыть USB соединение")
                val port = driver.ports.firstOrNull()
                    ?: throw IOException("Не найден последовательный порт")

                port.open(connection)
                port.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                try { port.dtr = true; port.rts = true } catch (_: Exception) { }

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
                emitLog("Подключено: $displayName ($baudRate бод)")

                delay(150)
                sendRawBytes(CMD_REQUEST_VERSION, "Запрос версии модуля")
                delay(100)
                sendRawBytes(CMD_ENABLE_ANTENNA, "Включение статуса антенны")
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                _connectionStatus.value =
                    ConnectionStatus.Error(e.message ?: "Ошибка подключения")
                emitLog("Ошибка подключения: ${e.message}")
                disconnectInternal()
            }
        }
    }

    override fun disconnect() {
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
        } catch (_: Exception) { }
        finally { ioManager = null }

        try { serialPort?.close() } catch (_: Exception) { }
        finally { serialPort = null }
    }

    // ─── Commands ───

    override fun sendCommand(command: String, lineEnding: String): Boolean {
        val port = serialPort ?: return false
        return try {
            val hexBytes = parseHexCommand(command.trim())
            val payload = hexBytes ?: (command + lineEnding).toByteArray(Charsets.US_ASCII)
            port.write(payload, 1000)
            emitLog("TX: $command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command", e)
            emitLog("TX Error: ${e.message}")
            false
        }
    }

    fun sendRawBytes(bytes: ByteArray, desc: String): Boolean {
        val port = serialPort ?: return false
        return try {
            port.write(bytes, 1000)
            emitLog("TX [$desc]: ${bytes.joinToString(" ") { "%02X".format(it) }}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send raw bytes", e)
            emitLog("TX Error ($desc): ${e.message}")
            false
        }
    }

    // ─── SerialInputOutputManager.Listener ───

    override fun onNewData(data: ByteArray?) = handleIncomingBytes(data)

    override fun onRunError(e: Exception?) {
        Log.e(TAG, "Serial IO run error", e)
        scope.launch {
            _connectionStatus.value =
                ConnectionStatus.Error(e?.message ?: "Ошибка ввода-вывода")
            emitLog("IO Error: ${e?.message}")
            disconnectInternal()
        }
    }

    override fun release() {
        try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) { }
        disconnectInternal()
        super.release()
    }
}