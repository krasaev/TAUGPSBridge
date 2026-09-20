package ru.krasaev.taugpsbridge.service

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.coroutines.launch
import ru.krasaev.taugpsbridge.gps.MockLocationManager
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.GpsData
import ru.krasaev.taugpsbridge.model.UsbDeviceInfo
import ru.krasaev.taugpsbridge.usb.UsbSerialManager

class GpsBridgeRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "GpsBridgeRepository"
        private const val PREFS_NAME = "tau_gps_bridge_prefs"
        private const val KEY_LAST_DEVICE_NAME = "key_last_device_name"
        private const val KEY_LAST_DEVICE_KEY = "key_last_device_key"
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

    private val _isMockLocationActive = MutableStateFlow(false)
    val isMockLocationActive: StateFlow<Boolean> = _isMockLocationActive.asStateFlow()

    private val _mockLocationError = MutableStateFlow<String?>(null)
    val mockLocationError: StateFlow<String?> = _mockLocationError.asStateFlow()

    private val _generalError = MutableStateFlow<String?>(null)
    val generalError: StateFlow<String?> = _generalError.asStateFlow()

    val selectedDeviceName = MutableStateFlow<String?>(prefs.getString(KEY_LAST_DEVICE_NAME, null))
    val selectedBaudRate = MutableStateFlow(prefs.getInt(KEY_LAST_BAUD_RATE, 115200))
    val selectedCommandEnding = MutableStateFlow(prefs.getString(KEY_LAST_CMD_ENDING, "\r\n") ?: "\r\n")

    // Logs are paused/disabled by default as per requirement
    private val _isLoggingEnabled = MutableStateFlow(false)
    val isLoggingEnabled: StateFlow<Boolean> = _isLoggingEnabled.asStateFlow()

    private val _logHistory = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 500)
    val logHistory: SharedFlow<String> = _logHistory.asSharedFlow()

    val usbSerialManager = UsbSerialManager(
        context = context,
        onGpsDataUpdated = { data ->
            if (_isMockLocationActive.value && data.hasFix && data.latitude != null && data.longitude != null) {
                val pushResult = mockLocationManager.pushLocation(data)
                if (pushResult.isFailure) {
                    val err = pushResult.exceptionOrNull()?.message ?: "Ошибка передачи мок-локации"
                    _mockLocationError.value = err
                    Log.w(TAG, "Mock push error: $err")
                } else {
                    _mockLocationError.value = null
                }
            }
        }
    )

    val connectionStatus: StateFlow<ConnectionStatus> = usbSerialManager.connectionStatus
    val gpsData: StateFlow<GpsData> = usbSerialManager.gpsData
    val availableDevices: StateFlow<List<UsbDeviceInfo>> = usbSerialManager.availableDevices

    init {
        scope.launch {
            usbSerialManager.rawLogs.collect { log ->
                if (_isLoggingEnabled.value) {
                    _logHistory.emit(log)
                }
            }
        }

        scope.launch {
            usbSerialManager.connectionStatus.collect { status ->
                if (status is ConnectionStatus.Error) {
                    _generalError.value = status.message
                } else if (status is ConnectionStatus.Connected) {
                    _generalError.value = null
                }
            }
        }

        // Try auto-connecting to saved device if present
        scanDevicesAndAutoConnect()
    }

    fun setLoggingEnabled(enabled: Boolean) {
        _isLoggingEnabled.value = enabled
        if (enabled) {
            addLog("[СИСТЕМА]: Логирование включено")
        } else {
            addLog("[СИСТЕМА]: Логирование приостановлено")
        }
    }

    fun clearGeneralError() {
        _generalError.value = null
    }

    fun scanDevicesAndAutoConnect(): List<UsbDeviceInfo> {
        val list = scanDevices()
        val savedDeviceName = prefs.getString(KEY_LAST_DEVICE_NAME, null)
        val savedDeviceKey = prefs.getString(KEY_LAST_DEVICE_KEY, null)

        val target = if (savedDeviceKey != null) {
            list.firstOrNull { it.uniqueKey == savedDeviceKey }
        } else if (savedDeviceName != null) {
            list.firstOrNull { it.deviceName == savedDeviceName }
        } else {
            list.firstOrNull()
        }

        if (target != null && connectionStatus.value is ConnectionStatus.Disconnected) {
            selectedDeviceName.value = target.deviceName
            connect(target.deviceName)
        }
        return list
    }

    fun scanDevices(): List<UsbDeviceInfo> {
        val list = usbSerialManager.scanDevices()
        if (selectedDeviceName.value == null && list.isNotEmpty()) {
            selectedDeviceName.value = list.first().deviceName
        }
        return list
    }

    fun selectAndConnectDevice(device: UsbDeviceInfo) {
        selectedDeviceName.value = device.deviceName
        prefs.edit()
            .putString(KEY_LAST_DEVICE_NAME, device.deviceName)
            .putString(KEY_LAST_DEVICE_KEY, device.uniqueKey)
            .apply()

        connect(device.deviceName)
    }

    fun updateBaudRate(baudRate: Int) {
        selectedBaudRate.value = baudRate
        prefs.edit().putInt(KEY_LAST_BAUD_RATE, baudRate).apply()
    }

    fun updateCommandEnding(ending: String) {
        selectedCommandEnding.value = ending
        prefs.edit().putString(KEY_LAST_CMD_ENDING, ending).apply()
    }

    fun connect(deviceName: String? = selectedDeviceName.value) {
        try {
            GpsBridgeService.startService(context)
            val dev = deviceName ?: selectedDeviceName.value
            usbSerialManager.connect(dev, selectedBaudRate.value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect", e)
            _generalError.value = "Ошибка подключения: ${e.message ?: "Unknown"}"
        }
    }

    fun disconnect() {
        try {
            usbSerialManager.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect", e)
        }
    }

    fun sendCommand(command: String): Boolean {
        return try {
            usbSerialManager.sendCommand(command, selectedCommandEnding.value)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command", e)
            _generalError.value = "Ошибка отправки команды: ${e.message}"
            false
        }
    }

    fun setMockLocationActive(active: Boolean): Boolean {
        return try {
            if (active) {
                val res = mockLocationManager.startMock()
                if (res.isSuccess) {
                    _isMockLocationActive.value = true
                    _mockLocationError.value = null
                    addLog("Mock location provider started")
                    true
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Не удалось запустить фиктивное местоположение. Проверьте режим разработчика."
                    _isMockLocationActive.value = false
                    _mockLocationError.value = err
                    _generalError.value = err
                    addLog("Mock location error: $err")
                    false
                }
            } else {
                mockLocationManager.stopMock()
                _isMockLocationActive.value = false
                _mockLocationError.value = null
                addLog("Mock location provider stopped")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Mock location toggle error", e)
            val msg = e.message ?: "Ошибка фиктивного местоположения"
            _mockLocationError.value = msg
            _generalError.value = msg
            false
        }
    }

    fun addLog(message: String) {
        scope.launch {
            if (_isLoggingEnabled.value) {
                _logHistory.emit(message)
            }
        }
    }
}
