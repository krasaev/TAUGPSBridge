package ru.krasaev.taugpsbridge.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.krasaev.taugpsbridge.model.BluetoothDeviceInfo
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.ConnectionType
import ru.krasaev.taugpsbridge.model.GpsData
import ru.krasaev.taugpsbridge.model.UsbDeviceInfo
import ru.krasaev.taugpsbridge.service.GpsBridgeRepository

data class GpsUiState(
    val gpsData: GpsData = GpsData(),
    val connectionType: ConnectionType = ConnectionType.USB,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val isMockLocationActive: Boolean = false,
    val mockLocationError: String? = null,
    val generalError: String? = null,
    val availableDevices: List<UsbDeviceInfo> = emptyList(),
    val availableBluetoothDevices: List<BluetoothDeviceInfo> = emptyList(),
    val selectedDeviceName: String? = null,
    val selectedBluetoothAddress: String? = null,
    val selectedBaudRate: Int = 115200,
    val selectedCommandEnding: String = "\r\n",
    val commandInput: String = "",
    val isLoggingEnabled: Boolean = false,
    val logs: List<String> = emptyList()
) {
    /** Сервис считается запущенным только когда mock location активен. */
    val isServiceRunning: Boolean get() = isMockLocationActive
}

class GpsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GpsBridgeRepository.getInstance(application)

    private val _uiState = MutableStateFlow(GpsUiState())
    val uiState: StateFlow<GpsUiState> = _uiState.asStateFlow()

    init {
        observeConnectionType()
        observeGpsData()
        observeConnectionStatus()
        observeMockLocation()
        observeErrors()
        observeDevices()
        observeSettings()
        observeLogs()

        // Первоначальное сканирование (в фоне, не блокирует UI)
        viewModelScope.launch {
            scanDevices()
            scanBluetoothDevices()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Наблюдение за репозиторием
    // ═══════════════════════════════════════════════════════════

    private fun observeConnectionType() {
        viewModelScope.launch {
            repository.selectedConnectionType.collect { type ->
                _uiState.update { it.copy(connectionType = type) }
            }
        }
    }

    private fun observeGpsData() {
        viewModelScope.launch {
            repository.gpsData.collect { data ->
                _uiState.update { it.copy(gpsData = data) }
            }
        }
    }

    private fun observeConnectionStatus() {
        viewModelScope.launch {
            repository.connectionStatus.collect { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }
    }

    private fun observeMockLocation() {
        viewModelScope.launch {
            repository.isMockLocationActive.collect { active ->
                _uiState.update { it.copy(isMockLocationActive = active) }
            }
        }
    }

    private fun observeErrors() {
        viewModelScope.launch {
            repository.mockLocationError.collect { error ->
                _uiState.update { it.copy(mockLocationError = error) }
            }
        }
        viewModelScope.launch {
            repository.generalError.collect { error ->
                _uiState.update { it.copy(generalError = error) }
            }
        }
    }

    private fun observeDevices() {
        viewModelScope.launch {
            repository.availableDevices.collect { devices ->
                _uiState.update { it.copy(availableDevices = devices) }
            }
        }
        viewModelScope.launch {
            repository.availableBluetoothDevices.collect { btDevices ->
                _uiState.update { it.copy(availableBluetoothDevices = btDevices) }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            repository.selectedBaudRate.collect { baud ->
                _uiState.update { it.copy(selectedBaudRate = baud) }
            }
        }
        viewModelScope.launch {
            repository.selectedCommandEnding.collect { ending ->
                _uiState.update { it.copy(selectedCommandEnding = ending) }
            }
        }
        viewModelScope.launch {
            repository.isLoggingEnabled.collect { enabled ->
                _uiState.update { it.copy(isLoggingEnabled = enabled) }
            }
        }
    }

    private fun observeLogs() {
        viewModelScope.launch {
            repository.logHistory.collect { log ->
                _uiState.update { state ->
                    // Держим не больше 300 строк
                    val newLogs = if (state.logs.size >= MAX_LOGS) {
                        state.logs.drop(state.logs.size - MAX_LOGS + 1) + log
                    } else {
                        state.logs + log
                    }
                    state.copy(logs = newLogs)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Действия пользователя
    // ═══════════════════════════════════════════════════════════

    fun selectConnectionType(type: ConnectionType) {
        repository.selectConnectionType(type)
        _uiState.update { it.copy(connectionType = type) }
    }

    fun scanDevices() {
        val list = repository.scanDevices()
        _uiState.update {
            it.copy(
                availableDevices = list,
                selectedDeviceName = repository.selectedDeviceName.value
            )
        }
    }

    fun scanBluetoothDevices() {
        val list = repository.scanBluetoothDevices()
        _uiState.update {
            it.copy(
                availableBluetoothDevices = list,
                selectedBluetoothAddress = repository.selectedBluetoothAddress.value
            )
        }
    }

    fun selectAndConnectDevice(device: UsbDeviceInfo) {
        repository.selectAndConnectDevice(device)
        _uiState.update { it.copy(selectedDeviceName = device.deviceName) }
    }

    fun selectAndConnectBluetoothDevice(device: BluetoothDeviceInfo) {
        repository.selectAndConnectBluetoothDevice(device)
        _uiState.update { it.copy(selectedBluetoothAddress = device.address) }
    }

    fun selectBaudRate(baudRate: Int) {
        repository.updateBaudRate(baudRate)
        _uiState.update { it.copy(selectedBaudRate = baudRate) }
    }

    fun selectCommandEnding(ending: String) {
        repository.updateCommandEnding(ending)
        _uiState.update { it.copy(selectedCommandEnding = ending) }
    }

    fun onCommandInputChanged(input: String) {
        _uiState.update { it.copy(commandInput = input) }
    }

    /**
     * Отправка команды. Если [customCommand] = null — берётся ввод из UI,
     * и поле очищается только при успешной отправке.
     */
    fun sendCommand(customCommand: String? = null) {
        val cmd = customCommand ?: _uiState.value.commandInput
        if (cmd.isBlank()) return

        val success = repository.sendCommand(cmd)

        // Очищаем поле только если пользователь отправлял из UI и всё прошло успешно
        if (customCommand == null && success) {
            _uiState.update { it.copy(commandInput = "") }
        }
    }

    fun connect() {
        repository.connect()
    }

    fun disconnect() {
        repository.disconnect()
    }

    fun toggleMockLocation(enable: Boolean) {
        repository.setMockLocationActive(enable)
    }

    fun setLoggingEnabled(enabled: Boolean) {
        repository.setLoggingEnabled(enabled)
    }

    fun toggleLogging() {
        repository.setLoggingEnabled(!_uiState.value.isLoggingEnabled)
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun clearGeneralError() {
        repository.clearGeneralError()
    }

    fun clearMockLocationError() {
        repository.clearMockLocationError()
    }

    private companion object {
        const val MAX_LOGS = 300
    }
}