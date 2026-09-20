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
    onGpsDataUpdated: ((GpsData) -> Unit)? = null
) : BaseSerialManager(context, onGpsDataUpdated) {

    companion object {
        private const val TAG = "BluetoothSerialManager"
        // Standard SPP UUID for Serial Communication (RFCOMM)
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _availableDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<BluetoothDeviceInfo>> = _availableDevices.asStateFlow()

    @Volatile
    private var socket: BluetoothSocket? = null
    @Volatile
    private var inputStream: InputStream? = null
    @Volatile
    private var outputStream: OutputStream? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        addDeviceToList(device)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    scanDevices()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null && _connectionStatus.value is ConnectionStatus.Connected) {
                        val currentName = (_connectionStatus.value as ConnectionStatus.Connected).deviceName
                        if (currentName.contains(device.address) || currentName.contains(device.name ?: "")) {
                            emitLog("Bluetooth соединение разорвано удалённым устройством")
                            disconnect()
                        }
                    }
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            context.registerReceiver(btReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register BT receiver", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun addDeviceToList(device: BluetoothDevice) {
        val name = try { device.name ?: "" } catch (e: SecurityException) { "" }
        val address = device.address ?: return
        val isBonded = device.bondState == BluetoothDevice.BOND_BONDED
        val info = BluetoothDeviceInfo(name = name, address = address, isBonded = isBonded)

        val currentList = _availableDevices.value.toMutableList()
        val existingIndex = currentList.indexOfFirst { it.address == address }
        if (existingIndex >= 0) {
            currentList[existingIndex] = info
        } else {
            currentList.add(info)
        }
        _availableDevices.value = currentList
    }

    @SuppressLint("MissingPermission")
    fun scanDevices(): List<BluetoothDeviceInfo> {
        val list = mutableListOf<BluetoothDeviceInfo>()
        if (bluetoothAdapter == null) {
            _availableDevices.value = emptyList()
            return emptyList()
        }

        try {
            val paired = bluetoothAdapter.bondedDevices
            paired?.forEach { dev ->
                val name = try { dev.name ?: "" } catch (e: Exception) { "" }
                val address = dev.address ?: ""
                if (address.isNotBlank()) {
                    list.add(BluetoothDeviceInfo(name = name, address = address, isBonded = true))
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permissions not granted for bonded devices", e)
        }

        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth permissions not granted for startDiscovery", e)
        }

        _availableDevices.value = list
        return list
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        scope.launch {
            if (bluetoothAdapter == null) {
                _connectionStatus.value = ConnectionStatus.Error("Bluetooth адаптер не найден")
                emitLog("Ошибка: Bluetooth адаптер недоступен")
                return@launch
            }

            if (!bluetoothAdapter.isEnabled) {
                _connectionStatus.value = ConnectionStatus.Error("Bluetooth выключен")
                emitLog("Ошибка: Bluetooth выключен на устройстве")
                return@launch
            }

            _connectionStatus.value = ConnectionStatus.Connecting
            emitLog("Подключение к Bluetooth: $address...")

            disconnectInternal()

            try {
                try {
                    bluetoothAdapter.cancelDiscovery()
                } catch (e: SecurityException) {
                    // Ignore
                }

                val device = bluetoothAdapter.getRemoteDevice(address)
                val devName = try { device.name ?: address } catch (e: Exception) { address }

                val btSocket = try {
                    device.createRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create secure socket, trying insecure", e)
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

                _connectionStatus.value = ConnectionStatus.Connected(deviceName = devName, baudRate = 0)
                emitLog("Подключено по Bluetooth: $devName ($address)")

                // Auto request version and antenna
                delay(150)
                sendRawBytes(CMD_REQUEST_VERSION, "Запрос версии модуля")
                delay(100)
                sendRawBytes(CMD_ENABLE_ANTENNA, "Включение статуса антенны")

                // Start read loop
                startReadLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth connection failed", e)
                val msg = e.message ?: "Не удалось подключиться к Bluetooth устройству"
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
                if (bytesRead <= 0) {
                    delay(10)
                    continue
                }
                val receivedData = buffer.copyOf(bytesRead)
                handleIncomingBytes(receivedData)
            }
        } catch (e: Exception) {
            if (isActive) {
                Log.e(TAG, "BT Read loop error", e)
                _connectionStatus.value = ConnectionStatus.Error(e.message ?: "Ошибка чтения данных Bluetooth")
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
        try {
            inputStream?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            inputStream = null
        }

        try {
            outputStream?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            outputStream = null
        }

        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            socket = null
        }
    }

    override fun sendCommand(command: String, lineEnding: String): Boolean {
        val out = outputStream ?: return false
        return try {
            val trimmed = command.trim()
            val hexBytes = parseHexCommand(trimmed)
            val payload = if (hexBytes != null) {
                hexBytes
            } else {
                (command + lineEnding).toByteArray(Charsets.US_ASCII)
            }
            out.write(payload)
            out.flush()
            emitLog("TX: $command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send BT command: $command", e)
            emitLog("TX Error: ${e.message}")
            false
        }
    }

    fun sendRawBytes(bytes: ByteArray, desc: String): Boolean {
        val out = outputStream ?: return false
        return try {
            out.write(bytes)
            out.flush()
            val hexString = bytes.joinToString(" ") { "%02X".format(it) }
            emitLog("TX [$desc]: $hexString")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send raw bytes over BT: $desc", e)
            emitLog("TX Error ($desc): ${e.message}")
            false
        }
    }

    override fun release() {
        try {
            context.unregisterReceiver(btReceiver)
        } catch (e: Exception) {
            // Ignore
        }
        disconnectInternal()
    }
}
