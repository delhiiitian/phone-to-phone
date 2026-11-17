package com.hotspotmirror.viewer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Local history of everything the Viewer received. Newest first, capped per kind. */
class HistoryDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "history.db", null, 1) {

    data class Row(val ts: Long, val title: String, val body: String, val code: String?)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, ts INTEGER NOT NULL, " +
            "title TEXT NOT NULL, body TEXT NOT NULL, code TEXT)")
        db.execSQL("CREATE INDEX events_kind_ts ON events(kind, ts DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit

    @Synchronized
    fun insert(kind: String, ts: Long, title: String, body: String, code: String? = null) {
        val db = writableDatabase
        db.insert("events", null, ContentValues().apply {
            put("kind", kind); put("ts", ts); put("title", title); put("body", body); put("code", code)
        })
        db.execSQL("DELETE FROM events WHERE kind = ? AND id NOT IN (SELECT id FROM events WHERE kind = ? ORDER BY ts DESC LIMIT $KEEP)",
            arrayOf(kind, kind))
    }

    @Synchronized
    fun list(kind: String): List<Row> =
        readableDatabase.rawQuery("SELECT ts, title, body, code FROM events WHERE kind = ? ORDER BY ts DESC", arrayOf(kind)).use { c ->
            buildList { while (c.moveToNext()) add(Row(c.getLong(0), c.getString(1), c.getString(2), c.getString(3))) }
        }

    @Synchronized
    fun clear(kind: String) { writableDatabase.delete("events", "kind = ?", arrayOf(kind)) }

    companion object {
        const val SMS = "sms"
        const val CALL = "call"
        const val NOTIF = "notif"
        private const val KEEP = 2000
        @Volatile private var instance: HistoryDb? = null
        fun get(ctx: Context): HistoryDb = instance ?: synchronized(this) { instance ?: HistoryDb(ctx).also { instance = it } }
    }
}
