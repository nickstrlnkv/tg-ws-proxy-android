package ru.flowseal.tgwsproxy.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.flowseal.tgwsproxy.core.MtprotoServer
import ru.flowseal.tgwsproxy.core.StatsManager
import ru.flowseal.tgwsproxy.data.PreferencesManager
import ru.flowseal.tgwsproxy.data.ProxyConfig

class ProxyForegroundService : Service() {

    companion object {
        private const val TAG = "TGWSProxy"

        const val ACTION_START = "ru.flowseal.tgwsproxy.ACTION_START"
        const val ACTION_STOP = "ru.flowseal.tgwsproxy.ACTION_STOP"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        private val _errorFlow = MutableStateFlow<String?>(null)
        val errorFlow: StateFlow<String?> = _errorFlow.asStateFlow()
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private var mtprotoServer: MtprotoServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityObserver: ConnectivityObserver? = null
    private var currentPort = ProxyConfig.DEFAULT_PORT

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ProxyForegroundService onCreate")
        NotificationHelper.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "ProxyForegroundService onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Received ACTION_STOP")
                stopProxy()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.i(TAG, "Received ACTION_START")
                startProxy()
                return START_STICKY
            }
        }
    }

    private fun startProxy() {
        if (_isRunningFlow.value) {
            Log.i(TAG, "Proxy service is already running")
            return
        }

        val notification = NotificationHelper.buildNotification(this, currentPort, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NotificationHelper.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID, notification)
        }

        acquireWakeLock()

        serviceScope.launch {
            try {
                val prefs = PreferencesManager(applicationContext)
                val config = prefs.getConfig()
                currentPort = config.port
                Log.i(TAG, "Loaded config: port=$currentPort, secret=${config.secretHex}, workers=${config.customWorkerDomains}")

                val server = MtprotoServer(
                    port = config.port,
                    secret = config.getSecretBytes(),
                    customWorkerDomains = config.getWorkerDomainsList()
                )
                mtprotoServer = server
                server.start(serviceScope)

                _isRunningFlow.value = true
                _errorFlow.value = null
                Log.i(TAG, "Foreground proxy service is now ACTIVE on 127.0.0.1:$currentPort")

                // Monitor network changes
                connectivityObserver = ConnectivityObserver(applicationContext) {
                    Log.i(TAG, "Network route changed (Wi-Fi <-> LTE)")
                }.apply { start() }

                // Collect stats and update notification
                StatsManager.statsFlow.collectLatest { stats ->
                    val updatedNotification = NotificationHelper.buildNotification(
                        applicationContext,
                        currentPort,
                        stats.activeConnections
                    )
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NotificationHelper.NOTIFICATION_ID, updatedNotification)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy service: ${e.message}", e)
                _errorFlow.value = e.localizedMessage ?: "Failed to start proxy"
                stopProxy()
                stopSelf()
            }
        }
    }

    private fun stopProxy() {
        Log.i(TAG, "Stopping ProxyForegroundService...")
        _isRunningFlow.value = false
        connectivityObserver?.close()
        connectivityObserver = null

        mtprotoServer?.close()
        mtprotoServer = null

        releaseWakeLock()
        serviceJob.cancel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.i(TAG, "ProxyForegroundService stopped completely")
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tgwsproxy:wakelock").apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L /* 10 minutes timeout */)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    override fun onDestroy() {
        Log.i(TAG, "ProxyForegroundService onDestroy")
        stopProxy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
