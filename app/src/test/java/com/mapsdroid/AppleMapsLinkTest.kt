package com.mapsdroid

import android.net.Uri
import com.mapsdroid.core.TransportType
import com.mapsdroid.links.AppleMapsIntent
import com.mapsdroid.links.AppleMapsLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Uri parsing needs the Android framework, so these run under Robolectric. If Robolectric is not
 * available in the environment, this class is skipped by the runner rather than failing the suite.
 */
@RunWith(RobolectricTestRunner::class)
class AppleMapsLinkTest {

    @Test
    fun legacyDaddrCoordinate_parsesAsDrivingDirections() {
        val uri = Uri.parse("https://maps.apple.com/?daddr=37.33,-122.03&dirflg=d")
        val intent = AppleMapsLink.parse(uri)
        assertTrue(intent is AppleMapsIntent.Directions)
        intent as AppleMapsIntent.Directions
        assertEquals(37.33, intent.destination!!.latitude, 1e-6)
        assertEquals(TransportType.AUTOMOBILE, intent.mode)
    }

    @Test
    fun unifiedDirections_walking() {
        val uri = Uri.parse("https://maps.apple.com/directions?destination=51.5,-0.12&mode=walking")
        val intent = AppleMapsLink.parse(uri) as AppleMapsIntent.Directions
        assertEquals(TransportType.WALKING, intent.mode)
        assertEquals(51.5, intent.destination!!.latitude, 1e-6)
    }

    @Test
    fun unifiedPlace_parsesCoordinate() {
        val uri = Uri.parse("https://maps.apple.com/place?coordinate=48.8584,2.2945&name=Eiffel")
        val intent = AppleMapsLink.parse(uri) as AppleMapsIntent.ShowPlace
        assertEquals(48.8584, intent.point!!.latitude, 1e-6)
    }

    @Test
    fun lookAround_parses() {
        val uri = Uri.parse("https://maps.apple.com/look-around?coordinate=40.0,-74.0")
        val intent = AppleMapsLink.parse(uri)
        assertTrue(intent is AppleMapsIntent.LookAround)
    }
}
