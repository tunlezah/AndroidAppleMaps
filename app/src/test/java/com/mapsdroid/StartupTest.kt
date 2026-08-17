package com.mapsdroid

import com.mapsdroid.data.AppPreferences
import com.mapsdroid.nav.NavHub
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards the startup path that produced a blank screen. These run headlessly, so they catch the
 * failure modes that render nothing: a crash (or partial init) in Application/Activity creation, and
 * an EULA preference flow that never emits (which leaves the UI gated on a null state forever).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StartupTest {

    @Test
    fun applicationInitializesNavHubFully() {
        // Instantiating the application runs MapsApp.onCreate -> NavHub.init.
        RuntimeEnvironment.getApplication()
        assertTrue("NavHub must finish init or the first frame throws", NavHub.isReady)
        // Reading these is exactly what the UI does on first composition.
        assertNotNull(NavHub.state.value)
        assertNotNull(NavHub.session)
        assertNotNull(NavHub.connectivity.online.value)
        assertNotNull(NavHub.offlineManager.regions.value)
    }

    @Test
    fun eulaPreferenceEmitsPromptly() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val value = withTimeoutOrNull(5_000) { AppPreferences(app).eulaAccepted.first() }
        assertNotNull("eulaAccepted must emit; a stalled flow renders a blank gate", value)
        assertEquals("first run should report not-accepted", false, value)
    }

    @Test
    fun mainActivityReachesResumedWithoutCrashing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup() // create -> start -> resume
        assertNotNull(controller.get())
        controller.close()
    }
}
