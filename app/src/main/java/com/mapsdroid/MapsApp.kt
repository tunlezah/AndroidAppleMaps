package com.mapsdroid

import android.app.Application
import com.mapsdroid.data.CrashReporter
import com.mapsdroid.nav.NavHub

class MapsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Install the crash handler first so failures during init are also captured.
        CrashReporter.install(this)
        NavHub.init(this)
    }
}
