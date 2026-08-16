package com.mapsdroid.offline

import android.content.Context

/**
 * Builds a MapLibre style JSON for the offline map by inlining the downloaded region's PMTiles path
 * into the Apple-look style template. If no region is available, a minimal background-only style is
 * returned so the map surface still renders (blank) instead of failing to load.
 */
object StyleProvider {

    fun styleJson(context: Context, region: OfflineRegion?, dark: Boolean): String {
        val asset = if (dark) "style/apple_like_dark.json" else "style/apple_like_light.json"
        val template = context.assets.open(asset).bufferedReader().use { it.readText() }
        val pmtilesUrl = region?.let { "pmtiles://${it.pmtilesPath}" }
        return if (pmtilesUrl != null) {
            template.replace("__PMTILES_URL__", pmtilesUrl)
        } else {
            blankStyle(dark)
        }
    }

    private fun blankStyle(dark: Boolean): String {
        val bg = if (dark) "#12141a" else "#f2f1ec"
        return """
            {
              "version": 8,
              "name": "Offline (no region)",
              "sources": {},
              "layers": [ { "id": "background", "type": "background", "paint": { "background-color": "$bg" } } ]
            }
        """.trimIndent()
    }
}
