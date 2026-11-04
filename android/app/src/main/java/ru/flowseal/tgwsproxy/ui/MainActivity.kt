package ru.flowseal.tgwsproxy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import ru.flowseal.tgwsproxy.ui.theme.TgWsProxyTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Notification permission handled
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkNotificationPermission()

        setContent {
            TgWsProxyTheme {
                AppNavHost(viewModel = viewModel, onToggleService = {
                    viewModel.toggleService(this)
                })
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(permission)
            }
        }
    }
}

@Composable
fun AppNavHost(
    viewModel: MainViewModel,
    onToggleService: () -> Unit
) {
    var inSettings by remember { mutableStateOf(false) }

    val isRunning by viewModel.isRunning.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val config by viewModel.config.collectAsState()
    val stats by viewModel.stats.collectAsState()

    if (inSettings) {
        SettingsScreen(
            currentConfig = config,
            onSave = { newConfig -> viewModel.saveConfig(newConfig) },
            onBack = { inSettings = false },
            onGenerateSecret = { viewModel.generateNewSecret() }
        )
    } else {
        MainScreen(
            isRunning = isRunning,
            errorMessage = errorMessage,
            config = config,
            stats = stats,
            onToggleService = onToggleService,
            onOpenSettings = { inSettings = true },
            formatBytes = { bytes -> viewModel.formatBytes(bytes) },
            proxyUri = viewModel.getTelegramProxyUri()
        )
    }
}
