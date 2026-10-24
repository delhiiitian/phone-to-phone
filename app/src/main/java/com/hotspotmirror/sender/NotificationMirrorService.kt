package com.hotspotmirror.sender

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hotspotmirror.core.*

/** Mirrors every posted notification (except ours and ongoing/group-summary noise) to the Viewers. */
class NotificationMirrorService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (!Prefs(this).mirrorAllNotifications) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title == null && text == null) return
        SenderService.send(
            Message.Notif(sbn.key, sbn.packageName, appLabel(sbn.packageName), title, text,
                OtpExtractor.extract("$title $text"), sbn.postTime)
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        SenderService.send(
            Message.Notif(sbn.key, sbn.packageName, "", null, null, null, System.currentTimeMillis(), removed = true)
        )
    }

    private fun appLabel(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
