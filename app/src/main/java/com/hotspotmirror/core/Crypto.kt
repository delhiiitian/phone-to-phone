package com.hotspotmirror.core

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pairing-key crypto.
 *
 * pairingKey  = PBKDF2-HMAC-SHA256(code, "hotspot-mirror-v1", 120k iterations, 256 bit)
 * sessionKey  = HMAC-SHA256(pairingKey, "session" || senderNonce || viewerNonce)
 * each frame  = AES-256-GCM(sessionKey, iv = direction(1) || counter(8) || 0x000000)
 *
 * Fresh nonces per connection mean a recorded session can't be replayed, and the
 * per-direction counter means an IV is never reused under one session key.
 */
object Crypto {
    private const val SALT = "hotspot-mirror-v1"
    private const val ITERATIONS = 120_000
    const val NONCE_LEN = 16

    fun derivePairingKey(code: String): ByteArray {
        val spec = PBEKeySpec(Prefs.normalizeCode(code).toCharArray(), SALT.toByteArray(), ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun randomNonce(): ByteArray = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }

    fun deriveSessionKey(pairingKey: ByteArray, senderNonce: ByteArray, viewerNonce: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingKey, "HmacSHA256"))
        mac.update("session".toByteArray())
        mac.update(senderNonce)
        mac.update(viewerNonce)
        return mac.doFinal()
    }

    class FrameCipher(sessionKey: ByteArray, private val sendDir: Byte, private val recvDir: Byte) {
        private val key = SecretKeySpec(sessionKey, "AES")
        private var sendCounter = 0L
        private var recvCounter = 0L

        fun seal(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv(sendDir, sendCounter++)))
            return c.doFinal(plain)
        }

        /** Throws AEADBadTagException when the peer used a different pairing code or tampered. */
        fun open(sealed: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv(recvDir, recvCounter++)))
            return c.doFinal(sealed)
        }

        private fun iv(dir: Byte, counter: Long): ByteArray {
            val iv = ByteArray(12)
            iv[0] = dir
            for (i in 0 until 8) iv[1 + i] = (counter ushr (56 - 8 * i)).toByte()
            return iv
        }
    }

    const val DIR_SENDER_TO_VIEWER: Byte = 1
    const val DIR_VIEWER_TO_SENDER: Byte = 2
}
