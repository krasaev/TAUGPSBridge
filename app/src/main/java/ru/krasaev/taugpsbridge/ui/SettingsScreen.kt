package ru.krasaev.taugpsbridge.ui

import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.krasaev.taugpsbridge.model.BluetoothDeviceInfo
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.ConnectionType
import ru.krasaev.taugpsbridge.model.ModuleInfo
import ru.krasaev.taugpsbridge.model.UsbDeviceInfo
import ru.krasaev.taugpsbridge.ui.components.DeviceRow
import ru.krasaev.taugpsbridge.ui.components.StatusGray
import ru.krasaev.taugpsbridge.ui.components.StatusGreen
import ru.krasaev.taugpsbridge.ui.theme.TAUGPSBridgeTheme
import ru.krasaev.taugpsbridge.viewmodel.GpsUiState
import ru.krasaev.taugpsbridge.viewmodel.GpsViewModel

// ═══════════════════════════════════════════════════════════════
//  Константы
// ═══════════════════════════════════════════════════════════════

private val BAUD_RATES = listOf(4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
private val COMMAND_ENDINGS = listOf(
    "\r\n" to "CRLF (\\r\\n)",
    "\n" to "LF (\\n)",
    "\r" to "CR (\\r)",
    "" to "Без окончания"
)

// ═══════════════════════════════════════════════════════════════
//  Stateful wrapper
// ═══════════════════════════════════════════════════════════════

@Composable
fun SettingsScreen(
    viewModel: GpsViewModel,
    uiState: GpsUiState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    SettingsScreenContent(
        uiState = uiState,
        onSelectConnectionType = viewModel::selectConnectionType,
        onScanUsbDevices = viewModel::scanDevices,
        onSelectUsbDevice = viewModel::selectAndConnectDevice,
        onScanBluetoothDevices = viewModel::scanBluetoothDevices,
        onSelectBluetoothDevice = viewModel::selectAndConnectBluetoothDevice,
        onDisconnect = viewModel::disconnect,
        onSelectBaudRate = viewModel::selectBaudRate,
        onSelectCommandEnding = viewModel::selectCommandEnding,
        onToggleMockLocation = viewModel::toggleMockLocation,
        onOpenDevSettings = {
            try {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (_: Exception) {
                    // ignore
                }
            }
        },
        modifier = modifier
    )
}

// ═══════════════════════════════════════════════════════════════
//  Stateless content
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreenContent(
    uiState: GpsUiState,
    onSelectConnectionType: (ConnectionType) -> Unit,
    onScanUsbDevices: () -> Unit,
    onSelectUsbDevice: (UsbDeviceInfo) -> Unit,
    onScanBluetoothDevices: () -> Unit,
    onSelectBluetoothDevice: (BluetoothDeviceInfo) -> Unit,
    onDisconnect: () -> Unit,
    onSelectBaudRate: (Int) -> Unit,
    onSelectCommandEnding: (String) -> Unit,
    onToggleMockLocation: (Boolean) -> Unit,
    onOpenDevSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected = uiState.connectionStatus is ConnectionStatus.Connected

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { Spacer(modifier = Modifier.height(4.dp)) }

        // ═══════════════════════════════════════════════════════
        // 1. ПОДКЛЮЧЕНИЕ И УСТРОЙСТВО
        // ═══════════════════════════════════════════════════════
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    ConnectionHeader(isConnected = isConnected)

                    Spacer(modifier = Modifier.height(10.dp))

                    ConnectionTypeTabs(
                        selectedType = uiState.connectionType,
                        onSelect = onSelectConnectionType
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    DevicesHeader(
                        connectionType = uiState.connectionType,
                        onScan = if (uiState.connectionType == ConnectionType.USB)
                            onScanUsbDevices
                        else
                            onScanBluetoothDevices
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    when (uiState.connectionType) {
                        ConnectionType.USB -> UsbDevicesList(
                            devices = uiState.availableDevices,
                            selectedName = uiState.selectedDeviceName,
                            isConnected = isConnected,
                            moduleInfo = uiState.gpsData.moduleInfo,
                            onSelect = onSelectUsbDevice
                        )
                        ConnectionType.BLUETOOTH -> BluetoothDevicesList(
                            devices = uiState.availableBluetoothDevices,
                            selectedAddress = uiState.selectedBluetoothAddress,
                            isConnected = isConnected,
                            moduleInfo = uiState.gpsData.moduleInfo,
                            onSelect = onSelectBluetoothDevice
                        )
                    }

                    if (isConnected) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.PowerSettingsNew,
                                contentDescription = "Disconnect",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Отключить соединение")
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════
        // 2. ПАРАМЕТРЫ ПОРТА И КОМАНД
        // ═══════════════════════════════════════════════════════
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    SectionHeader(
                        icon = Icons.Default.Speed,
                        title = "ПАРАМЕТРЫ ПОРТА И КОМАНД"
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "Скорость порта (Baud Rate):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BAUD_RATES.forEach { rate ->
                            FilterChip(
                                selected = uiState.selectedBaudRate == rate,
                                onClick = { onSelectBaudRate(rate) },
                                label = {
                                    Text(
                                        text = "$rate",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (uiState.selectedBaudRate == rate)
                                            FontWeight.Bold
                                        else
                                            FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        "Окончание строки команд:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        COMMAND_ENDINGS.forEach { (ending, label) ->
                            FilterChip(
                                selected = uiState.selectedCommandEnding == ending,
                                onClick = { onSelectCommandEnding(ending) },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (uiState.selectedCommandEnding == ending)
                                            FontWeight.Bold
                                        else
                                            FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════
        // 3. MOCK LOCATION
        // ═══════════════════════════════════════════════════════
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = "MockLocation",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Фиктивное местоположение",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Switch(
                            checked = uiState.isMockLocationActive,
                            onCheckedChange = onToggleMockLocation
                        )
                    }

                    Text(
                        text = "Трансляция координат с GPS-приёмника в систему Android " +
                                "для работы Яндекс.Навигатора, 2ГИС и других приложений.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = onOpenDevSettings,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Dev Settings",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть «Параметры разработчика»")
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Sub-composables
// ═══════════════════════════════════════════════════════════════

@Composable
private fun ConnectionHeader(isConnected: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ПОДКЛЮЧЕНИЕ И УСТРОЙСТВО",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Surface(
            color = if (isConnected) StatusGreen.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isConnected) StatusGreen else StatusGray)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isConnected) "Подключено" else "Отключено",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isConnected) StatusGreen else StatusGray,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ConnectionTypeTabs(
    selectedType: ConnectionType,
    onSelect: (ConnectionType) -> Unit
) {
    TabRow(
        selectedTabIndex = if (selectedType == ConnectionType.USB) 0 else 1,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Tab(
            selected = selectedType == ConnectionType.USB,
            onClick = { onSelect(ConnectionType.USB) },
            text = { Text("USB OTG", fontWeight = FontWeight.Bold) },
            icon = { Icon(Icons.Default.Usb, contentDescription = "USB") }
        )
        Tab(
            selected = selectedType == ConnectionType.BLUETOOTH,
            onClick = { onSelect(ConnectionType.BLUETOOTH) },
            text = { Text("Bluetooth SPP", fontWeight = FontWeight.Bold) },
            icon = { Icon(Icons.Default.Bluetooth, contentDescription = "Bluetooth") }
        )
    }
}

@Composable
private fun DevicesHeader(
    connectionType: ConnectionType,
    onScan: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (connectionType == ConnectionType.USB)
                "Список USB-устройств:"
            else
                "Список Bluetooth-устройств:",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onScan, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Scan",
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun UsbDevicesList(
    devices: List<UsbDeviceInfo>,
    selectedName: String?,
    isConnected: Boolean,
    moduleInfo: ModuleInfo,
    onSelect: (UsbDeviceInfo) -> Unit
) {
    if (devices.isEmpty()) {
        EmptyDevicesState(
            icon = Icons.Default.Usb,
            title = "USB-устройства не обнаружены",
            hint = "Подключите приёмник через USB OTG и нажмите кнопку обновления"
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        devices.forEach { device ->
            val isSelected = selectedName == device.deviceName
            val isDeviceConnected = isConnected && isSelected

            DeviceRow(
                isSelected = isSelected,
                isConnected = isDeviceConnected,
                onClick = { onSelect(device) },
                title = device.displayName.ifBlank { "USB-устройство" },
                subtitle = "Порт: ${device.deviceName} • " +
                        "VID:${device.vendorId.toString(16).padStart(4, '0')} " +
                        "PID:${device.productId.toString(16).padStart(4, '0')}",
                moduleInfo = moduleInfo
            )
        }
    }
}

@Composable
private fun BluetoothDevicesList(
    devices: List<BluetoothDeviceInfo>,
    selectedAddress: String?,
    isConnected: Boolean,
    moduleInfo: ModuleInfo,
    onSelect: (BluetoothDeviceInfo) -> Unit
) {
    if (devices.isEmpty()) {
        EmptyDevicesState(
            icon = Icons.Default.Bluetooth,
            title = "Bluetooth-устройства не найдены",
            hint = "Убедитесь, что Bluetooth включён, а приёмник сопряжён с телефоном"
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        devices.forEach { device ->
            val isSelected = selectedAddress == device.address
            val isDeviceConnected = isConnected && isSelected

            DeviceRow(
                isSelected = isSelected,
                isConnected = isDeviceConnected,
                onClick = { onSelect(device) },
                title = device.name.ifBlank { "Неизвестное устройство" },
                subtitle = "${device.address} • " +
                        if (device.isBonded) "Сопряжено" else "Найдено",
                moduleInfo = moduleInfo
            )
        }
    }
}

@Composable
private fun EmptyDevicesState(
    icon: ImageVector,
    title: String,
    hint: String
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  PREVIEWS
// ═══════════════════════════════════════════════════════════════

@Preview(name = "Settings - Connected USB", showBackground = true)
@Preview(name = "Settings - Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SettingsScreenPreview() {
    TAUGPSBridgeTheme {
        SettingsScreenContent(
            uiState = GpsUiState(
                connectionType = ConnectionType.USB,
                connectionStatus = ConnectionStatus.Connected(
                    deviceName = "ttyUSB0",
                    baudRate = 115200
                ),
                selectedDeviceName = "ttyUSB0",
                selectedBaudRate = 115200,
                selectedCommandEnding = "\r\n",
                isMockLocationActive = true,
                availableDevices = listOf(
                    UsbDeviceInfo(
                        deviceName = "ttyUSB0",
                        vendorId = 0x10c4,
                        productId = 0xea60,
                        manufacturerName = "Silicon Labs",
                        productName = "CP2102 USB-UART",
                        serialNumber = "0001",
                        portCount = 1,
                        hasPermission = true
                    ),
                    UsbDeviceInfo(
                        deviceName = "ttyUSB1",
                        vendorId = 0x0403,
                        productId = 0x6001,
                        manufacturerName = "FTDI",
                        productName = "FT232R USB UART",
                        serialNumber = "A50285BI",
                        portCount = 1,
                        hasPermission = true
                    )
                ),
                availableBluetoothDevices = listOf(
                    BluetoothDeviceInfo(
                        name = "TAU-GPS-BT",
                        address = "00:11:22:33:44:55",
                        isBonded = true
                    )
                ),
                gpsData = ru.krasaev.taugpsbridge.model.GpsData(
                    moduleInfo = ModuleInfo(
                        swVersion = "3.M8C.4e08c7",
                        hwVersion = "HD8040DF.017747a",
                        isDualFrequency = true,
                        isDetected = true
                    )
                )
            ),
            onSelectConnectionType = {},
            onScanUsbDevices = {},
            onSelectUsbDevice = {},
            onScanBluetoothDevices = {},
            onSelectBluetoothDevice = {},
            onDisconnect = {},
            onSelectBaudRate = {},
            onSelectCommandEnding = {},
            onToggleMockLocation = {},
            onOpenDevSettings = {}
        )
    }
}

@Preview(name = "Settings - Empty", showBackground = true)
@Composable
fun SettingsScreenEmptyPreview() {
    TAUGPSBridgeTheme {
        SettingsScreenContent(
            uiState = GpsUiState(
                connectionType = ConnectionType.USB,
                connectionStatus = ConnectionStatus.Disconnected,
                selectedDeviceName = null,
                availableDevices = emptyList()
            ),
            onSelectConnectionType = {},
            onScanUsbDevices = {},
            onSelectUsbDevice = {},
            onScanBluetoothDevices = {},
            onSelectBluetoothDevice = {},
            onDisconnect = {},
            onSelectBaudRate = {},
            onSelectCommandEnding = {},
            onToggleMockLocation = {},
            onOpenDevSettings = {}
        )
    }
}