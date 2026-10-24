package com.hotspotmirror.core

import android.content.Context
import android.os.Build
import java.security.SecureRandom

enum class Mode { NONE, SENDER, VIEWER }

/** Small wrapper around SharedPreferences for mode, pairing code and device name. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("hotspot_mirror", Context.MODE_PRIVATE)

    var mode: Mode
        get() = Mode.valueOf(sp.getString("mode", Mode.NONE.name)!!)
        set(value) = sp.edit().putString("mode", value.name).apply()

    /** Shared secret typed into both phones. Sender generates it, Viewer enters it. */
    var pairingCode: String
        get() = sp.getString("pairing_code", "")!!
        set(value) = sp.edit().putString("pairing_code", normalizeCode(value)).apply()

    var deviceName: String
        get() = sp.getString("device_name", null) ?: "${Build.MANUFACTURER} ${Build.MODEL}"
        set(value) = sp.edit().putString("device_name", value).apply()

    var mirrorAllNotifications: Boolean
        get() = sp.getBoolean("mirror_all", true)
        set(value) = sp.edit().putBoolean("mirror_all", value).apply()

    companion object {
        // Crockford base32 without ambiguous characters (no I, L, O, U).
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** 12 chars x 5 bits = 60 bits of entropy, shown as XXXX-XXXX-XXXX. */
        fun generateCode(): String {
            val rnd = SecureRandom()
            val raw = (1..12).map { ALPHABET[rnd.nextInt(ALPHABET.length)] }.joinToString("")
            return raw.chunked(4).joinToString("-")
        }

        fun normalizeCode(code: String): String =
            code.uppercase().filter { it in ALPHABET }.chunked(4).joinToString("-")
    }
}
