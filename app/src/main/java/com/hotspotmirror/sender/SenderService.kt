package com.hotspotmirror.sender

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import android.os.PowerManager
import com.hotspotmirror.core.*
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Sender mode. Runs on the hotspot phone: advertises over mDNS, accepts Viewer connections,
 * and fans out every Message that SmsReceiver / CallReceiver / NotificationMirrorService hands to [broadcast].
 */
class SenderService : Service() {
    private class Client(val channel: SecureChannel, var name: String, val since: Long)

    private val clients = CopyOnWriteArrayList<Client>()
    private var server: ServerSocket? = null
    private lateinit var nsd: Nsd
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Explicit type: with no type Android 14 uses every type in the manifest, and the microphone
        // type throws SecurityException when RECORD_AUDIO has not been granted yet.
        ServiceCompat.startForeground(this, 1, Notifications.foreground(this, "Forwarding to your devices"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hotspotmirror:sender").apply { acquire() }
        nsd = Nsd(this)
        startServer()
    }

    private fun startServer() = thread(name = "sender-accept") {
        val prefs = Prefs(this)
        val pairingKey = Crypto.derivePairingKey(prefs.pairingCode)
        val ss = ServerSocket(Nsd.DEFAULT_PORT).also { server = it }
        nsd.register(prefs.deviceName, ss.localPort)
        while (!ss.isClosed) {
            val sock = runCatching { ss.accept() }.getOrNull() ?: break
            thread(name = "sender-client") { handle(sock, pairingKey) }
        }
    }

    private fun handle(sock: java.net.Socket, pairingKey: ByteArray) {
        var client: Client? = null
        try {
            val ch = SecureChannel.asSender(sock, pairingKey)
            val hello = ch.receive() as? Message.Hello ?: return // wrong key throws before this
            client = Client(ch, hello.deviceName, System.currentTimeMillis()).also { clients.add(it) }
            pushDeviceList()
            while (true) onViewerMessage(ch.receive()) // any read error ends the session
        } catch (_: Exception) {
        } finally {
            client?.let { clients.remove(it) }
            runCatching { sock.close() }
            pushDeviceList()
        }
    }

    private val relay = AudioRelay()
    private val micGranted get() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0

    /** Call control and audio from the Viewer. Audio needs RECORD_AUDIO on this phone; speaker is forced on so the mic hears the call. */
    private fun onViewerMessage(m: Message) {
        val am = getSystemService(android.media.AudioManager::class.java)
        val telecom = getSystemService(android.telecom.TelecomManager::class.java)
        when (m) {
            is Message.Audio -> relay.play(m.pcm)
            is Message.Control -> when (m.action) {
                "audio_start" -> if (micGranted) {
                    ServiceCompat.startForeground(this, 1, Notifications.foreground(this, "Relaying call audio"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                    am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                    @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = true }
                    relay.startCapture { broadcast(Message.Audio(it)) }
                }
                "audio_stop" -> { relay.stop(); @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false } }
                "answer" -> if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == 0)
                    @Suppress("MissingPermission") telecom.acceptRingingCall()
                "hangup" -> if (Build.VERSION.SDK_INT >= 28 && checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == 0)
                    @Suppress("MissingPermission") telecom.endCall()
            }
            else -> Unit
        }
    }

    private fun pushDeviceList() {
        val list = clients.map { Message.Device(it.name, it.channel.remoteAddress, Mode.VIEWER, it.since) }
        broadcast(Message.Devices(list))
    }

    fun broadcast(msg: Message) {
        for (c in clients) thread { runCatching { c.channel.send(msg) }.onFailure { c.channel.close() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        relay.stop()
        nsd.unregister()
        runCatching { server?.close() }
        clients.forEach { it.channel.close() }
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: SenderService? = null
        fun send(msg: Message) { instance?.broadcast(msg) }
    }
}
