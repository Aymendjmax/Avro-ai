package com.newoether.agora.webui

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
import com.newoether.agora.AgoraApplication
import com.newoether.agora.MainActivity
import com.newoether.agora.R
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Keeps the process alive while the WebUI server runs. It is a specialUse foreground service
 * rather than dataSync: Android 15 caps dataSync at 6 hours a day, and the server has no natural
 * end. Start it only while the app is in the foreground (the Settings toggle or app launch).
 */
class WebUiService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: WebUiController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // Promote first: the platform requires startForeground() within seconds of the start.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(getString(R.string.webui_notification_starting)),
            foregroundServiceType(),
        )
        scope.launch {
            val container = (application as AgoraApplication).awaitContainer()
            val webUi = container?.webUi
            if (webUi == null) {
                stopSelf()
                return@launch
            }
            controller = webUi
            launch { webUi.status.collectLatest(::render) }
            webUi.startServer()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's Stop turns the WebUI off, so the next app launch does not
            // silently restart it.
            scope.launch { controller?.setEnabled(false) ?: stopSelf() }
        }
        // Not sticky: after the process dies, the server returns on the next foreground launch.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Release the port before the process may be reused; stop() is bounded by its timeout.
        controller?.let { runBlocking { it.stopServer() } }
        scope.cancel()
        super.onDestroy()
    }

    private fun render(status: WebUiStatus) {
        val text = when (status) {
            WebUiStatus.Stopped, WebUiStatus.Starting -> getString(R.string.webui_notification_starting)
            is WebUiStatus.Running -> controller?.accessUrls(status.port, status.https)?.firstOrNull()
                ?.let { getString(R.string.webui_notification_running, it) }
                ?: getString(R.string.webui_notification_running, "port ${status.port}")
            is WebUiStatus.Failed -> getString(R.string.webui_notification_failed, status.message)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        if (status is WebUiStatus.Failed) {
            DebugLog.w(TAG, "WebUI stopped: ${status.message}")
            stopSelf()
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.webui_notification_title))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN,
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            getString(R.string.webui_notification_stop),
            PendingIntent.getService(
                this,
                REQUEST_STOP,
                Intent(this, WebUiService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.webui_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    companion object {
        private const val TAG = "WebUiService"
        private const val CHANNEL_ID = "agora_webui"
        private const val NOTIFICATION_ID = 4_686
        private const val REQUEST_OPEN = 4_686
        private const val REQUEST_STOP = 4_687
        private const val ACTION_STOP = "com.newoether.agora.webui.STOP"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WebUiService::class.java),
                )
            }.onFailure { DebugLog.w(TAG, "WebUI service could not start", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebUiService::class.java))
        }
    }
}
