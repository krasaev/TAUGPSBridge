package ru.krasaev.taugpsbridge.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.GpsData
import ru.krasaev.taugpsbridge.model.UsbDeviceInfo
import ru.krasaev.taugpsbridge.service.GpsBridgeRepository
import ru.krasaev.taugpsbridge.service.GpsBridgeService

data class GpsUiState(
    val gpsData: GpsData = GpsData(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val isMockLocationActive: Boolean = false,
    val mockLocationError: String? = null,
    val generalError: String? = null,
    val availableDevices: List<UsbDeviceInfo> = emptyList(),
    val selectedDeviceName: String? = null,
    val selectedBaudRate: Int = 115200,
    val selectedCommandEnding: String = "\r\n",
    val commandInput: String = "",
    val isLoggingEnabled: Boolean = false,
    val logs: List<String> = emptyList(),
    val isServiceRunning: Boolean = false
)

class GpsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = GpsBridgeRepository.getInstance(application)

    private val _uiState = MutableStateFlow(GpsUiState())
    val uiState: StateFlow<GpsUiState> = _uiState.asStateFlow()

    init {
        // Collect GPS Data
        viewModelScope.launch {
            repository.gpsData.collectLatest { data ->
                _uiState.update { it.copy(gpsData = data) }
            }
        }

        // Collect Connection Status
        viewModelScope.launch {
            repository.connectionStatus.collectLatest { status ->
                _uiState.update { it.copy(connectionStatus = status) }
            }
        }

        // Collect Mock Location Active state
        viewModelScope.launch {
            repository.isMockLocationActive.collectLatest { active ->
                _uiState.update { it.copy(isMockLocationActive = active) }
            }
        }

        // Collect Mock Location Error
        viewModelScope.launch {
            repository.mockLocationError.collectLatest { error ->
                _uiState.update { it.copy(mockLocationError = error) }
            }
        }

        // Collect General Error
        viewModelScope.launch {
            repository.generalError.collectLatest { error ->
                _uiState.update { it.copy(generalError = error) }
            }
        }

        // Collect Available Devices
        viewModelScope.launch {
            repository.availableDevices.collectLatest { devices ->
                _uiState.update { it.copy(availableDevices = devices) }
            }
        }

        // Collect Logging Enabled state
        viewModelScope.launch {
            repository.isLoggingEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(isLoggingEnabled = enabled) }
            }
        }

        // Collect Selected Baud Rate
        viewModelScope.launch {
            repository.selectedBaudRate.collectLatest { baud ->
                _uiState.update { it.copy(selectedBaudRate = baud) }
            }
        }

        // Collect Selected Command Ending
        viewModelScope.launch {
            repository.selectedCommandEnding.collectLatest { ending ->
                _uiState.update { it.copy(selectedCommandEnding = ending) }
            }
        }

        // Collect Logs
        viewModelScope.launch {
            repository.logHistory.collect { log ->
                _uiState.update { state ->
                    val updatedLogs = (state.logs + log).takeLast(300)
                    state.copy(logs = updatedLogs)
                }
            }
        }

        scanDevices()
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

    fun selectAndConnectDevice(device: UsbDeviceInfo) {
        repository.selectAndConnectDevice(device)
        _uiState.update {
            it.copy(
                selectedDeviceName = device.deviceName,
                isServiceRunning = true
            )
        }
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

    fun setLoggingEnabled(enabled: Boolean) {
        repository.setLoggingEnabled(enabled)
    }

    fun toggleLogging() {
        val current = _uiState.value.isLoggingEnabled
        repository.setLoggingEnabled(!current)
    }

    fun clearGeneralError() {
        repository.clearGeneralError()
    }

    fun connect() {
        GpsBridgeService.startService(getApplication())
        repository.connect()
        _uiState.update { it.copy(isServiceRunning = true) }
    }

    fun disconnect() {
        repository.disconnect()
        _uiState.update { it.copy(isServiceRunning = false) }
    }

    fun toggleMockLocation(enable: Boolean) {
        val success = repository.setMockLocationActive(enable)
        if (enable && success) {
            GpsBridgeService.startService(getApplication())
        }
    }

    fun sendCommand(customCommand: String? = null) {
        val cmd = customCommand ?: _uiState.value.commandInput
        if (cmd.isNotBlank()) {
            repository.sendCommand(cmd)
            if (customCommand == null) {
                _uiState.update { it.copy(commandInput = "") }
            }
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }
}
