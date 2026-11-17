package com.hotspotmirror.sender

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
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
        startForegroundSafely()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hotspotmirror:sender").apply { acquire() }
        nsd = Nsd(this)
        startServer()
    }

    /**
     * Android 14 only allows a microphone-type foreground service to start while the app is visible,
     * and keeps the mic available for the service's lifetime once it has. So when the user starts
     * forwarding from the screen we include the microphone type, and fall back to plain
     * connectedDevice when started from boot or without the permission.
     */
    private fun startForegroundSafely() {
        val n = Notifications.foreground(this, "Forwarding to your devices")
        val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (micGranted) {
            try {
                ServiceCompat.startForeground(this, 1, n, base or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                return
            } catch (_: Exception) { /* not allowed from the background: fall through */ }
        }
        ServiceCompat.startForeground(this, 1, n, base)
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

    // Capture with MIC, not VOICE_COMMUNICATION: that source runs echo cancellation, which removes
    // exactly the speaker audio (the caller's voice) we want to hear.
    private val relay = AudioRelay(MediaRecorder.AudioSource.MIC, AudioAttributes.USAGE_MEDIA)
    private val micGranted get() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0
    private var savedVolumes: Pair<Int, Int>? = null

    /** Speaker on and loud, so the phone's own mic can hear the caller and the caller can hear the Viewer's voice. */
    private fun startRelay() {
        if (!micGranted) { broadcast(Message.Control("mic_denied")); return }
        val am = getSystemService(AudioManager::class.java)
        if (savedVolumes == null) {
            savedVolumes = am.getStreamVolume(AudioManager.STREAM_MUSIC) to am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        }
        @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = true }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0) }
        var frames = 0
        var peak = 0
        try {
            relay.startCapture { f ->
                if (frames < 100) { // first 2 s: tell the Viewer if the OS is feeding us silence
                    for (i in 0 until f.size - 1 step 2) peak = maxOf(peak, kotlin.math.abs((f[i].toInt() and 0xFF) or (f[i + 1].toInt() shl 8)))
                    if (++frames == 100 && peak < 4) broadcast(Message.Control("mic_silent"))
                }
                broadcast(Message.Audio(f))
            }
        } catch (e: Exception) { broadcast(Message.Control("mic_denied")) }
    }

    private fun stopRelay() {
        relay.stop()
        val am = getSystemService(AudioManager::class.java)
        @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        savedVolumes?.let { (music, call) ->
            am.setStreamVolume(AudioManager.STREAM_MUSIC, music, 0)
            runCatching { am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, call, 0) }
        }
        savedVolumes = null
    }

    /** Call state from CallReceiver: end the relay when the call ends. */
    fun onCallState(state: String) { if (state == "idle") stopRelay() }

    /** Call control and audio from the Viewer. */
    private fun onViewerMessage(m: Message) {
        val telecom = getSystemService(android.telecom.TelecomManager::class.java)
        when (m) {
            is Message.Audio -> relay.play(m.pcm)
            is Message.Control -> when (m.action) {
                "audio_start" -> startRelay()
                "audio_stop" -> stopRelay()
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
        stopRelay()
        nsd.unregister()
        runCatching { server?.close() }
        clients.forEach { it.channel.close() }
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: SenderService? = null
        fun send(msg: Message) {
            instance?.broadcast(msg)
            if (msg is Message.Call) instance?.onCallState(msg.state)
        }
    }
}
