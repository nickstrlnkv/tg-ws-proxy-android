package ru.flowseal.tgwsproxy.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.flowseal.tgwsproxy.core.ProxyStats
import ru.flowseal.tgwsproxy.core.StatsManager
import ru.flowseal.tgwsproxy.data.PreferencesManager
import ru.flowseal.tgwsproxy.data.ProxyConfig
import ru.flowseal.tgwsproxy.service.ProxyForegroundService

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TGWSProxy"
    }

    private val preferencesManager = PreferencesManager(application)

    val isRunning: StateFlow<Boolean> = ProxyForegroundService.isRunningFlow
    val errorMessage: StateFlow<String?> = ProxyForegroundService.errorFlow

    val config: StateFlow<ProxyConfig> = preferencesManager.configFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ProxyConfig()
    )

    val stats: StateFlow<ProxyStats> = StatsManager.statsFlow

    fun toggleService(context: Context) {
        Log.i(TAG, "toggleService called (currently running: ${isRunning.value})")
        if (isRunning.value) {
            stopService(context)
        } else {
            startService(context)
        }
    }

    fun startService(context: Context) {
        Log.i(TAG, "Triggering startForegroundService...")
        val intent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopService(context: Context) {
        Log.i(TAG, "Triggering stopService...")
        val intent = Intent(context, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun saveConfig(newConfig: ProxyConfig) {
        Log.i(TAG, "Saving new config: port=${newConfig.port}, secret=${newConfig.secretHex}")
        viewModelScope.launch {
            preferencesManager.saveConfig(newConfig)
        }
    }

    fun generateNewSecret(): String = ProxyConfig.generateRandomSecret()

    fun getTelegramProxyUri(): String {
        val current = config.value
        val uri = "tg://proxy?server=127.0.0.1&port=${current.port}&secret=${current.secretHex}"
        Log.i(TAG, "Generated Telegram Proxy URI: $uri")
        return uri
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }
}
