package ru.krasaev.taugpsbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import ru.krasaev.taugpsbridge.model.ConnectionStatus
import ru.krasaev.taugpsbridge.model.GpsData

class GpsBridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "gps_bridge_channel"
        const val NOTIFICATION_ID = 1001

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

    inner class LocalBinder : Binder() {
        fun getService(): GpsBridgeService = this@GpsBridgeService
        fun getRepository(): GpsBridgeRepository = repository
    }

    override fun onCreate() {
        super.onCreate()
        repository = GpsBridgeRepository.getInstance(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Трансляция геопозиции активна", GpsData()))
        observeGpsData()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!repository.isMockLocationActive.value) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }
        return START_STICKY
    }

    private fun observeGpsData() {
        serviceScope.launch {
            repository.gpsData.collectLatest { data ->
                if (!repository.isMockLocationActive.value) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collectLatest
                }
                val status = repository.connectionStatus.value
                val statusText = when (status) {
                    is ConnectionStatus.Connected -> {
                        val fixText = if (data.is3DFix) "3D Fix" else data.fixType.displayName
                        "Мок-локация активна • $fixText (Спутников: ${data.satellitesUsed})"
                    }
                    is ConnectionStatus.Connecting -> "Подключение к источнику GNSS..."
                    is ConnectionStatus.Disconnected -> "Ожидание подключения..."
                    is ConnectionStatus.Error -> "Ошибка: ${status.message}"
                }
                updateNotification(statusText, data)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GPS Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows GPS bridge status and mock provider state"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String, data: GpsData): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val subText = if (data.hasFix && data.latitude != null && data.longitude != null) {
            String.format(
                "Lat: %.5f, Lon: %.5f | 3D Fix: %s",
                data.latitude,
                data.longitude,
                if (data.is3DFix) "YES" else "NO"
            )
        } else {
            "Searching for GPS satellites..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAU GPS Bridge")
            .setContentText(statusText)
            .setSubText(subText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(statusText: String, data: GpsData) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(statusText, data))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
