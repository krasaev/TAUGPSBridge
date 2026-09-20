package ru.krasaev.taugpsbridge.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.krasaev.taugpsbridge.model.BluetoothDeviceInfo
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.GpsData
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class BluetoothSerialManager(
    context: Context,
    onGpsDataUpdated: ((GpsData) -> Unit)? = null,
    /** Вызывается, когда BT-устройство сопрягли или оно стало доступно. */
    private val onBluetoothDeviceAvailable: ((BluetoothDeviceInfo) -> Unit)? = null,
    /** Вызывается, когда Bluetooth-адаптер выключен. */
    private val onBluetoothDisabled: (() -> Unit)? = null
) : BaseSerialManager(context, onGpsDataUpdated) {

    companion object {
        private const val TAG = "BluetoothSerialManager"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _availableDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothDeviceInfo>> = _availableDevices.asStateFlow()

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var outputStream: OutputStream? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    intent.getBtDeviceExtra()?.let { addDeviceToList(it) }
                }

                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = intent.getBtDeviceExtra()
                    val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    scanDevices()

                    if (device != null && newState == BluetoothDevice.BOND_BONDED) {
                        onBluetoothDeviceAvailable?.invoke(device.toInfo())
                    }
                }

                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    intent.getBtDeviceExtra()?.let {
                        onBluetoothDeviceAvailable?.invoke(it.toInfo())
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getBtDeviceExtra()
                    if (device != null && _connectionStatus.value is ConnectionStatus.Connected) {
                        val connected = _connectionStatus.value as ConnectionStatus.Connected
                        val matches = connected.deviceName.contains(device.address) ||
                                (!device.name.isNullOrBlank() &&
                                        connected.deviceName.contains(device.name))
                        if (matches) {
                            emitLog("Bluetooth соединение разорвано удалённым устройством")
                            disconnect()
                        }
                    }
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_OFF) {
                        onBluetoothDisabled?.invoke()
                        disconnect()
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try { context.registerReceiver(btReceiver, filter) }
        catch (e: Exception) { Log.w(TAG, "Failed to register BT receiver", e) }
    }

    // ─── Утилиты ───

    private fun Intent.getBtDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toInfo(): BluetoothDeviceInfo {
        val name = try { this.name ?: "" } catch (_: SecurityException) { "" }
        return BluetoothDeviceInfo(
            name = name,
            address = address ?: "",
            isBonded = bondState == BluetoothDevice.BOND_BONDED
        )
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceToList(device: BluetoothDevice) {
        val info = device.toInfo()
        if (info.address.isBlank()) return

        val list = _availableDevices.value.toMutableList()
        val idx = list.indexOfFirst { it.address == info.address }
        if (idx >= 0) list[idx] = info else list.add(info)
        _availableDevices.value = list
    }

    @SuppressLint("MissingPermission")
    fun scanDevices(): List<BluetoothDeviceInfo> {
        val list = mutableListOf<BluetoothDeviceInfo>()
        val adapter = bluetoothAdapter ?: run {
            _availableDevices.value = emptyList()
            return emptyList()
        }

        try {
            adapter.bondedDevices?.forEach { dev ->
                if (!dev.address.isNullOrBlank()) list.add(dev.toInfo())
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "BT permissions not granted for bonded devices", e)
        }

        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "BT permissions not granted for startDiscovery", e)
        }

        _availableDevices.value = list
        return list
    }

    @SuppressLint("MissingPermission")
    fun isDeviceBonded(address: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        return try {
            adapter.bondedDevices?.any { it.address.equals(address, true) } == true
        } catch (_: SecurityException) { false }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        scope.launch {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                _connectionStatus.value = ConnectionStatus.Error("Bluetooth адаптер не найден")
                emitLog("Ошибка: Bluetooth адаптер недоступен")
                return@launch
            }
            if (!adapter.isEnabled) {
                _connectionStatus.value = ConnectionStatus.Error("Bluetooth выключен")
                emitLog("Ошибка: Bluetooth выключен")
                return@launch
            }

            _connectionStatus.value = ConnectionStatus.Connecting
            emitLog("Подключение к Bluetooth: $address...")
            disconnectInternal()

            try {
                try { adapter.cancelDiscovery() } catch (_: SecurityException) { }

                val device = adapter.getRemoteDevice(address)
                val devName = try { device.name ?: address } catch (_: Exception) { address }

                val btSocket = try {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Secure socket failed, trying insecure", e)
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                }

                socket = btSocket
                btSocket.connect()
                inputStream = btSocket.inputStream
                outputStream = btSocket.outputStream

                nmeaParser.reset()
                nmeaParser.onConnected()
                synchronized(rawByteStream) { rawByteStream.reset() }
                synchronized(lineBuffer) { lineBuffer.clear() }

                _connectionStatus.value =
                    ConnectionStatus.Connected(deviceName = devName, baudRate = 0)
                emitLog("Подключено по Bluetooth: $devName ($address)")

                delay(150)
                sendRawBytes(CMD_REQUEST_VERSION, "Запрос версии модуля")
                delay(100)
                sendRawBytes(CMD_ENABLE_ANTENNA, "Включение статуса антенны")

                startReadLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth connection failed", e)
                val msg = e.message ?: "Не удалось подключиться"
                _connectionStatus.value = ConnectionStatus.Error(msg)
                emitLog("Ошибка подключения BT: $msg")
                disconnectInternal()
            }
        }
    }

    private suspend fun startReadLoop() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(2048)
        val inStream = inputStream ?: return@withContext
        try {
            while (isActive && socket?.isConnected == true) {
                val bytesRead = inStream.read(buffer)
                if (bytesRead <= 0) { delay(10); continue }
                handleIncomingBytes(buffer.copyOf(bytesRead))
            }
        } catch (e: Exception) {
            if (isActive) {
                Log.e(TAG, "BT Read loop error", e)
                _connectionStatus.value =
                    ConnectionStatus.Error(e.message ?: "Ошибка чтения BT")
                emitLog("Ошибка чтения BT: ${e.message}")
                disconnectInternal()
            }
        }
    }

    override fun disconnect() {
        scope.launch {
            disconnectInternal()
            _connectionStatus.value = ConnectionStatus.Disconnected
            emitLog("Bluetooth отключено")
        }
    }

    private fun disconnectInternal() {
        val s = socket; val i = inputStream; val o = outputStream
        socket = null; inputStream = null; outputStream = null
        try { i?.close() } catch (_: Exception) { }
        try { o?.close() } catch (_: Exception) { }
        try { s?.close() } catch (_: Exception) { }
    }

    override fun sendCommand(command: String, lineEnding: String): Boolean {
        val out = outputStream ?: return false
        return try {
            val hexBytes = parseHexCommand(command.trim())
            val payload = hexBytes ?: (command + lineEnding).toByteArray(Charsets.US_ASCII)
            out.write(payload)
            out.flush()
            emitLog("TX: $command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send BT command", e)
            emitLog("TX Error: ${e.message}")
            false
        }
    }

    fun sendRawBytes(bytes: ByteArray, desc: String): Boolean {
        val out = outputStream ?: return false
        return try {
            out.write(bytes)
            out.flush()
            emitLog("TX [$desc]: ${bytes.joinToString(" ") { "%02X".format(it) }}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send raw bytes over BT", e)
            emitLog("TX Error ($desc): ${e.message}")
            false
        }
    }

    override fun release() {
        try { context.unregisterReceiver(btReceiver) } catch (_: Exception) { }
        disconnectInternal()
        super.release()
    }
}