package ru.krasaev.taugpsbridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.krasaev.taugpsbridge.ui.theme.TAUGPSBridgeTheme
import ru.krasaev.taugpsbridge.viewmodel.GpsUiState
import ru.krasaev.taugpsbridge.viewmodel.GpsViewModel

data class PresetCommand(
    val title: String,
    val command: String,
    val description: String
)

private val COMMAND_PRESETS = listOf(
    PresetCommand("Версия модуля (HEX)", "F1 D9 0A 04 00 00 0E 34", "Запрос версий ПО (SW) и железа (HW)"),
    PresetCommand("Статус антенны (HEX)", "F1 D9 06 01 03 00 F0 20 01 1B 50", "Включение \$GNTXT вывода статуса антенны"),
    PresetCommand("Сброс INS (RESET)", "\$PCAS10,0*1C", "Полный сброс калибровки инерциальной системы"),
    PresetCommand("Горячий старт", "\$PCAS04,1*18", "Hot start с сохранением эфемерид"),
    PresetCommand("Тёплый старт", "\$PCAS04,2*1B", "Warm start"),
    PresetCommand("Холодный старт", "\$PCAS04,3*1A", "Cold start (очистка всех данных)"),
    PresetCommand("Скорость 115200", "\$PCAS01,5*19", "Установка скорости 115200 бод"),
    PresetCommand("Частота 1 Гц", "\$PCAS02,1000*2E", "Интервал выдачи 1000 мс"),
    PresetCommand("Частота 5 Гц", "\$PCAS02,200*1D", "Интервал выдачи 200 мс"),
    PresetCommand("Частота 10 Гц", "\$PCAS02,100*1E", "Интервал выдачи 100 мс"),
    PresetCommand("Включить все NMEA", "\$PCAS03,1,1,1,1,1,1,1,1,0,0,,,0,0*02", "GGA, GLL, GSA, GSV, RMC, VTG, ZDA, TXT")
)

/**
 * Stateful wrapper connecting ViewModel to the UI.
 */
@Composable
fun LogsScreen(
    viewModel: GpsViewModel,
    uiState: GpsUiState,
    modifier: Modifier = Modifier
) {
    LogsScreenContent(
        uiState = uiState,
        onCommandInputChanged = viewModel::onCommandInputChanged,
        onSendCommand = { viewModel.sendCommand() },
        onToggleLogging = viewModel::toggleLogging,
        onClearLogs = viewModel::clearLogs,
        modifier = modifier
    )
}

/**
 * Stateless UI composable for Logs and Commands screen.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LogsScreenContent(
    uiState: GpsUiState,
    onCommandInputChanged: (String) -> Unit,
    onSendCommand: () -> Unit,
    onToggleLogging: () -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var isPresetsExpanded by remember { mutableStateOf(false) }

    // Auto-scroll to bottom when new logs arrive (if logging is enabled)
    LaunchedEffect(uiState.logs.size) {
        if (uiState.logs.isNotEmpty()) {
            listState.animateScrollToItem(uiState.logs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // =========================================================================
        // ВЕРХНЯЯ ЧАСТЬ: ОТПРАВКА КОМАНД И ПРЕСЕТЫ
        // =========================================================================
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = "Commands",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ОТПРАВКА КОМАНД ПО USB",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Button to toggle presets dropdown/list
                    OutlinedButton(
                        onClick = { isPresetsExpanded = !isPresetsExpanded },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Пресеты", style = MaterialTheme.typography.labelSmall)
                        Icon(
                            imageVector = if (isPresetsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Presets",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Command Text Input with Send button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.commandInput,
                        onValueChange = onCommandInputChanged,
                        placeholder = { Text("Введите NMEA или HEX...", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { isPresetsExpanded = true },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        trailingIcon = {
                            if (uiState.commandInput.isNotEmpty()) {
                                IconButton(onClick = { onCommandInputChanged("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onSendCommand,
                        enabled = uiState.commandInput.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Presets expanded section
                AnimatedVisibility(visible = isPresetsExpanded) {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        Text(
                            text = "Выберите команду для подстановки:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            COMMAND_PRESETS.forEach { preset ->
                                FilterChip(
                                    selected = uiState.commandInput == preset.command,
                                    onClick = {
                                        onCommandInputChanged(preset.command)
                                        isPresetsExpanded = false
                                    },
                                    label = {
                                        Text(
                                            text = preset.title,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // =========================================================================
        // НИЖНЯЯ ЧАСТЬ: ТЕРМИНАЛ ЛОГОВ С КНОПКАМИ СТАРТ / ПАУЗА / КОПИРОВАНИЕ / СТЕРЕТЬ
        // =========================================================================
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Status Header Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "ТЕРМИНАЛ ЛОГОВ",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Surface(
                        color = if (uiState.isLoggingEnabled) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color(0xFFFFB300).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (uiState.isLoggingEnabled) "🟢 Активно" else "⏸️ На паузе",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (uiState.isLoggingEnabled) Color(0xFF4CAF50) else Color(0xFFFFB300),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action Controls Toolbar (Full width, clearly visible buttons)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Start / Pause button
                    Button(
                        onClick = onToggleLogging,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isLoggingEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.isLoggingEnabled) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isLoggingEnabled) "Pause" else "Start",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (uiState.isLoggingEnabled) "Пауза" else "Старт",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // Copy button
                    OutlinedButton(
                        onClick = {
                            if (uiState.logs.isNotEmpty()) {
                                val fullLogText = uiState.logs.joinToString("\n")
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("TAU GPS Logs", fullLogText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Логи скопированы (${uiState.logs.size} строк)", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Логи пусты", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Копировать", style = MaterialTheme.typography.labelSmall)
                    }

                    // Clear button ("Стереть")
                    OutlinedButton(
                        onClick = {
                            onClearLogs()
                            Toast.makeText(context, "Логи очищены", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Стереть",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Стереть", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                // Terminal Box
                Surface(
                    color = Color(0xFF1E1E1E),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (uiState.logs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (uiState.isLoggingEnabled) "Ожидание входящих данных..." else "Логирование отключено по умолчанию.\nНажмите «Старт», чтобы начать запись логов.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            items(uiState.logs) { log ->
                                val textColor = when {
                                    log.startsWith("TX") -> Color(0xFF64B5F6) // Blue
                                    log.startsWith("RX [VERSION]") -> Color(0xFF81C784) // Light green
                                    log.startsWith("RX [HEX]") -> Color(0xFFFFB74D) // Orange for HEX data
                                    log.startsWith("RX") -> Color(0xFFE0E0E0) // Light gray
                                    log.contains("Error", ignoreCase = true) || log.contains("Ошибка", ignoreCase = true) -> Color(0xFFE57373) // Red
                                    log.startsWith("[СИСТЕМА]") -> Color(0xFFFFD54F) // Yellow
                                    else -> Color(0xFFB0BEC5)
                                }
                                Text(
                                    text = log,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = textColor,
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// PREVIEWS
// =========================================================================
@Preview(name = "Logs Screen - Light", showBackground = true)
@Preview(name = "Logs Screen - Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LogsScreenPreview() {
    TAUGPSBridgeTheme {
        LogsScreenContent(
            uiState = GpsUiState(
                isLoggingEnabled = true,
                commandInput = "\$PCAS04,1*18",
                logs = listOf(
                    "[СИСТЕМА] Подключение к порту ttyUSB0 (115200 бод)...",
                    "TX: \$PCAS04,1*18",
                    "RX: \$GNGGA,142845.00,5545.0746,N,03737.1054,E,4,24,0.82,156.4,M,14.2,M,,*4A",
                    "RX: \$GNRMC,142845.00,A,5545.0746,N,03737.1054,E,34.6,128.5,200926,,,D*7F",
                    "RX [VERSION]: TAU1201-DR SW:V2.3.1 HW:1.0"
                )
            ),
            onCommandInputChanged = {},
            onSendCommand = {},
            onToggleLogging = {},
            onClearLogs = {}
        )
    }
}

@Preview(name = "Logs Screen - Empty State", showBackground = true)
@Composable
fun LogsScreenEmptyPreview() {
    TAUGPSBridgeTheme {
        LogsScreenContent(
            uiState = GpsUiState(
                isLoggingEnabled = false,
                commandInput = "",
                logs = emptyList()
            ),
            onCommandInputChanged = {},
            onSendCommand = {},
            onToggleLogging = {},
            onClearLogs = {}
        )
    }
}
