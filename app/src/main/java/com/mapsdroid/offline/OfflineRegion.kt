package com.mapsdroid.offline

import com.mapsdroid.core.GeoPoint
import kotlinx.serialization.Serializable

/** A user-downloaded offline area: a bounding box plus the local packs backing it. */
@Serializable
data class OfflineRegion(
    val id: String,
    val name: String,
    val southWest: GeoPoint,
    val northEast: GeoPoint,
    /** Local path to the Protomaps PMTiles basemap pack. */
    val pmtilesPath: String,
    /** Local path to the Valhalla routing tile pack (tar), if downloaded. */
    val valhallaTilesPath: String?,
    val sizeBytes: Long,
) {
    fun contains(point: GeoPoint): Boolean =
        point.latitude in southWest.latitude..northEast.latitude &&
            point.longitude in southWest.longitude..northEast.longitude
}
