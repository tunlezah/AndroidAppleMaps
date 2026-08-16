package com.mapsdroid.links

import android.net.Uri
import com.mapsdroid.core.GeoPoint
import com.mapsdroid.core.TransportType

/**
 * Parses the Apple Maps URL schemes an Android user is likely to receive from an iPhone contact:
 * both the legacy `maps.apple.com/?q=&ll=&daddr=` form and the 2025 unified path form
 * (`/place`, `/directions`, `/search`, `/look-around`). Also accepts `geo:` intents.
 *
 * The intent is a wedge feature: today a whole genre of Android apps exists solely to redirect these
 * links to Google Maps. We open them natively instead.
 */
sealed interface AppleMapsIntent {
    data class ShowPlace(val point: GeoPoint?, val query: String?, val name: String?) : AppleMapsIntent
    data class Search(val query: String, val near: GeoPoint?) : AppleMapsIntent
    data class Directions(
        val destination: GeoPoint?,
        val destinationQuery: String?,
        val source: GeoPoint?,
        val mode: TransportType,
    ) : AppleMapsIntent
    data class LookAround(val point: GeoPoint) : AppleMapsIntent
    data object Unknown : AppleMapsIntent
}

object AppleMapsLink {

    fun parse(uri: Uri): AppleMapsIntent {
        if (uri.scheme == "geo") return parseGeo(uri)
        val path = uri.path?.trimEnd('/') ?: ""
        return when {
            path.endsWith("/directions") -> parseUnifiedDirections(uri)
            path.endsWith("/place") -> AppleMapsIntent.ShowPlace(
                point = coordinateParam(uri, "coordinate"),
                query = uri.getQueryParameter("address"),
                name = uri.getQueryParameter("name"),
            )
            path.endsWith("/search") -> AppleMapsIntent.Search(
                query = uri.getQueryParameter("query").orEmpty(),
                near = coordinateParam(uri, "center"),
            )
            path.endsWith("/look-around") -> coordinateParam(uri, "coordinate")
                ?.let { AppleMapsIntent.LookAround(it) } ?: AppleMapsIntent.Unknown
            else -> parseLegacy(uri)
        }
    }

    private fun parseLegacy(uri: Uri): AppleMapsIntent {
        val daddr = uri.getQueryParameter("daddr")
        if (daddr != null) {
            return AppleMapsIntent.Directions(
                destination = parseLatLng(daddr),
                destinationQuery = if (parseLatLng(daddr) == null) daddr else null,
                source = uri.getQueryParameter("saddr")?.let(::parseLatLng),
                mode = dirflgToMode(uri.getQueryParameter("dirflg")),
            )
        }
        val ll = uri.getQueryParameter("ll")?.let(::parseLatLng)
        val q = uri.getQueryParameter("q")
        if (ll != null || q != null) {
            // A bare "q" that is itself a coordinate is a place, otherwise a search.
            val qCoord = q?.let(::parseLatLng)
            return when {
                qCoord != null -> AppleMapsIntent.ShowPlace(qCoord, null, null)
                ll != null -> AppleMapsIntent.ShowPlace(ll, q, q)
                else -> AppleMapsIntent.Search(q!!, ll)
            }
        }
        return AppleMapsIntent.Unknown
    }

    private fun parseUnifiedDirections(uri: Uri): AppleMapsIntent {
        val dest = uri.getQueryParameter("destination")
        return AppleMapsIntent.Directions(
            destination = dest?.let(::parseLatLng),
            destinationQuery = if (dest != null && parseLatLng(dest) == null) dest else null,
            source = uri.getQueryParameter("source")?.let(::parseLatLng),
            mode = when (uri.getQueryParameter("mode")?.lowercase()) {
                "walking" -> TransportType.WALKING
                "cycling" -> TransportType.CYCLING
                else -> TransportType.AUTOMOBILE
            },
        )
    }

    private fun parseGeo(uri: Uri): AppleMapsIntent {
        // geo:lat,lng?q=lat,lng(label) or geo:0,0?q=query
        val ssp = uri.schemeSpecificPart ?: return AppleMapsIntent.Unknown
        val beforeQuery = ssp.substringBefore('?')
        val base = parseLatLng(beforeQuery)
        val q = uri.getQueryParameter("q")
        return when {
            q != null && parseLatLng(q.substringBefore('(')) != null ->
                AppleMapsIntent.ShowPlace(parseLatLng(q.substringBefore('(')), null, q.substringAfter('(', "").trimEnd(')').ifEmpty { null })
            q != null && base != null && base.latitude == 0.0 && base.longitude == 0.0 ->
                AppleMapsIntent.Search(q, null)
            base != null -> AppleMapsIntent.ShowPlace(base, q, null)
            else -> AppleMapsIntent.Unknown
        }
    }

    private fun coordinateParam(uri: Uri, key: String): GeoPoint? =
        uri.getQueryParameter(key)?.let(::parseLatLng)

    private fun parseLatLng(value: String?): GeoPoint? {
        if (value == null) return null
        val parts = value.split(",")
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lng = parts[1].trim().toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return GeoPoint(lat, lng)
    }

    private fun dirflgToMode(dirflg: String?): TransportType = when (dirflg) {
        "w" -> TransportType.WALKING
        "r" -> TransportType.AUTOMOBILE // transit → we do not route transit; fall back to driving
        else -> TransportType.AUTOMOBILE
    }
}
