package dev.emwl.host

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

object HostState {
    @Volatile var running = false
    @Volatile var siteCount = 0
    @Volatile var startedAt = 0L
    @Volatile var runtime: HostRuntime? = null
    @Volatile var lastServiceError: String? = null

    fun desiredSites(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SITES, 0)

    const val PREFS = "host"
    const val KEY_SITES = "sites"
}

class HostService : Service() {

    companion object {
        const val ACTION_START = "dev.emwl.host.action.START"
        const val ACTION_STOP = "dev.emwl.host.action.STOP"
        const val EXTRA_SITES = "sites"
        private const val CHANNEL = "host"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, sites: Int) {
            val intent = Intent(context, HostService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SITES, sites)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, HostService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onCreate() {
        super.onCreate()
        UiBridge.attach(this)
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }

        // A sticky restart delivers a null intent; the hosted set must survive it.
        val sites = intent?.getIntExtra(EXTRA_SITES, HostState.siteCount)
            ?: HostState.desiredSites(this)

        try {
            startInForeground(sites)
        } catch (t: Throwable) {
            HostState.lastServiceError = "startForeground failed: ${t.javaClass.simpleName}: ${t.message}"
        }

        restartRuntime(sites)
        return START_STICKY
    }

    private fun restartRuntime(sites: Int) {
        val count = sites.coerceIn(0, Ports.MAX_SITES)
        HostState.runtime?.let { old ->
            HostState.running = false
            runCatching { old.stop() }
        }

        val runtime = HostRuntime(this, count)
        try {
            runtime.start()
            HostState.running = true
        } catch (t: Throwable) {
            HostState.lastServiceError = "runtime failed: ${t.javaClass.simpleName}: ${t.message}"
            HostState.running = false
        }

        HostState.runtime = runtime
        HostState.siteCount = count
        HostState.startedAt = System.currentTimeMillis()
        getSharedPreferences(HostState.PREFS, MODE_PRIVATE).edit().putInt(HostState.KEY_SITES, count).apply()
        refreshNotification()
    }

    private fun shutdown() {
        HostState.runtime?.let { runCatching { it.stop() } }
        HostState.runtime = null
        HostState.running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startInForeground(sites: Int) {
        val notification = buildNotification(sites)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(HostState.siteCount))
        }
    }

    private fun buildNotification(sites: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
        .setContentTitle("Hosting on LAN")
        .setContentText("$sites site(s) · control 127.0.0.1:${HostState.runtime?.controlPort ?: "-"}")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Site hosting", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
