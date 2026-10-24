package com.hotspotmirror.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.hotspotmirror.core.Message
import com.hotspotmirror.core.OtpExtractor

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val from = parts.firstOrNull()?.originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        SenderService.send(
            Message.Sms(from, Contacts.nameFor(ctx, from), body, OtpExtractor.extract(body), System.currentTimeMillis())
        )
    }
}
