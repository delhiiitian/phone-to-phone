package com.hotspotmirror.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.hotspotmirror.sender.SenderService
import com.hotspotmirror.viewer.ViewerService

/** Restart the right service after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (Prefs(ctx).mode) {
            Mode.SENDER -> ContextCompat.startForegroundService(ctx, Intent(ctx, SenderService::class.java))
            Mode.VIEWER -> ContextCompat.startForegroundService(ctx, Intent(ctx, ViewerService::class.java))
            Mode.NONE -> Unit
        }
    }
}
