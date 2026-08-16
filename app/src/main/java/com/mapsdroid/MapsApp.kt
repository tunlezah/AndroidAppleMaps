package com.mapsdroid

import android.app.Application
import com.mapsdroid.nav.NavHub

class MapsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NavHub.init(this)
    }
}
