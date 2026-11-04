package ru.flowseal.tgwsproxy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tg_ws_proxy_settings")

class PreferencesManager(private val context: Context) {

    companion object {
        val KEY_PORT = intPreferencesKey("proxy_port")
        val KEY_SECRET_HEX = stringPreferencesKey("proxy_secret_hex")
        val KEY_WORKER_DOMAINS = stringPreferencesKey("proxy_worker_domains")
    }

    val configFlow: Flow<ProxyConfig> = context.dataStore.data.map { prefs ->
        val rawSecret = prefs[KEY_SECRET_HEX] ?: ProxyConfig.DEFAULT_SECRET_HEX
        // If the user had the old secret that started with "ee" (which triggers Fake-TLS in Telegram), migrate it
        val safeSecret = if (rawSecret.startsWith("ee", ignoreCase = true) || rawSecret.startsWith("dd", ignoreCase = true)) {
            ProxyConfig.DEFAULT_SECRET_HEX
        } else {
            rawSecret
        }
        ProxyConfig(
            port = prefs[KEY_PORT] ?: ProxyConfig.DEFAULT_PORT,
            secretHex = safeSecret,
            customWorkerDomains = prefs[KEY_WORKER_DOMAINS] ?: ""
        )
    }

    suspend fun getConfig(): ProxyConfig = configFlow.first()

    suspend fun saveConfig(config: ProxyConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PORT] = config.port
            prefs[KEY_SECRET_HEX] = config.secretHex
            prefs[KEY_WORKER_DOMAINS] = config.customWorkerDomains
        }
    }
}
