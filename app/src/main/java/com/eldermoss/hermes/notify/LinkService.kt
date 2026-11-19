package com.eldermoss.hermes.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.eldermoss.hermes.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service holding the gateway WebSocket while the app is
 * backgrounded: instant cron/agent notifications over the tailnet, no FCM.
 * Tradeoff documented in the README; the WorkManager poller covers the off case.
 */
class LinkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, Channels.link(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, Channels.link(this))
        }
        val connections = ServiceLocator.connections
        connections.socket.start()

        scope.launch {
            connections.socket.events.collect { ev ->
                if (ev.type == "message.complete" && ev.str("text").isNotBlank()) {
                    Notifier.agentFinished(this@LinkService, ev.sessionId, ev.str("text"))
                }
            }
        }
        pollJob = scope.launch {
            while (isActive) {
                delay(60_000)
                runCatching { Notifier.diffCron(this@LinkService) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 1
        fun start(context: Context) {
            context.startForegroundService(Intent(context, LinkService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, LinkService::class.java))
        }
    }
}
