package com.hotspotmirror.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/**
 * Length-prefixed, AES-GCM encrypted frames over a TCP socket.
 *
 * Handshake (plaintext): sender sends its 16-byte nonce, viewer replies with its nonce.
 * Both derive the session key, then the viewer sends an encrypted HELLO. If the codes
 * differ, decryption of the first frame fails and the connection is dropped.
 */
class SecureChannel private constructor(
    private val socket: Socket,
    private val cipher: Crypto.FrameCipher,
) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = DataOutputStream(socket.getOutputStream().buffered())
    private val writeLock = Any()

    val remoteAddress: String get() = socket.inetAddress?.hostAddress ?: "?"

    fun send(msg: Message) {
        val sealed = cipher.seal(msg.toJson().toByteArray(Charsets.UTF_8))
        synchronized(writeLock) {
            output.writeInt(sealed.size)
            output.write(sealed)
            output.flush()
        }
    }

    /** Blocks until a frame arrives. Throws on EOF, bad key or tampering. */
    fun receive(): Message {
        val len = input.readInt()
        if (len <= 0 || len > MAX_FRAME) throw IOException("bad frame length $len")
        val buf = ByteArray(len)
        input.readFully(buf)
        return Message.fromJson(String(cipher.open(buf), Charsets.UTF_8))
    }

    fun close() = runCatching { socket.close() }

    companion object {
        private const val MAX_FRAME = 256 * 1024

        fun asSender(socket: Socket, pairingKey: ByteArray): SecureChannel {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val out = DataOutputStream(socket.getOutputStream())
            val inp = DataInputStream(socket.getInputStream())
            val myNonce = Crypto.randomNonce()
            out.write(myNonce); out.flush()
            val theirNonce = ByteArray(Crypto.NONCE_LEN).also { inp.readFully(it) }
            val key = Crypto.deriveSessionKey(pairingKey, myNonce, theirNonce)
            socket.soTimeout = 0
            return SecureChannel(socket, Crypto.FrameCipher(key, Crypto.DIR_SENDER_TO_VIEWER, Crypto.DIR_VIEWER_TO_SENDER))
        }

        fun asViewer(socket: Socket, pairingKey: ByteArray): SecureChannel {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            val out = DataOutputStream(socket.getOutputStream())
            val inp = DataInputStream(socket.getInputStream())
            val theirNonce = ByteArray(Crypto.NONCE_LEN).also { inp.readFully(it) }
            val myNonce = Crypto.randomNonce()
            out.write(myNonce); out.flush()
            val key = Crypto.deriveSessionKey(pairingKey, theirNonce, myNonce)
            socket.soTimeout = 0
            return SecureChannel(socket, Crypto.FrameCipher(key, Crypto.DIR_VIEWER_TO_SENDER, Crypto.DIR_SENDER_TO_VIEWER))
        }

        private const val HANDSHAKE_TIMEOUT_MS = 10_000
    }
}
