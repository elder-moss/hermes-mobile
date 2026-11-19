package com.eldermoss.hermes.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.eldermoss.hermes.MainActivity
import com.eldermoss.hermes.R

object Channels {
    const val LINK = "link"
    const val CRON = "cron"
    const val AGENT = "agent"

    fun ensure(context: Context): NotificationManager {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(LINK, "Connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Persistent background link indicator"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CRON, "Schedules", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Cron job completions and failures"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(AGENT, "Agent activity", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Finished long-running agent tasks"
            },
        )
        return nm
    }

    fun contentIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun link(context: Context): Notification =
        NotificationCompat.Builder(context, LINK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Hermes link active")
            .setContentText("Listening for schedule and agent updates")
            .setOngoing(true)
            .setContentIntent(contentIntent(context))
            .build()

    fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        ensure(context)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1200)))
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context))
            .build()
        runCatching { getSystemService(context, id, n) }
    }

    private fun getSystemService(context: Context, id: Int, n: Notification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, n)
    }
}
