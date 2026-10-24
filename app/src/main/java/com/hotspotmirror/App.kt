package com.hotspotmirror

import android.app.Application
import com.hotspotmirror.core.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
