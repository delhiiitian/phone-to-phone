package com.hotspotmirror.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.hotspotmirror.core.Message

/**
 * Call alerts. On Android 9+ the EXTRA_INCOMING_NUMBER is only delivered with READ_CALL_LOG
 * (and READ_PHONE_STATE); without it the number is null and the Viewer shows "Unknown".
 */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> "ringing"
            TelephonyManager.EXTRA_STATE_OFFHOOK -> "offhook"
            TelephonyManager.EXTRA_STATE_IDLE -> "idle"
            else -> return
        }
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (state == lastState && number == null) return
        lastState = state
        SenderService.send(Message.Call(state, number, Contacts.nameFor(ctx, number), System.currentTimeMillis()))
    }

    companion object { private var lastState = "" }
}
