package com.mapsdroid.offline

import android.content.Context
import com.mapsdroid.core.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.last
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Manages downloaded offline regions.
 *
 * The offline stack is an open-data substitute for Apple's tiles (which cannot be cached — expiring
 * signed URLs, proprietary VMP4, and an explicit ToS ban). A region consists of:
 *   1. a Protomaps PMTiles basemap pack (downloaded here, rendered by MapLibre with an Apple-look
 *      style), and
 *   2. optionally a Valhalla routing-tile pack for offline turn-by-turn.
 *
 * Region metadata is persisted as JSON alongside the packs so downloads survive restarts.
 */
class OfflineMapManager(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val downloader = PmTilesDownloader()

    private val regionsDir: File by lazy { File(context.filesDir, "offline").apply { mkdirs() } }
    private val indexFile: File by lazy { File(regionsDir, "regions.json") }

    private val _regions = MutableStateFlow(loadIndex())
    val regions: StateFlow<List<OfflineRegion>> = _regions

    fun regionContaining(point: GeoPoint): OfflineRegion? =
        _regions.value.firstOrNull { it.contains(point) }

    /**
     * Downloads a PMTiles basemap for [name] from [pmtilesUrl] and registers it as an offline region.
     * A Valhalla routing pack can be attached later via [attachRoutingTiles].
     */
    suspend fun download(
        name: String,
        southWest: GeoPoint,
        northEast: GeoPoint,
        pmtilesUrl: String,
    ): Result<OfflineRegion> {
        val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        val dest = File(regionsDir, "$id.pmtiles")
        val result = downloader.download(pmtilesUrl, dest).last()
        return when (result) {
            is PmTilesDownloader.Progress.Done -> {
                val region = OfflineRegion(
                    id = id,
                    name = name,
                    southWest = southWest,
                    northEast = northEast,
                    pmtilesPath = dest.absolutePath,
                    valhallaTilesPath = null,
                    sizeBytes = dest.length(),
                )
                upsert(region)
                Result.success(region)
            }
            is PmTilesDownloader.Progress.Failed -> Result.failure(result.error)
            else -> Result.failure(IllegalStateException("download did not complete"))
        }
    }

    fun attachRoutingTiles(regionId: String, valhallaTilesPath: String) {
        _regions.value.firstOrNull { it.id == regionId }?.let { region ->
            upsert(region.copy(valhallaTilesPath = valhallaTilesPath))
        }
    }

    fun delete(region: OfflineRegion) {
        File(region.pmtilesPath).delete()
        region.valhallaTilesPath?.let { File(it).delete() }
        _regions.value = _regions.value.filterNot { it.id == region.id }
        saveIndex()
    }

    private fun upsert(region: OfflineRegion) {
        _regions.value = _regions.value.filterNot { it.id == region.id } + region
        saveIndex()
    }

    private fun loadIndex(): List<OfflineRegion> = runCatching {
        if (!indexFile.exists()) emptyList()
        else json.decodeFromString<List<OfflineRegion>>(indexFile.readText())
    }.getOrDefault(emptyList())

    private fun saveIndex() {
        runCatching { indexFile.writeText(json.encodeToString(_regions.value)) }
    }
}
