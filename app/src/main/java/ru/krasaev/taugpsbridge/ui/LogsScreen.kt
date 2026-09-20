package ru.krasaev.taugpsbridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.krasaev.taugpsbridge.ui.theme.TAUGPSBridgeTheme
import ru.krasaev.taugpsbridge.viewmodel.GpsUiState
import ru.krasaev.taugpsbridge.viewmodel.GpsViewModel

// ═══════════════════════════════════════════════════════════════
//  Константы цветов
// ═══════════════════════════════════════════════════════════════

private val StatusGreen = Color(0xFF4CAF50)
private val StatusYellow = Color(0xFFFFB300)

private val TerminalBg = Color(0xFF1E1E1E)
private val LogTxColor = Color(0xFF64B5F6)          // синий — TX
private val LogRxVersionColor = Color(0xFF81C784)   // зелёный — версия
private val LogRxHexColor = Color(0xFFFFB74D)       // оранжевый — HEX
private val LogRxColor = Color(0xFFE0E0E0)          // светло-серый — RX
private val LogErrorColor = Color(0xFFE57373)       // красный — ошибки
private val LogSystemColor = Color(0xFFFFD54F)      // жёлтый — системные
private val LogDefaultColor = Color(0xFFB0BEC5)     // серый — прочее

// ═══════════════════════════════════════════════════════════════
//  Модель пресета (не меняем — как в проекте)
// ═══════════════════════════════════════════════════════════════

data class PresetCommand(
    val title: String,
    val command: String,
    val description: String
)

// ═══════════════════════════════════════════════════════════════
//  Список пресетов
// ═══════════════════════════════════════════════════════════════

private val COMMAND_PRESETS: List<PresetCommand> = listOf(
    PresetCommand(
        title = "Версия модуля",
        command = "F1 D9 0A 04 00 00 0E 34",
        description = "Запрос версий прошивки (SW) и чипа (HW)"
    ),
    PresetCommand(
        title = "Горячий старт",
        command = "F1 D9 06 40 01 00 03 4A 24",
        description = "Быстрый фикс из сохранённых данных (1–5 секунд)"
    ),
    PresetCommand(
        title = "Тёплый старт",
        command = "F1 D9 06 40 01 00 02 49 23",
        description = "Сброс эфемерид, альманах сохраняется (30–60 секунд)"
    ),
    PresetCommand(
        title = "Холодный старт",
        command = "F1 D9 06 40 01 00 01 48 22",
        description = "Полный сброс данных GNSS (фикс может занять до 5 минут)"
    ),
    PresetCommand(
        title = "Сохранить настройки",
        command = "F1 D9 06 09 08 00 00 00 00 00 2F 00 00 00 46 B7",
        description = "Записать конфигурацию во flash (без калибровки INS)"
    ),
    PresetCommand(
        title = "Сохранить калибровку INS",
        command = "F1 D9 06 09 08 00 00 00 00 00 00 00 00 00 00 17 FB",
        description = "Записать параметры INS. Делать перед выключением питания"
    ),
    PresetCommand(
        title = "Двухчастотный режим (L1+L5)",
        command = "F1 D9 06 0C 04 00 25 82 00 04 C1 BA",
        description = "GPS L1/L5 + BDS B1/B2a + QZSS. Максимальная точность в городе"
    ),
    PresetCommand(
        title = "Одночастотный режим (L1)",
        command = "F1 D9 06 0C 04 00 25 00 00 00 3B 30",
        description = "GPS L1 + BDS B1 + QZSS. Меньше нагрузка, но хуже в городе"
    ),
    PresetCommand(
        title = "Статус антенны: вкл",
        command = "F1 D9 06 01 03 00 F0 20 01 1B 50",
        description = "Включить вывод GNTXT со статусом ANT_OK / ANT_OPEN / ANT_SHORT"
    ),
    PresetCommand(
        title = "Статус антенны: выкл",
        command = "F1 D9 06 01 03 00 F0 20 00 1A 4F",
        description = "Прекратить вывод статуса антенны"
    ),
    PresetCommand(
        title = "Сброс до заводских",
        command = "F1 D9 06 09 08 00 02 00 00 00 FF FF FF FF 15 01",
        description = "Удаляет все настройки и калибровку INS. После — заново калибровать"
    )
)

// Опасные команды (требуют подтверждения)
private val DANGEROUS_TITLES: Set<String> = setOf("Сброс до заводских")

// Категории пресетов (порядок сохраняется по первому появлению)
private val PRESETS_BY_CATEGORY: Map<String, List<PresetCommand>> =
    COMMAND_PRESETS.groupBy { preset ->
        when {
            preset.title.contains("старт", ignoreCase = true) -> "СТАРТ GNSS"
            preset.title.contains("Сохранить", ignoreCase = true) -> "СОХРАНЕНИЕ"
            preset.title.contains("режим", ignoreCase = true) -> "РЕЖИМЫ ПРИЁМА"
            preset.title.contains("антенны", ignoreCase = true) -> "АНТЕННА"
            preset.title in DANGEROUS_TITLES -> "⚠️ ОПАСНОЕ"
            else -> "ИНФОРМАЦИЯ"
        }
    }

// ═══════════════════════════════════════════════════════════════
//  Stateful wrapper
// ═══════════════════════════════════════════════════════════════

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

// ═══════════════════════════════════════════════════════════════
//  Stateless content
// ═══════════════════════════════════════════════════════════════

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
    var pendingPreset by remember { mutableStateOf<PresetCommand?>(null) }

    // Отправка команды после того, как state обновился
    LaunchedEffect(uiState.commandInput, pendingPreset) {
        val preset = pendingPreset ?: return@LaunchedEffect
        if (uiState.commandInput == preset.command) {
            onSendCommand()
            pendingPreset = null
            isPresetsExpanded = false
        }
    }

    // Автоскролл только если пользователь внизу
    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= uiState.logs.size - 2
        }
    }

    LaunchedEffect(uiState.logs.size) {
        if (isAtBottom && uiState.logs.isNotEmpty()) {
            listState.scrollToItem(uiState.logs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .imePadding()
    ) {
        // ═══════════════════════════════════════════════════════
        // ВЕРХНЯЯ ЧАСТЬ: КОМАНДА + ПРЕСЕТЫ
        // ═══════════════════════════════════════════════════════
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header
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

                    OutlinedButton(
                        onClick = { isPresetsExpanded = !isPresetsExpanded },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Пресеты", style = MaterialTheme.typography.labelSmall)
                        Icon(
                            imageVector = if (isPresetsExpanded) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Presets",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Поле ввода + Send
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.commandInput,
                        onValueChange = onCommandInputChanged,
                        placeholder = {
                            Text(
                                "Введите NMEA или HEX...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (uiState.commandInput.isNotBlank()) onSendCommand()
                        }),
                        trailingIcon = {
                            if (uiState.commandInput.isNotEmpty()) {
                                IconButton(onClick = { onCommandInputChanged("") }) {
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = onSendCommand,
                        enabled = uiState.commandInput.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(52.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // ═══════════════════════════════════════════════════
                // Пресеты с группировкой по категориям
                // ═══════════════════════════════════════════════════
                AnimatedVisibility(visible = isPresetsExpanded) {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            PRESETS_BY_CATEGORY.forEach { (category, presets) ->
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                                )
                                presets.forEach { preset ->
                                    PresetRow(
                                        preset = preset,
                                        isDangerous = preset.title in DANGEROUS_TITLES,
                                        onClick = {
                                            val dangerous = preset.title in DANGEROUS_TITLES
                                            if (dangerous) {
                                                pendingPreset = preset
                                            } else {
                                                onCommandInputChanged(preset.command)
                                                pendingPreset = preset
                                            }
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ═══════════════════════════════════════════════════════
        // НИЖНЯЯ ЧАСТЬ: ТЕРМИНАЛ ЛОГОВ
        // ═══════════════════════════════════════════════════════
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Header
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
                        color = if (uiState.isLoggingEnabled)
                            StatusGreen.copy(alpha = 0.15f)
                        else
                            StatusYellow.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        if (uiState.isLoggingEnabled) StatusGreen else StatusYellow,
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isLoggingEnabled) "Активно" else "На паузе",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.isLoggingEnabled) StatusGreen else StatusYellow
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Кнопки управления
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = onToggleLogging,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.isLoggingEnabled)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.isLoggingEnabled) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (uiState.isLoggingEnabled) "Пауза" else "Старт",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            if (uiState.logs.isNotEmpty()) {
                                val fullLogText = uiState.logs.joinToString("\n")
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                        as ClipboardManager
                                val clip = ClipData.newPlainText("TAU GPS Logs", fullLogText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(
                                    context,
                                    "Логи скопированы (${uiState.logs.size} строк)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(context, "Логи пусты", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Копировать", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = {
                            onClearLogs()
                            Toast.makeText(context, "Логи очищены", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Стереть", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(8.dp))

                // Терминал
                Surface(
                    color = TerminalBg,
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
                                text = if (uiState.isLoggingEnabled)
                                    "Ожидание входящих данных..."
                                else
                                    "Логирование отключено по умолчанию.\nНажмите «Старт», чтобы начать запись логов.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        ) {
                            itemsIndexed(
                                items = uiState.logs,
                                key = { index, _ -> index }
                            ) { _, log ->
                                Text(
                                    text = log,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = logColor(log),
                                    modifier = Modifier.padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Диалог подтверждения для опасных команд
    // ═══════════════════════════════════════════════════════════
    val confirmPreset = pendingPreset
    if (confirmPreset != null && confirmPreset.title in DANGEROUS_TITLES) {
        AlertDialog(
            onDismissRequest = { pendingPreset = null },
            title = { Text("⚠️ Подтверждение") },
            text = {
                Text(
                    "${confirmPreset.title}\n\n${confirmPreset.description}\n\n" +
                            "Продолжить?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onCommandInputChanged(confirmPreset.command)
                    // pendingPreset останется — LaunchedEffect отправит после обновления state
                }) {
                    Text("Выполнить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPreset = null }) {
                    Text("Отмена")
                }
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  Helper: цвет строки лога по префиксу
// ═══════════════════════════════════════════════════════════════

private fun logColor(log: String): Color = when {
    log.startsWith("TX") -> LogTxColor
    log.startsWith("RX [VERSION]") -> LogRxVersionColor
    log.startsWith("RX [HEX]") -> LogRxHexColor
    log.startsWith("RX") -> LogRxColor
    log.contains("Error", ignoreCase = true) ||
            log.contains("Ошибка", ignoreCase = true) -> LogErrorColor

    log.startsWith("[СИСТЕМА]") -> LogSystemColor
    else -> LogDefaultColor
}

// ═══════════════════════════════════════════════════════════════
//  PresetRow — карточка одного пресета
// ═══════════════════════════════════════════════════════════════

@Composable
private fun PresetRow(
    preset: PresetCommand,
    isDangerous: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (isDangerous)
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        else
            MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = preset.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isDangerous)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = preset.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  PREVIEWS
// ═══════════════════════════════════════════════════════════════

@Preview(name = "Logs Screen - Light", showBackground = true)
@Preview(name = "Logs Screen - Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LogsScreenPreview() {
    TAUGPSBridgeTheme {
        LogsScreenContent(
            uiState = GpsUiState(
                isLoggingEnabled = true,
                commandInput = "F1 D9 0A 04 00 00 0E 34",
                logs = listOf(
                    "[СИСТЕМА] Подключение к порту ttyUSB0 (115200 бод)...",
                    "[СИСТЕМА] Порт открыт. Запрос версии модуля.",
                    "TX: F1 D9 0A 04 00 00 0E 34",
                    "RX [VERSION]: SW=3.M8C.4e08c7  HW=HD8040DF.017747a",
                    "RX [VERSION]: Модуль двухчастотный (DF)",
                    "RX: \$GNGGA,103550.000,4841.78649,N,04429.62784,E,1,13,1.04,64.1,M,3.7,M,,*79",
                    "RX: \$GNRMC,103550.000,A,4841.78649,N,04429.62784,E,0.358,130.01,200926,,,A,S*36",
                    "RX: \$GNTXT,01,01,02,ANT_OK,D2,ANT_SAT*67",
                    "RX: \$GNTXT,04,01,04,INS,G,1,,,,FLG,1,000000,1,0,6,-1,0,0,0,-5,-2,-96*30"
                )
            ),
            onCommandInputChanged = {},
            onSendCommand = {},
            onToggleLogging = {},
            onClearLogs = {}
        )
    }
}

@Preview(name = "Logs Screen - Empty", showBackground = true)
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