package ru.krasaev.taugpsbridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.ui.LogsScreen
import ru.krasaev.taugpsbridge.ui.MainScreen
import ru.krasaev.taugpsbridge.ui.SettingsScreen
import ru.krasaev.taugpsbridge.ui.theme.TAUGPSBridgeTheme
import ru.krasaev.taugpsbridge.viewmodel.GpsUiState
import ru.krasaev.taugpsbridge.viewmodel.GpsViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: GpsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        installCrashHandler()

        setContent {
            TAUGPSBridgeTheme {
                RequestPermissionsOnStart()
                TauGpsApp(viewModel = viewModel)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Глобальный обработчик крашей
// ═══════════════════════════════════════════════════════════════

/**
 * Ловит необработанные исключения, показывает уведомление и сохраняет
 * информацию в SharedPreferences для просмотра при следующем запуске.
 *
 * ВАЖНО: обработчик работает в потоке, где произошёл краш (не в UI-потоке),
 * поэтому:
 *  - не вызываем Toast (может не успеть);
 *  - не трогаем UI напрямую;
 *  - используем NotificationManager — он живёт независимо от процесса.
 */
private fun ComponentActivity.installCrashHandler() {
    val prefs = getSharedPreferences(CRASH_PREFS, Context.MODE_PRIVATE)
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val summary = buildString {
                append(throwable::class.java.simpleName)
                throwable.message?.let { append(": ").append(it) }
            }
            val details = Log.getStackTraceString(throwable)
            Log.e(TAG_CRASH, "Uncaught exception: $summary\n$details", throwable)

            // Сохраняем в SharedPreferences — прочитаем при следующем запуске
            prefs.edit()
                .putString(KEY_LAST_CRASH_SUMMARY, summary)
                .putString(KEY_LAST_CRASH_DETAILS, details)
                .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
                .apply()

            // Показываем уведомление (переживёт смерть процесса)
            showCrashNotification(summary)
        } catch (inner: Throwable) {
            // Ошибка самого обработчика — не мешаем стандартному поведению
            Log.e(TAG_CRASH, "Crash handler failed", inner)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

/**
 * Показывает уведомление об ошибке с полным стектрейсом.
 * Тап открывает MainActivity.
 */
private fun ComponentActivity.showCrashNotification(summary: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        // Нет разрешения на уведомления — просто выходим. Инфа уже в prefs и logcat.
        return
    }

    try {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Канал уведомлений
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_CRASH,
                "Ошибки приложения",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Необработанные исключения"
            }
            manager.createNotificationChannel(channel)
        }

        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_CRASH)
            .setContentTitle("Ошибка в TAU GPS Bridge")
            .setContentText(summary.take(120))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "$summary\n\nТапните, чтобы открыть приложение и посмотреть детали."
                )
            )
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(NOTIF_ID_CRASH, notification)
    } catch (e: Throwable) {
        Log.e(TAG_CRASH, "Failed to show crash notification", e)
    }
}

// ═══════════════════════════════════════════════════════════════
//  Разрешения
// ═══════════════════════════════════════════════════════════════

@Composable
private fun RequestPermissionsOnStart() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.w(TAG_PERM, "Permissions denied: $denied")
        }
    }

    LaunchedEffect(Unit) {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                    PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Root composable
// ═══════════════════════════════════════════════════════════════

@Composable
fun TauGpsApp(
    viewModel: GpsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    TauGpsAppContent(
        uiState = uiState,
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        onClearError = viewModel::clearGeneralError,
        modifier = modifier
    ) { tab ->
        when (tab) {
            0 -> MainScreen(
                uiState = uiState,
                modifier = Modifier.fillMaxSize()
            )
            1 -> LogsScreen(
                viewModel = viewModel,
                uiState = uiState,
                modifier = Modifier.fillMaxSize()
            )
            2 -> SettingsScreen(
                viewModel = viewModel,
                uiState = uiState,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TauGpsAppContent(
    uiState: GpsUiState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Int) -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            0 -> "TAU GPS Монитор"
                            1 -> "Логи и Команды"
                            else -> "Настройки"
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) },
                    icon = { Icon(Icons.Default.GpsFixed, contentDescription = "Монитор") },
                    label = { Text("Монитор") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Логи") },
                    label = { Text("Логи и Команды") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { onTabSelected(2) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Настройки") },
                    label = { Text("Настройки") }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ErrorBanner(
                message = uiState.generalError ?: uiState.mockLocationError,
                onDismiss = onClearError
            )

            Box(modifier = Modifier.fillMaxSize()) {
                content(selectedTab)
            }
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String?,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(visible = message != null) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  Константы краш-хендлера
// ═══════════════════════════════════════════════════════════════

private const val TAG_CRASH = "TAU_CRASH"
private const val TAG_PERM = "TAU_PERM"
private const val CHANNEL_CRASH = "tau_crash_channel"
private const val NOTIF_ID_CRASH = 9999
private const val CRASH_PREFS = "tau_crash_prefs"
private const val KEY_LAST_CRASH_SUMMARY = "last_crash_summary"
private const val KEY_LAST_CRASH_DETAILS = "last_crash_details"
private const val KEY_LAST_CRASH_TIME = "last_crash_time"

// ═══════════════════════════════════════════════════════════════
//  PREVIEWS
// ═══════════════════════════════════════════════════════════════

@Preview(name = "App Scaffold - Light", showBackground = true)
@Preview(name = "App Scaffold - Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TauGpsAppPreview() {
    TAUGPSBridgeTheme {
        TauGpsAppContent(
            uiState = GpsUiState(
                connectionStatus = ConnectionStatus.Connected(
                    deviceName = "ttyUSB0",
                    baudRate = 115200
                )
            ),
            selectedTab = 0,
            onTabSelected = {},
            onClearError = {}
        ) { _ ->
            MainScreen(
                uiState = GpsUiState(
                    connectionStatus = ConnectionStatus.Connected(
                        deviceName = "ttyUSB0",
                        baudRate = 115200
                    )
                ),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Preview(name = "App Scaffold with Error Banner", showBackground = true)
@Composable
fun TauGpsAppErrorPreview() {
    TAUGPSBridgeTheme {
        TauGpsAppContent(
            uiState = GpsUiState(
                generalError = "Ошибка подключения к USB: устройство занято другим процессом"
            ),
            selectedTab = 0,
            onTabSelected = {},
            onClearError = {}
        ) { _ ->
            MainScreen(
                uiState = GpsUiState(),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}