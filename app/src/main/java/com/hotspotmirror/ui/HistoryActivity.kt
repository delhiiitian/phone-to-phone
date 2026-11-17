package com.hotspotmirror.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hotspotmirror.viewer.HistoryDb
import kotlin.concurrent.thread

/** Newest-first list of calls, messages or notifications. Tap a row with a code to copy it. */
class HistoryActivity : AppCompatActivity() {
    private lateinit var kind: String
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        kind = intent.getStringExtra(EXTRA_KIND) ?: HistoryDb.SMS
        title = when (kind) { HistoryDb.CALL -> "Call history"; HistoryDb.SMS -> "Message history"; else -> "Notification history" }
        list = ListView(this)
        setContentView(list)
    }

    override fun onResume() {
        super.onResume()
        thread {
            val rows = HistoryDb.get(this).list(kind)
            runOnUiThread {
                if (rows.isEmpty()) { title = "$title (empty)"; return@runOnUiThread }
                list.adapter = object : ArrayAdapter<HistoryDb.Row>(this, android.R.layout.simple_list_item_2, android.R.id.text1, rows) {
                    override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                        val v = super.getView(position, convertView, parent)
                        val r = rows[position]
                        v.findViewById<android.widget.TextView>(android.R.id.text1).text = r.title
                        v.findViewById<android.widget.TextView>(android.R.id.text2).text =
                            DateUtils.formatDateTime(context, r.ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME) +
                                "\n" + r.body + (r.code?.let { "\nTap to copy code $it" } ?: "")
                        return v
                    }
                }
                list.setOnItemClickListener { _, _, pos, _ ->
                    rows[pos].code?.let {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("OTP", it))
                        Toast.makeText(this, "Copied $it", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    companion object { const val EXTRA_KIND = "kind" }
}
