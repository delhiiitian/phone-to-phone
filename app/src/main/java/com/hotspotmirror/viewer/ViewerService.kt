package com.hotspotmirror.viewer

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.hotspotmirror.R
import com.hotspotmirror.core.*
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Viewer mode. Browses for the Sender via mDNS, connects with the pairing key, auto-reconnects,
 * and turns every incoming Message into a local notification (SMS/OTP get a "Copy code" action).
 */
class ViewerService : Service() {
    private lateinit var nsd: Nsd
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = true
    @Volatile private var target: Triple<String, Int, String>? = null
    private var nextId = 100
    private val relay = AudioRelay()
    @Volatile private var channel: SecureChannel? = null

    override fun onCreate() {
        super.onCreate()
        // Explicit type: see SenderService. The Viewer has not been granted RECORD_AUDIO at this point.
        ServiceCompat.startForeground(this, 2, Notifications.foreground(this, "Looking for hotspot phone…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hotspotmirror:viewer").apply { acquire() }
        nsd = Nsd(this)
        nsd.discover { host, port, name -> target = Triple(host, port, name) }
        thread(name = "viewer-connect") { connectLoop() }
    }

    private fun connectLoop() {
        val prefs = Prefs(this)
        val key = Crypto.derivePairingKey(prefs.pairingCode)
        while (running) {
            val t = target
            if (t == null) { Thread.sleep(1000); continue }
            try {
                val sock = Socket().apply { connect(InetSocketAddress(t.first, t.second), 5000); keepAlive = true }
                val ch = SecureChannel.asViewer(sock, key)
                ch.send(Message.Hello(prefs.deviceName, Mode.VIEWER))
                channel = ch
                ViewerState.connected = true; ViewerState.senderName = t.third; ViewerState.changed()
                updateStatus("Connected to ${t.third}")
                while (running) handle(ch.receive())
            } catch (_: Exception) {
            } finally {
                channel = null; relay.stop()
                ViewerState.connected = false; ViewerState.devices = emptyList(); ViewerState.changed()
                updateStatus("Reconnecting…")
            }
            Thread.sleep(2000)
        }
    }

    private fun handle(m: Message) {
        when (m) {
            is Message.Audio -> relay.play(m.pcm)
            is Message.Devices -> { ViewerState.devices = m.list; ViewerState.changed() }
            is Message.Sms -> { ViewerState.add(m); show(Notifications.CH_SMS, m.contactName ?: m.from, m.body, m.otp) }
            is Message.Call -> {
                ViewerState.add(m)
                if (m.state == "ringing") show(Notifications.CH_CALLS, "Incoming call", m.contactName ?: m.number ?: "Unknown", null)
            }
            is Message.Notif -> if (!m.removed) {
                ViewerState.add(m)
                show(Notifications.CH_NOTIFS, "${m.appName}: ${m.title.orEmpty()}", m.text.orEmpty(), m.otp)
            }
            else -> Unit
        }
    }

    private fun show(channel: String, title: String, text: String, otp: String?) {
        val b = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_mirror).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
        if (otp != null) {
            val pi = PendingIntent.getBroadcast(this, nextId,
                Intent(this, CopyCodeReceiver::class.java).putExtra(CopyCodeReceiver.EXTRA_CODE, otp),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.addAction(0, getString(R.string.copy_code) + " $otp", pi)
        }
        getSystemService(NotificationManager::class.java).notify(nextId++, b.build())
    }

    private fun updateStatus(text: String) =
        getSystemService(NotificationManager::class.java).notify(2, Notifications.foreground(this, text))

    /** Called from the UI. "audio_start" also begins capturing this phone's mic (RECORD_AUDIO must be granted). */
    private fun control(action: String) {
        val ch = channel ?: return
        thread { runCatching { ch.send(Message.Control(action)) } }
        when (action) {
            "audio_start" -> if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0) {
                ServiceCompat.startForeground(this, 2, Notifications.foreground(this, "Call audio on"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                relay.startCapture { f -> runCatching { ch.send(Message.Audio(f)) } }
            }
            "audio_stop" -> relay.stop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_ACTION)?.let { control(it) }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        nsd.stopDiscovery()
        wakeLock?.release()
        super.onDestroy()
    }

    companion object { const val EXTRA_ACTION = "action" }
}
