package com.hotspotmirror.sender

import android.app.Service
import android.content.Intent
import android.os.IBinder
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
        startForeground(1, Notifications.foreground(this, "Forwarding to your devices"))
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
            while (true) { ch.receive() } // Viewer only sends pings; any read error ends the session
        } catch (_: Exception) {
        } finally {
            client?.let { clients.remove(it) }
            runCatching { sock.close() }
            pushDeviceList()
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
