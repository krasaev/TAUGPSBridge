package ru.krasaev.taugpsbridge

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.ui.LogsScreen
import ru.krasaev.taugpsbridge.ui.LogsScreenContent
import ru.krasaev.taugpsbridge.ui.MainScreen
import ru.krasaev.taugpsbridge.ui.SettingsScreen
import ru.krasaev.taugpsbridge.ui.SettingsScreenContent
import ru.krasaev.taugpsbridge.ui.theme.TAUGPSBridgeTheme
import ru.krasaev.taugpsbridge.viewmodel.GpsUiState
import ru.krasaev.taugpsbridge.viewmodel.GpsViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: GpsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Global uncaught exception handler to prevent app crashes and log errors cleanly
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("TAU_CRASH", "Uncaught exception caught safely: ${throwable.message}", throwable)
            try {
                runOnUiThread {
                    Toast.makeText(applicationContext, "Ошибка: ${throwable.localizedMessage ?: "Сбой"}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Ignore
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        setContent {
            TAUGPSBridgeTheme {
                // Request Location and Notification Permissions
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    // Permissions handled
                }

                LaunchedEffect(Unit) {
                    val permissionsToRequest = mutableListOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
                        permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    val missing = permissionsToRequest.filter {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) {
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                }

                TauGpsApp(viewModel = viewModel)
            }
        }
    }
}

/**
 * Stateful root composable connecting the ViewModel to the application UI.
 */
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
    ) {
        when (selectedTab) {
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

/**
 * Stateless UI scaffold structure for the entire application.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TauGpsAppContent(
    uiState: GpsUiState,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
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
            // Error Notification Banner
            val errorText = uiState.generalError ?: uiState.mockLocationError
            AnimatedVisibility(visible = errorText != null) {
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
                            text = errorText ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onClearError,
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

            // Screen Content
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

// =========================================================================
// PREVIEWS
// =========================================================================
@Preview(name = "App Scaffold - Light", showBackground = true)
@Preview(name = "App Scaffold - Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun TauGpsAppPreview() {
    TAUGPSBridgeTheme {
        TauGpsAppContent(
            uiState = GpsUiState(
                connectionStatus = ConnectionStatus.Connected("ttyUSB0", 115200),
                generalError = null
            ),
            selectedTab = 0,
            onTabSelected = {},
            onClearError = {}
        ) {
            MainScreen(
                uiState = GpsUiState(
                    connectionStatus = ConnectionStatus.Connected("ttyUSB0", 115200)
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
        ) {
            MainScreen(
                uiState = GpsUiState(),
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
