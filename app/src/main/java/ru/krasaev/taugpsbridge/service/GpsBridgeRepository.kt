package ru.krasaev.taugpsbridge.service

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.krasaev.taugpsbridge.connection.BluetoothSerialManager
import ru.krasaev.taugpsbridge.connection.UsbSerialManager
import ru.krasaev.taugpsbridge.gps.MockLocationManager
import ru.krasaev.taugpsbridge.model.BluetoothDeviceInfo
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.ConnectionType
import ru.krasaev.taugpsbridge.model.GpsData
import ru.krasaev.taugpsbridge.model.UsbDeviceInfo

class GpsBridgeRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GpsBridgeRepository"
        private const val PREFS_NAME = "tau_gps_bridge_prefs"
        private const val KEY_CONNECTION_TYPE = "key_connection_type"
        private const val KEY_LAST_DEVICE_NAME = "key_last_device_name"
        private const val KEY_LAST_DEVICE_KEY = "key_last_device_key"
        private const val KEY_LAST_DEVICE_VID_PID = "key_last_device_vid_pid"
        private const val KEY_LAST_BT_ADDRESS = "key_last_bt_address"
        private const val KEY_LAST_BAUD_RATE = "key_last_baud_rate"
        private const val KEY_LAST_CMD_ENDING = "key_last_cmd_ending"

        @Volatile
        private var instance: GpsBridgeRepository? = null

        fun getInstance(context: Context): GpsBridgeRepository {
            return instance ?: synchronized(this) {
                instance ?: GpsBridgeRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val mockLocationManager = MockLocationManager(context)

    // ─── StateFlow ───

    private val _isMockLocationActive = MutableStateFlow(false)
    val isMockLocationActive: StateFlow<Boolean> = _isMockLocationActive.asStateFlow()

    private val _mockLocationError = MutableStateFlow<String?>(null)
    val mockLocationError: StateFlow<String?> = _mockLocationError.asStateFlow()

    private val _generalError = MutableStateFlow<String?>(null)
    val generalError: StateFlow<String?> = _generalError.asStateFlow()

    private val savedConnTypeString = prefs.getString(KEY_CONNECTION_TYPE, ConnectionType.USB.name)
    val selectedConnectionType = MutableStateFlow(
        try { ConnectionType.valueOf(savedConnTypeString ?: ConnectionType.USB.name) }
        catch (_: Exception) { ConnectionType.USB }
    )

    val selectedDeviceName = MutableStateFlow<String?>(prefs.getString(KEY_LAST_DEVICE_NAME, null))
    val selectedBluetoothAddress =
        MutableStateFlow<String?>(prefs.getString(KEY_LAST_BT_ADDRESS, null))
    val selectedBaudRate = MutableStateFlow(prefs.getInt(KEY_LAST_BAUD_RATE, 115200))
    val selectedCommandEnding =
        MutableStateFlow(prefs.getString(KEY_LAST_CMD_ENDING, "\r\n") ?: "\r\n")

    private val _isLoggingEnabled = MutableStateFlow(false)
    val isLoggingEnabled: StateFlow<Boolean> = _isLoggingEnabled.asStateFlow()

    private val _logHistory = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 500)
    val logHistory: SharedFlow<String> = _logHistory.asSharedFlow()

    private val _connectionStatus =
        MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _gpsData = MutableStateFlow(GpsData())
    val gpsData: StateFlow<GpsData> = _gpsData.asStateFlow()

    // ─── Managers ───

    val usbSerialManager = UsbSerialManager(
        context = context,
        onGpsDataUpdated = ::onGpsDataReceived,
        onDeviceAttached = ::onUsbDeviceAttached,
        onDeviceDetached = ::onUsbDeviceDetached
    )

    val bluetoothSerialManager = BluetoothSerialManager(
        context = context,
        onGpsDataUpdated = ::onGpsDataReceived,
        onBluetoothDeviceAvailable = ::onBluetoothDeviceAvailable,
        onBluetoothDisabled = ::onBluetoothDisabled
    )

    val availableDevices: StateFlow<List<UsbDeviceInfo>> = usbSerialManager.availableDevices
    val availableBluetoothDevices: StateFlow<List<BluetoothDeviceInfo>> =
        bluetoothSerialManager.availableDevices

    init {
        observeLogs()
        observeConnectionStatus()
        observeGpsData()

        scope.launch {
            if (selectedConnectionType.value == ConnectionType.USB) {
                scanDevicesAndAutoConnect()
            }
        }
    }

    // ─── Наблюдатели ───

    private fun observeLogs() {
        scope.launch {
            usbSerialManager.rawLogs.collect { log ->
                if (_isLoggingEnabled.value &&
                    selectedConnectionType.value == ConnectionType.USB
                ) _logHistory.emit(log)
            }
        }
        scope.launch {
            bluetoothSerialManager.rawLogs.collect { log ->
                if (_isLoggingEnabled.value &&
                    selectedConnectionType.value == ConnectionType.BLUETOOTH
                ) _logHistory.emit(log)
            }
        }
    }

    private fun observeConnectionStatus() {
        scope.launch {
            usbSerialManager.connectionStatus.collect { status ->
                if (selectedConnectionType.value != ConnectionType.USB) return@collect
                _connectionStatus.value = status
                when (status) {
                    is ConnectionStatus.Error -> _generalError.value = status.message
                    is ConnectionStatus.Connected -> _generalError.value = null
                    else -> Unit
                }
            }
        }
        scope.launch {
            bluetoothSerialManager.connectionStatus.collect { status ->
                if (selectedConnectionType.value != ConnectionType.BLUETOOTH) return@collect
                _connectionStatus.value = status
                when (status) {
                    is ConnectionStatus.Error -> _generalError.value = status.message
                    is ConnectionStatus.Connected -> _generalError.value = null
                    else -> Unit
                }
            }
        }
    }

    private fun observeGpsData() {
        scope.launch {
            usbSerialManager.gpsData.collectLatest { data ->
                if (selectedConnectionType.value == ConnectionType.USB) _gpsData.value = data
            }
        }
        scope.launch {
            bluetoothSerialManager.gpsData.collectLatest { data ->
                if (selectedConnectionType.value == ConnectionType.BLUETOOTH) _gpsData.value = data
            }
        }
    }

    // ─── GPS data → mock location ───

    private fun onGpsDataReceived(data: GpsData) {
        if (!_isMockLocationActive.value) return
        if (!data.hasFix || data.latitude == null || data.longitude == null) return

        val result = mockLocationManager.pushLocation(data)
        if (result.isFailure) {
            val err = result.exceptionOrNull()?.message ?: "Ошибка передачи мок-локации"
            _mockLocationError.value = err
            Log.w(TAG, "Mock push error: $err")
        } else {
            _mockLocationError.value = null
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Авто-подключение: общие проверки
    // ═══════════════════════════════════════════════════════════

    /**
     * Совпадает ли USB-устройство с сохранённым.
     * Матч по одному из трёх признаков:
     *   1) uniqueKey (серийник / vid:pid:порт)
     *   2) точное deviceName
     *   3) VID:PID (fallback для адаптеров без серийника)
     */
    private fun isSavedUsbDevice(device: UsbDeviceInfo): Boolean {
        val savedKey = prefs.getString(KEY_LAST_DEVICE_KEY, null)
        val savedName = prefs.getString(KEY_LAST_DEVICE_NAME, null)
        val savedVidPid = prefs.getString(KEY_LAST_DEVICE_VID_PID, null)

        if (savedKey == null && savedName == null && savedVidPid == null) return false

        return (savedKey != null && device.uniqueKey == savedKey) ||
                (savedName != null && device.deviceName == savedName) ||
                (savedVidPid != null && savedVidPid.equals(device.vidPidKey, ignoreCase = true))
    }

    /** Ищет сохранённое USB-устройство в списке. */
    private fun findSavedUsbDevice(devices: List<UsbDeviceInfo>): UsbDeviceInfo? =
        devices.firstOrNull { isSavedUsbDevice(it) }

    /** Совпадает ли BT-устройство с сохранённым MAC. */
    private fun isSavedBluetoothDevice(address: String): Boolean {
        val saved = prefs.getString(KEY_LAST_BT_ADDRESS, null)
            ?: selectedBluetoothAddress.value
            ?: return false
        return address.equals(saved, ignoreCase = true)
    }

    // ═══════════════════════════════════════════════════════════
    //  Авто-подключение USB
    // ═══════════════════════════════════════════════════════════

    private fun onUsbDeviceAttached(device: UsbDeviceInfo) {
        Log.i(TAG, "USB attached: ${device.deviceName} (${device.vidPidString})")

        if (selectedConnectionType.value != ConnectionType.USB) return
        if (_connectionStatus.value is ConnectionStatus.Connected) return
        if (!isSavedUsbDevice(device)) {
            Log.i(TAG, "Not a saved device, skip auto-connect")
            return
        }

        Log.i(TAG, "Saved device detected, auto-connecting…")
        selectedDeviceName.value = device.deviceName
        scope.launch {
            usbSerialManager.connect(device.deviceName, selectedBaudRate.value)
        }
    }

    private fun onUsbDeviceDetached(device: UsbDevice) {
        Log.i(TAG, "USB detached: ${device.deviceName}")
        if (_connectionStatus.value is ConnectionStatus.Connected) {
            val current = selectedDeviceName.value
            if (current.isNullOrBlank() || current == device.deviceName) {
                _connectionStatus.value = ConnectionStatus.Disconnected
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Авто-подключение Bluetooth
    // ═══════════════════════════════════════════════════════════

    private fun onBluetoothDeviceAvailable(device: BluetoothDeviceInfo) {
        Log.i(TAG, "BT available: ${device.address} (${device.name})")

        if (selectedConnectionType.value != ConnectionType.BLUETOOTH) return

        val status = _connectionStatus.value
        if (status is ConnectionStatus.Connected || status is ConnectionStatus.Connecting) return
        if (!isSavedBluetoothDevice(device.address)) return

        Log.i(TAG, "Saved BT device available, auto-connecting…")
        scope.launch {
            bluetoothSerialManager.connect(device.address)
        }
    }

    private fun onBluetoothDisabled() {
        Log.i(TAG, "Bluetooth disabled by user")
        if (selectedConnectionType.value == ConnectionType.BLUETOOTH) {
            _connectionStatus.value = ConnectionStatus.Disconnected
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Scanning / selection
    // ═══════════════════════════════════════════════════════════

    fun selectConnectionType(type: ConnectionType) {
        if (selectedConnectionType.value == type) return
        disconnect()
        selectedConnectionType.value = type
        prefs.edit().putString(KEY_CONNECTION_TYPE, type.name).apply()
        _connectionStatus.value = ConnectionStatus.Disconnected
        if (type == ConnectionType.USB) scanDevices() else scanBluetoothDevices()
    }

    fun scanDevices(): List<UsbDeviceInfo> {
        val list = usbSerialManager.scanDevices()
        if (selectedDeviceName.value == null && list.isNotEmpty()) {
            selectedDeviceName.value = list.first().deviceName
        }
        return list
    }

    fun scanBluetoothDevices(): List<BluetoothDeviceInfo> {
        val list = bluetoothSerialManager.scanDevices()
        if (selectedBluetoothAddress.value == null && list.isNotEmpty()) {
            selectedBluetoothAddress.value = list.first().address
        }
        return list
    }

    fun scanDevicesAndAutoConnect(): List<UsbDeviceInfo> {
        val usbList = scanDevices()
        scanBluetoothDevices()

        if (_connectionStatus.value !is ConnectionStatus.Disconnected) return usbList

        if (selectedConnectionType.value == ConnectionType.USB) {
            val target = findSavedUsbDevice(usbList) ?: return usbList
            selectedDeviceName.value = target.deviceName
            connectUsb(target.deviceName)
        } else {
            val savedAddr = prefs.getString(KEY_LAST_BT_ADDRESS, null)
                ?: selectedBluetoothAddress.value
                ?: return usbList
            selectedBluetoothAddress.value = savedAddr
            connectBluetooth(savedAddr)
        }

        return usbList
    }

    fun selectAndConnectDevice(device: UsbDeviceInfo) {
        bluetoothSerialManager.disconnect()
        selectedConnectionType.value = ConnectionType.USB
        selectedDeviceName.value = device.deviceName
        saveLastUsbDevice(device)
        connectUsb(device.deviceName)
    }

    fun selectAndConnectBluetoothDevice(device: BluetoothDeviceInfo) {
        usbSerialManager.disconnect()
        selectedConnectionType.value = ConnectionType.BLUETOOTH
        selectedBluetoothAddress.value = device.address
        prefs.edit()
            .putString(KEY_CONNECTION_TYPE, ConnectionType.BLUETOOTH.name)
            .putString(KEY_LAST_BT_ADDRESS, device.address)
            .apply()
        connectBluetooth(device.address)
    }

    private fun saveLastUsbDevice(device: UsbDeviceInfo) {
        prefs.edit()
            .putString(KEY_CONNECTION_TYPE, ConnectionType.USB.name)
            .putString(KEY_LAST_DEVICE_NAME, device.deviceName)
            .putString(KEY_LAST_DEVICE_KEY, device.uniqueKey)
            .putString(KEY_LAST_DEVICE_VID_PID, device.vidPidKey)
            .apply()
    }

    // ═══════════════════════════════════════════════════════════
    //  Connect / disconnect
    // ═══════════════════════════════════════════════════════════

    fun connect() {
        if (selectedConnectionType.value == ConnectionType.USB) connectUsb()
        else connectBluetooth()
    }

    fun connectUsb(deviceName: String? = selectedDeviceName.value) {
        try {
            bluetoothSerialManager.disconnect()
            val dev = deviceName ?: selectedDeviceName.value
            if (dev.isNullOrBlank()) {
                _generalError.value = "USB-устройство не выбрано"
                return
            }
            usbSerialManager.connect(dev, selectedBaudRate.value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect USB", e)
            _generalError.value = "Ошибка подключения USB: ${e.message ?: "Unknown"}"
        }
    }

    fun connectBluetooth(address: String? = selectedBluetoothAddress.value) {
        try {
            usbSerialManager.disconnect()
            val addr = address ?: selectedBluetoothAddress.value
            if (addr.isNullOrBlank()) {
                _generalError.value = "Bluetooth-устройство не выбрано"
                return
            }
            bluetoothSerialManager.connect(addr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect Bluetooth", e)
            _generalError.value = "Ошибка подключения Bluetooth: ${e.message ?: "Unknown"}"
        }
    }

    fun disconnect() {
        try {
            usbSerialManager.disconnect()
            bluetoothSerialManager.disconnect()
            _connectionStatus.value = ConnectionStatus.Disconnected
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
        }
    }

    // ─── Commands / settings ───

    fun sendCommand(command: String): Boolean {
        return try {
            if (selectedConnectionType.value == ConnectionType.USB) {
                usbSerialManager.sendCommand(command, selectedCommandEnding.value)
            } else {
                bluetoothSerialManager.sendCommand(command, selectedCommandEnding.value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command", e)
            _generalError.value = "Ошибка отправки команды: ${e.message}"
            false
        }
    }

    fun updateBaudRate(baudRate: Int) {
        selectedBaudRate.value = baudRate
        prefs.edit().putInt(KEY_LAST_BAUD_RATE, baudRate).apply()
    }

    fun updateCommandEnding(ending: String) {
        selectedCommandEnding.value = ending
        prefs.edit().putString(KEY_LAST_CMD_ENDING, ending).apply()
    }

    // ─── Logging ───

    fun setLoggingEnabled(enabled: Boolean) {
        if (_isLoggingEnabled.value == enabled) return
        _isLoggingEnabled.value = enabled
        if (enabled) addLog("[СИСТЕМА]: Логирование включено")
    }

    fun addLog(message: String) {
        if (!_isLoggingEnabled.value) return
        scope.launch { _logHistory.emit(message) }
    }

    fun clearGeneralError() { _generalError.value = null }
    fun clearMockLocationError() { _mockLocationError.value = null }

    // ─── Mock location ───

    fun setMockLocationActive(active: Boolean): Boolean {
        return try {
            if (active) startMockLocation() else stopMockLocation()
        } catch (e: Exception) {
            Log.e(TAG, "Mock location toggle error", e)
            val msg = e.message ?: "Ошибка фиктивного местоположения"
            _mockLocationError.value = msg
            _generalError.value = msg
            false
        }
    }

    private fun startMockLocation(): Boolean {
        val res = mockLocationManager.startMock()
        if (res.isFailure) {
            val err = res.exceptionOrNull()?.message
                ?: "Не удалось запустить фиктивное местоположение"
            _isMockLocationActive.value = false
            _mockLocationError.value = err
            _generalError.value = err
            addLog("[СИСТЕМА] Ошибка запуска mock: $err")
            return false
        }

        _isMockLocationActive.value = true
        _mockLocationError.value = null
        _generalError.value = null

        return try {
            GpsBridgeService.startService(context)
            addLog("[СИСТЕМА] Mock location включён")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            mockLocationManager.stopMock()
            _isMockLocationActive.value = false
            val msg = "Не удалось запустить сервис: ${e.message ?: "Unknown"}"
            _mockLocationError.value = msg
            _generalError.value = msg
            false
        }
    }

    private fun stopMockLocation(): Boolean {
        mockLocationManager.stopMock()
        _isMockLocationActive.value = false
        _mockLocationError.value = null
        return try {
            GpsBridgeService.stopService(context)
            addLog("[СИСТЕМА] Mock location выключен")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop foreground service", e)
            false
        }
    }
}