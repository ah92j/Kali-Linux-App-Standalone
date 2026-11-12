package com.orailnoor.droiddesk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.orailnoor.droiddesk.MainActivity
import com.orailnoor.droiddesk.runtime.AndroidAppBridge
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.view.DesktopActivity
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread

/**
 * Foreground service that keeps the Linux runtime alive.
 *
 * Android aggressively kills background processes (especially Android 12+'s
 * Phantom Process Killer). This service ensures our native Termux/chroot session, desktop
 * environment, and Wayland compositor survive when the user switches apps.
 */
class DroidDeskService : Service() {

    companion object {
        const val CHANNEL_ID = "droiddesk_service"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "DroidDeskService"
        private const val ACTION_STOP = "com.orailnoor.droiddesk.action.STOP_DESKTOP"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        AndroidAppBridge.start(this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDesktop()
            return START_NOT_STICKY
        }
        val notification = buildNotification("Linux desktop is running")

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AndroidAppBridge.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    /** Same teardown as the Flutter "stopLinux" call, triggered from the notification. */
    private fun stopDesktop() {
        if (stopping) return
        stopping = true
        DesktopActivity.finishIfOpen()
        thread(name = "notification-stop-linux") {
            try {
                val chrootRuntime = ChrootRuntime(this)
                if (chrootRuntime.isRunning()) chrootRuntime.stopSession()
                LinuxRuntime(this).stopSession()
                stopService(Intent(this, X11ServerService::class.java))
            } catch (error: Exception) {
                Log.e(TAG, "Failed to stop the Linux desktop from the notification", error)
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DroidDesk Linux Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Linux desktop environment running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        // Brings the existing task forward (showing the desktop if it is open); otherwise
        // MainActivity reopens the running session without restarting it.
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DESKTOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DroidDeskService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidDesk")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(0, "Open", openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    // ── Wake Lock ──

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DroidDesk::LinuxRuntime"
        ).apply {
            acquire(Long.MAX_VALUE)  // Keep CPU alive
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }
}
