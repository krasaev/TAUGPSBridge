package ru.krasaev.taugpsbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.krasaev.taugpsbridge.MainActivity
import ru.krasaev.taugpsbridge.R

class GpsBridgeService : Service() {

    companion object {
        private const val CHANNEL_ID = "gps_bridge_channel"
        private const val CHANNEL_NAME = "GPS Bridge"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "ru.krasaev.taugpsbridge.START_SERVICE"
        const val ACTION_STOP = "ru.krasaev.taugpsbridge.STOP_SERVICE"

        fun startService(context: Context) {
            val intent = Intent(context, GpsBridgeService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, GpsBridgeService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var repository: GpsBridgeRepository

    @Volatile
    private var isForeground = false

    inner class LocalBinder : Binder() {
        fun getService(): GpsBridgeService = this@GpsBridgeService
        fun getRepository(): GpsBridgeRepository = repository
    }

    override fun onCreate() {
        super.onCreate()
        repository = GpsBridgeRepository.getInstance(applicationContext)
        createNotificationChannel()
        startForegroundSafely(buildNotification())
        observeMockLocationState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundSafely()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Ничего — сервис уже запущен, ждём observeMockLocationState()
            }
        }
        return START_STICKY
    }

    /**
     * Единственный наблюдатель — следим только за тем, чтобы мок-локация была активна.
     * Как только пользователь её выключит — сервис останавливается.
     */
    private fun observeMockLocationState() {
        serviceScope.launch {
            repository.isMockLocationActive.collectLatest { active ->
                if (!active && isForeground) {
                    stopForegroundSafely()
                    stopSelf()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  Foreground helpers
    // ═══════════════════════════════════════════════════════════

    private fun startForegroundSafely(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (_: Exception) {
            isForeground = false
        }
    }

    private fun stopForegroundSafely() {
        if (!isForeground) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        isForeground = false
    }

    // ═══════════════════════════════════════════════════════════
    //  Notification — создаётся один раз, без обновлений
    // ═══════════════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_MIN  // без звука, без вибрации, без всплытия
            ).apply {
                description = "Уведомление о работе GPS-моста"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAU GPS Bridge")
            .setContentText("Трансляция активна")
            .setSmallIcon(R.drawable.ic_stat_gps)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    // ═══════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}