package com.example.cftunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

enum class ServiceState {
    STOPPED, RUNNING, RECONNECTING
}

class TunnelService : Service() {

    companion object {
        const val ACTION_STOP_SERVICE = "com.example.cftunnel.ACTION_STOP"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "cftunnel_channel"

        private val _serviceState = MutableStateFlow(ServiceState.STOPPED)
        val serviceState = _serviceState.asStateFlow()

        var logManager: LogManager? = null
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var process: Process? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var isIntentionalStop = AtomicBoolean(false)

    private lateinit var appPrefs: AppPreferences

    override fun onCreate() {
        super.onCreate()
        appPrefs = AppPreferences(this)
        if (logManager == null) {
            logManager = LogManager(this)
        }

        createNotificationChannel()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }

        val token = appPrefs.tunnelToken
        if (token.isBlank()) {
            logManager?.appendLog("Error: Tunnel token is empty. Cannot start.")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceWithNotification(ServiceState.RUNNING)
        _serviceState.value = ServiceState.RUNNING
        isIntentionalStop.set(false)

        startCloudflared(token)

        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CFTunnel::WakeLock").apply {
                acquire()
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL,
                "CFTunnel::WifiLock"
            ).apply {
                acquire()
            }
            logManager?.appendLog("Wake locks acquired.")
        } catch (e: Exception) {
            logManager?.appendLog("Warning: Failed to acquire locks - ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            logManager?.appendLog("Wake locks released.")
        } catch (e: Exception) {
            logManager?.appendLog("Warning: Failed to release locks - ${e.message}")
        }
    }

    private fun startCloudflared(token: String) {
        serviceScope.launch {
            while (isActive && !isIntentionalStop.get()) {
                try {
                    val nativeLibDir = applicationInfo.nativeLibraryDir
                    val executablePath = "$nativeLibDir/libcloudflared.so"

                    val file = File(executablePath)
                    if (!file.exists()) {
                        logManager?.appendLog("Error: Executable not found at $executablePath")
                        _serviceState.value = ServiceState.STOPPED
                        stopSelf()
                        return@launch
                    }

                    if (!file.canExecute()) {
                        logManager?.appendLog("Warning: Executable lacks execute permission. Attempting to fix...")
                        file.setExecutable(true)
                    }

                    logManager?.appendLog("Starting cloudflared process...")
                    updateNotification(ServiceState.RUNNING)
                    _serviceState.value = ServiceState.RUNNING

                    val processBuilder = ProcessBuilder(
                        executablePath,
                        "tunnel",
                        "run",
                        "--token",
                        token
                    )
                    processBuilder.redirectErrorStream(true)

                    val activeProcess = processBuilder.start()
                    process = activeProcess

                    val reader = BufferedReader(InputStreamReader(activeProcess.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        line?.let { logManager?.appendLog(it) }
                    }

                    val exitCode = activeProcess.waitFor()
                    logManager?.appendLog("Process exited with code $exitCode")

                } catch (e: Exception) {
                    logManager?.appendLog("Error running cloudflared: ${e.message}")
                }

                if (!isIntentionalStop.get()) {
                    _serviceState.value = ServiceState.RECONNECTING
                    updateNotification(ServiceState.RECONNECTING)
                    logManager?.appendLog("Restarting in 3 seconds...")
                    delay(3000)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cloudflare Tunnel Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Cloudflare tunnel running in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(state: ServiceState): Notification {
        val stopIntent = Intent(this, TunnelService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentText = when (state) {
            ServiceState.RUNNING -> "Tunnel is running"
            ServiceState.RECONNECTING -> "Tunnel is reconnecting..."
            ServiceState.STOPPED -> "Tunnel is stopped"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CF-Tunnel")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use standard icon
            .setContentIntent(mainPendingIntent)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundServiceWithNotification(state: ServiceState) {
        val notification = createNotification(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14+ requires foreground service type
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(state: ServiceState) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(state))
    }

    override fun onDestroy() {
        isIntentionalStop.set(true)
        serviceScope.cancel()
        process?.destroy()
        releaseLocks()
        _serviceState.value = ServiceState.STOPPED
        logManager?.appendLog("Service stopped.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
