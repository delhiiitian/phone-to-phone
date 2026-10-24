package com.hotspotmirror.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hotspotmirror.core.*
import com.hotspotmirror.sender.SenderService
import com.hotspotmirror.viewer.ViewerService
import com.hotspotmirror.viewer.ViewerState

/** Single screen, built in code: mode picker, pairing code, permission shortcuts, and (Viewer) feed + devices. */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        ViewerState.onChange = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() { ViewerState.onChange = null; super.onPause() }

    private fun render() {
        root.removeAllViews()
        when (prefs.mode) {
            Mode.NONE -> pickMode()
            Mode.SENDER -> senderScreen()
            Mode.VIEWER -> viewerScreen()
        }
    }

    private fun text(s: String, size: Float = 16f) = TextView(this).apply { text = s; textSize = size; setPadding(0, 12, 0, 12) }
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; setOnClickListener { onClick() } }

    private fun pickMode() {
        root.addView(text("Hotspot Mirror", 24f))
        root.addView(text("Which phone is this?"))
        root.addView(button("Sender – the hotspot phone (has the SIM)") {
            prefs.mode = Mode.SENDER; if (prefs.pairingCode.isEmpty()) prefs.pairingCode = Prefs.generateCode(); render()
        })
        root.addView(button("Viewer – my personal phone") { prefs.mode = Mode.VIEWER; render() })
    }

    private fun commonButtons() {
        if (!Background.isExempt(this))
            root.addView(button("Allow background running (battery)") { startActivity(Background.requestExemptionIntent(this)) })
        root.addView(button("Change mode") { stopServices(); prefs.mode = Mode.NONE; render() })
    }

    private fun senderScreen() {
        root.addView(text("Sender mode", 24f))
        root.addView(text("Pairing code (type this into the Viewer):"))
        root.addView(text(prefs.pairingCode, 28f).apply { gravity = Gravity.CENTER })
        root.addView(button("New code") { prefs.pairingCode = Prefs.generateCode(); stopServices(); render() })
        root.addView(button("Grant SMS / call / contacts permissions") {
            permLauncher.launch(arrayOf(
                Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS,
                *(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())))
        })
        root.addView(button("Enable notification access") { startActivity(Background.notificationListenerSettings()) })
        root.addView(button("Start forwarding") {
            ContextCompat.startForegroundService(this, Intent(this, SenderService::class.java))
            Toast.makeText(this, "Sender running", Toast.LENGTH_SHORT).show()
        })
        commonButtons()
    }

    private fun viewerScreen() {
        root.addView(text("Viewer mode", 24f))
        val code = EditText(this).apply { hint = "Pairing code XXXX-XXXX-XXXX"; setText(prefs.pairingCode) }
        root.addView(code)
        root.addView(button("Save code and connect") {
            prefs.pairingCode = code.text.toString()
            stopServices()
            permLauncher.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
            ContextCompat.startForegroundService(this, Intent(this, ViewerService::class.java))
        })
        root.addView(text(if (ViewerState.connected) "Connected to ${ViewerState.senderName}" else "Not connected"))
        root.addView(text("Connected devices", 18f))
        ViewerState.devices.forEach { root.addView(text("• ${it.name} (${it.address})")) }
        root.addView(text("Recent", 18f))
        ViewerState.feed.take(30).forEach { root.addView(text(describe(it))) }
        commonButtons()
    }

    private fun describe(m: Message): String = when (m) {
        is Message.Sms -> "SMS ${m.contactName ?: m.from}: ${m.body}" + (m.otp?.let { "  [code $it]" } ?: "")
        is Message.Call -> "Call ${m.state}: ${m.contactName ?: m.number ?: "Unknown"}"
        is Message.Notif -> "${m.appName}: ${m.title.orEmpty()} ${m.text.orEmpty()}"
        else -> ""
    }

    private fun stopServices() {
        stopService(Intent(this, SenderService::class.java)); stopService(Intent(this, ViewerService::class.java))
    }

    private val permLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {}
}
