package com.hotspotmirror.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun otpPicksCodeNearKeyword() {
        assertEquals("482913", OtpExtractor.extract("Your OTP is 482913. Valid 10 min. Rs 1500 debited"))
        assertEquals("123456", OtpExtractor.extract("123-456 is your verification code"))
        assertNull(OtpExtractor.extract("Meeting at 5, call 98765"))
    }

    @Test fun messageRoundTrip() {
        val sms = Message.Sms("AX-BANK", null, "code 1234", "1234", 5L)
        assertEquals(sms, Message.fromJson(sms.toJson()))
        val audio = Message.fromJson(Message.Audio(byteArrayOf(1, 2, 3)).toJson()) as Message.Audio
        assertArrayEquals(byteArrayOf(1, 2, 3), audio.pcm)
        assertEquals(Message.Control("answer"), Message.fromJson(Message.Control("answer").toJson()))
    }

    @Test fun framesAuthenticateOnlyWithSameCode() {
        val nS = Crypto.randomNonce(); val nV = Crypto.randomNonce()
        fun session(code: String) = Crypto.deriveSessionKey(Crypto.derivePairingKey(code), nS, nV)
        val sender = Crypto.FrameCipher(session("AAAA-BBBB-CCCC"), Crypto.DIR_SENDER_TO_VIEWER, Crypto.DIR_VIEWER_TO_SENDER)
        val good = Crypto.FrameCipher(session("AAAA-BBBB-CCCC"), Crypto.DIR_VIEWER_TO_SENDER, Crypto.DIR_SENDER_TO_VIEWER)
        val bad = Crypto.FrameCipher(session("DDDD-EEEE-FFFF"), Crypto.DIR_VIEWER_TO_SENDER, Crypto.DIR_SENDER_TO_VIEWER)
        val frame = sender.seal("hi".toByteArray())
        assertEquals("hi", String(good.open(frame)))
        assertThrows(Exception::class.java) { bad.open(frame) }
    }

    @Test fun codeFormat() {
        val c = Prefs.generateCode()
        assertEquals(c, Prefs.normalizeCode(c.lowercase()))
        assertEquals(14, c.length)
    }
}
