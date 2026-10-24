package com.hotspotmirror.sender

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

object Contacts {
    fun nameFor(ctx: Context, number: String?): String? {
        if (number.isNullOrBlank() || ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != 0) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }
}
