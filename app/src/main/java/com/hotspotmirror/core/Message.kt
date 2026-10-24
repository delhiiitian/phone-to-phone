package com.hotspotmirror.core

import org.json.JSONArray
import org.json.JSONObject

/** Everything that travels over the wire. Encoded as one JSON object per encrypted frame. */
sealed class Message {
    abstract val type: String

    data class Hello(val deviceName: String, val role: Mode) : Message() { override val type = "hello" }

    data class Sms(val from: String, val contactName: String?, val body: String, val otp: String?, val ts: Long) :
        Message() { override val type = "sms" }

    /** state: "ringing", "offhook" (answered / outgoing) or "idle" (ended or missed). */
    data class Call(val state: String, val number: String?, val contactName: String?, val ts: Long) :
        Message() { override val type = "call" }

    data class Notif(
        val key: String, val pkg: String, val appName: String,
        val title: String?, val text: String?, val otp: String?, val ts: Long, val removed: Boolean = false,
    ) : Message() { override val type = "notif" }

    data class Device(val name: String, val address: String, val role: Mode, val connectedSince: Long)

    data class Devices(val list: List<Device>) : Message() { override val type = "devices" }

    data object Ping : Message() { override val type = "ping" }

    fun toJson(): String {
        val o = JSONObject().put("t", type)
        when (this) {
            is Hello -> o.put("name", deviceName).put("role", role.name)
            is Sms -> o.put("from", from).putOpt("contact", contactName).put("body", body).putOpt("otp", otp).put("ts", ts)
            is Call -> o.put("state", state).putOpt("number", number).putOpt("contact", contactName).put("ts", ts)
            is Notif -> o.put("key", key).put("pkg", pkg).put("app", appName).putOpt("title", title)
                .putOpt("text", text).putOpt("otp", otp).put("ts", ts).put("removed", removed)
            is Devices -> o.put("list", JSONArray().apply {
                list.forEach {
                    put(JSONObject().put("name", it.name).put("addr", it.address)
                        .put("role", it.role.name).put("since", it.connectedSince))
                }
            })
            Ping -> Unit
        }
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): Message {
            val o = JSONObject(s)
            return when (val t = o.getString("t")) {
                "hello" -> Hello(o.getString("name"), Mode.valueOf(o.getString("role")))
                "sms" -> Sms(o.getString("from"), o.optStringOrNull("contact"), o.getString("body"),
                    o.optStringOrNull("otp"), o.getLong("ts"))
                "call" -> Call(o.getString("state"), o.optStringOrNull("number"), o.optStringOrNull("contact"), o.getLong("ts"))
                "notif" -> Notif(o.getString("key"), o.getString("pkg"), o.getString("app"),
                    o.optStringOrNull("title"), o.optStringOrNull("text"), o.optStringOrNull("otp"),
                    o.getLong("ts"), o.optBoolean("removed"))
                "devices" -> {
                    val arr = o.getJSONArray("list")
                    Devices((0 until arr.length()).map { i ->
                        val d = arr.getJSONObject(i)
                        Device(d.getString("name"), d.getString("addr"), Mode.valueOf(d.getString("role")), d.getLong("since"))
                    })
                }
                "ping" -> Ping
                else -> throw IllegalArgumentException("unknown message type $t")
            }
        }

        private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k)
    }
}
