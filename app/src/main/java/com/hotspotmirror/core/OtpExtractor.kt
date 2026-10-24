package com.hotspotmirror.core

/** Finds a one-time code in an SMS or notification body. Returns null when nothing looks like an OTP. */
object OtpExtractor {
    private val keyword = Regex(
        "(otp|one[- ]time|code|passcode|password|pin|verification|verify|token|2fa|login)",
        RegexOption.IGNORE_CASE,
    )
    // 4-8 digits, optionally split as 123-456 or 123 456. Not part of a longer number.
    private val candidate = Regex("(?<![\\d₹$.,])(\\d{3,4}[- ]?\\d{3,4}|\\d{4,8})(?![\\d.,])")

    fun extract(text: String?): String? {
        if (text.isNullOrBlank() || !keyword.containsMatchIn(text)) return null
        val matches = candidate.findAll(text).map { it.value.replace(Regex("[- ]"), "") }
            .filter { it.length in 4..8 }.toList()
        if (matches.isEmpty()) return null
        // Prefer the candidate closest to the keyword.
        val kwIndex = keyword.find(text)!!.range.first
        return candidate.findAll(text)
            .filter { it.value.replace(Regex("[- ]"), "").length in 4..8 }
            .minByOrNull { kotlin.math.abs(it.range.first - kwIndex) }
            ?.value?.replace(Regex("[- ]"), "")
    }
}
