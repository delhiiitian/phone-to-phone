package com.hotspotmirror.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.hotspotmirror.R

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_SMS = "sms"
    const val CH_CALLS = "calls"
    const val CH_NOTIFS = "notifs"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: Int, imp: Int) =
            nm.createNotificationChannel(NotificationChannel(id, ctx.getString(name), imp))
        ch(CH_SERVICE, R.string.channel_service, NotificationManager.IMPORTANCE_LOW)
        ch(CH_SMS, R.string.channel_sms, NotificationManager.IMPORTANCE_HIGH)
        ch(CH_CALLS, R.string.channel_calls, NotificationManager.IMPORTANCE_HIGH)
        ch(CH_NOTIFS, R.string.channel_notifs, NotificationManager.IMPORTANCE_DEFAULT)
    }

    fun foreground(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_mirror)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}
