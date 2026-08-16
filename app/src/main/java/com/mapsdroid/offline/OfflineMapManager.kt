package com.mapsdroid.offline

import android.content.Context
import com.mapsdroid.core.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Manages downloaded offline regions (Phase 6 scaffold).
 *
 * The offline stack, per the research, is an open-data substitute for Apple's tiles (which cannot be
 * cached — expiring signed URLs, proprietary VMP4, and an explicit ToS ban). A region download pulls:
 *   1. a Protomaps PMTiles basemap extract for the bounding box, rendered by MapLibre with an
 *      Apple-look style (see assets/style/apple_like_*.json), and
 *   2. a Valhalla routing-tile pack for offline turn-by-turn.
 *
 * This class tracks region metadata and file locations; the actual extract/download pipeline and the
 * MapLibre map view are the remaining Phase 6 work items documented in docs/OFFLINE.md.
 */
class OfflineMapManager(private val context: Context) {

    private val regionsDir: File by lazy {
        File(context.filesDir, "offline").apply { mkdirs() }
    }

    private val _regions = MutableStateFlow<List<OfflineRegion>>(emptyList())
    val regions: StateFlow<List<OfflineRegion>> = _regions

    fun regionContaining(point: GeoPoint): OfflineRegion? =
        _regions.value.firstOrNull { it.contains(point) }

    /**
     * Downloads a region covering the given bounds. Phase 6: fetch a PMTiles extract (via the
     * Protomaps extract service or a bundled planet slice) and a Valhalla tile pack into [regionsDir].
     */
    suspend fun download(name: String, southWest: GeoPoint, northEast: GeoPoint): Result<OfflineRegion> {
        // TODO(phase6): PMTiles extract + Valhalla tile fetch. See docs/OFFLINE.md.
        return Result.failure(NotImplementedError("Offline region download lands in Phase 6"))
    }

    fun delete(region: OfflineRegion) {
        File(region.pmtilesPath).delete()
        region.valhallaTilesPath?.let { File(it).delete() }
        _regions.value = _regions.value.filterNot { it.id == region.id }
    }
}
