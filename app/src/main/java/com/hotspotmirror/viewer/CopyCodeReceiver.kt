package com.hotspotmirror.viewer

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Handles the "Copy code" action on OTP notifications. */
class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        ctx.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("OTP", code))
        Toast.makeText(ctx, "Copied $code", Toast.LENGTH_SHORT).show()
    }

    companion object { const val EXTRA_CODE = "code" }
}
