package ru.krasaev.taugpsbridge.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.krasaev.taugpsbridge.model.BluetoothDeviceInfo
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.ConnectionType
import ru.krasaev.taugpsbridge.model.ModuleInfo
import ru.krasaev.taugpsbridge.model.UsbDeviceInfo
import ru.krasaev.taugpsbridge.ui.theme.TAUGPSBridgeTheme
import ru.krasaev.taugpsbridge.viewmodel.GpsUiState
import ru.krasaev.taugpsbridge.viewmodel.GpsViewModel

private val BAUD_RATES = listOf(4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600)
private val COMMAND_ENDINGS = listOf(
    "\r\n" to "CRLF (\\r\\n)",
    "\n" to "LF (\\n)",
    "\r" to "CR (\\r)",
    "" to "Без окончания"
)

private val StatusGreen = Color(0xFF4CAF50)
private val StatusYellow = Color(0xFFFFB300)
private val StatusRed = Color(0xFFF44336)
private val StatusGray = Color(0xFF9E9E9E)

/**
 * Stateful wrapper connecting ViewModel to the Settings screen.
 */
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
            } catch (e: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (e2: Exception) {
                    // Ignore
                }
            }
        },
        modifier = modifier
    )
}

/**
 * Stateless UI composable for Settings screen.
 */
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
        item {
            Spacer(modifier = Modifier.height(4.dp))
        }

        // =========================================================================
        // 1. ЕДИНЫЙ БЛОК: ТИП ПОДКЛЮЧЕНИЯ И ВЫБОР УСТРОЙСТВА
        // =========================================================================
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Title and Connection Status Row
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
                            color = if (isConnected) StatusGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
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

                    Spacer(modifier = Modifier.height(10.dp))

                    // Connection Type Switcher
                    TabRow(
                        selectedTabIndex = if (uiState.connectionType == ConnectionType.USB) 0 else 1,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Tab(
                            selected = uiState.connectionType == ConnectionType.USB,
                            onClick = { onSelectConnectionType(ConnectionType.USB) },
                            text = { Text("USB OTG", fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.Usb, contentDescription = "USB") }
                        )
                        Tab(
                            selected = uiState.connectionType == ConnectionType.BLUETOOTH,
                            onClick = { onSelectConnectionType(ConnectionType.BLUETOOTH) },
                            text = { Text("Bluetooth SPP", fontWeight = FontWeight.Bold) },
                            icon = { Icon(Icons.Default.Bluetooth, contentDescription = "Bluetooth") }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Devices Header & Scan Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (uiState.connectionType == ConnectionType.USB) "Список USB-устройств:" else "Список Bluetooth-устройств:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(
                            onClick = if (uiState.connectionType == ConnectionType.USB) onScanUsbDevices else onScanBluetoothDevices,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Scan", modifier = Modifier.size(20.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Device List Content based on Connection Type
                    if (uiState.connectionType == ConnectionType.USB) {
                        if (uiState.availableDevices.isEmpty()) {
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
                                        imageVector = Icons.Default.Usb,
                                        contentDescription = "No USB",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "USB-устройства не обнаружены",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Подключите приёмник через USB OTG и нажмите кнопку обновления",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                uiState.availableDevices.forEach { device ->
                                    val isSelected = uiState.selectedDeviceName == device.deviceName
                                    val isDeviceConnected = isConnected && isSelected

                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(12.dp),
                                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectUsbDevice(device) }
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = "Select",
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = device.displayName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "Порт: ${device.deviceName} • VID:${device.vendorId.toString(16).padStart(4, '0')} PID:${device.productId.toString(16).padStart(4, '0')}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                if (isDeviceConnected) {
                                                    Surface(
                                                        color = StatusGreen.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(8.dp)
                                                                    .clip(CircleShape)
                                                                    .background(StatusGreen)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                text = "Активно",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = StatusGreen,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            // INLINE MODULE INFO (For selected/connected USB device)
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                InlineModuleInfoBanner(
                                                    moduleInfo = uiState.gpsData.moduleInfo,
                                                    isConnected = isConnected
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Bluetooth device list
                        if (uiState.availableBluetoothDevices.isEmpty()) {
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
                                        imageVector = Icons.Default.Bluetooth,
                                        contentDescription = "No BT",
                                        tint = Color.Gray,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Bluetooth-устройства не найдены",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Убедитесь, что Bluetooth включён, а приёмник сопряжён с телефоном",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                uiState.availableBluetoothDevices.forEach { device ->
                                    val isSelected = uiState.selectedBluetoothAddress == device.address
                                    val isDeviceConnected = isConnected && isSelected

                                    Surface(
                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(12.dp),
                                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onSelectBluetoothDevice(device) }
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                                    contentDescription = "Select",
                                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = if (device.name.isNotBlank()) device.name else "Неизвестное устройство",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "${device.address} • ${if (device.isBonded) "Сопряжено" else "Найдено"}",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }

                                                if (isDeviceConnected) {
                                                    Surface(
                                                        color = StatusGreen.copy(alpha = 0.2f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(8.dp)
                                                                    .clip(CircleShape)
                                                                    .background(StatusGreen)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Text(
                                                                text = "Активно",
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = StatusGreen,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            // INLINE MODULE INFO (For selected/connected Bluetooth device)
                                            if (isSelected) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                InlineModuleInfoBanner(
                                                    moduleInfo = uiState.gpsData.moduleInfo,
                                                    isConnected = isConnected
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Disconnect button
                    if (isConnected) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Disconnect", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Отключить соединение")
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 2. ПАРАМЕТРЫ СЕРИЙНОГО ПОРТА И КОМАНД
        // =========================================================================
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = "BaudRate",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ПАРАМЕТРЫ ПОРТА И КОМАНД",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Скорость порта (Baud Rate):", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        fontWeight = if (uiState.selectedBaudRate == rate) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(10.dp))

                    Text("Окончание строки команд:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                        fontWeight = if (uiState.selectedCommandEnding == ending) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 3. ФИКТИВНОЕ МЕСТОПОЛОЖЕНИЕ (MOCK LOCATION)
        // =========================================================================
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
                        text = "Трансляция координат с GPS-приёмника в систему Android для работы Яндекс.Навигатора, 2ГИС и других приложений.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = onOpenDevSettings,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Dev Settings", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Открыть «Параметры разработчика»")
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Inline banner displaying Module Info inside the selected device card.
 */
@Composable
private fun InlineModuleInfoBanner(
    moduleInfo: ModuleInfo,
    isConnected: Boolean
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = "Module",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "ИНФОРМАЦИЯ О МОДУЛЕ TAU",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (!isConnected) {
                Text(
                    text = "Подключитесь к устройству для автоматического опроса версии модуля.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!moduleInfo.isDetected) {
                Text(
                    text = "Опрос версии модуля TAU (F1 D9)...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("Модель (HW):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = moduleInfo.displayModel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text("Прошивка (SW):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = moduleInfo.displayFirmware,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Тип: ${moduleInfo.typeDescription}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (moduleInfo.isDualFrequency == true) StatusGreen else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// =========================================================================
// PREVIEWS
// =========================================================================
@Preview(name = "Settings Screen - Connected USB", showBackground = true)
@Preview(name = "Settings Screen - Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SettingsScreenPreview() {
    TAUGPSBridgeTheme {
        SettingsScreenContent(
            uiState = GpsUiState(
                connectionType = ConnectionType.USB,
                connectionStatus = ConnectionStatus.Connected("ttyUSB0", 115200),
                selectedDeviceName = "ttyUSB0",
                selectedBaudRate = 115200,
                selectedCommandEnding = "\r\n",
                isMockLocationActive = true,
                availableDevices = listOf(
                    UsbDeviceInfo(
                        deviceName = "ttyUSB0",
                        vendorId = 0x10c4,
                        productId = 0xea60,
                        manufacturerName = "TAU",
                        productName = "TAU1201-DR Module",
                        serialNumber = "123456",
                        portCount = 1,
                        hasPermission = true
                    ),
                    UsbDeviceInfo(
                        deviceName = "ttyUSB1",
                        vendorId = 0x0403,
                        productId = 0x6001,
                        manufacturerName = "FTDI",
                        productName = "FT232R USB UART",
                        serialNumber = "654321",
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
                        swVersion = "V2.3.1",
                        hwVersion = "TAU1201-DR",
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

@Preview(name = "Settings Screen - Empty State", showBackground = true)
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
