package com.mapsdroid.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/** Car session; hands back the single [NavigationScreen] that renders the shared guidance state. */
class MapsSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = NavigationScreen(carContext)
}
