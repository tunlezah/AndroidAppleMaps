package com.mapsdroid.offline

/**
 * JNI bridge to `libvalhalla_jni`. The native library is produced by the NDK module in
 * `native/valhalla/` (not built in CI without the NDK toolchain), so we load it defensively:
 * [isAvailable] is false when the `.so` is absent, and callers fall back to "no offline route".
 */
class ValhallaNative {

    val isAvailable: Boolean = LOADED

    /**
     * Returns a Valhalla `/route` JSON response, or null on failure.
     * @param tilesPath directory (or .tar) of Valhalla routing tiles for the region.
     * @param mode one of AUTOMOBILE, WALKING, CYCLING (mapped to Valhalla costing native-side).
     */
    fun route(
        tilesPath: String,
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        mode: String,
    ): String? = if (LOADED) nativeRoute(tilesPath, fromLat, fromLng, toLat, toLng, mode) else null

    private external fun nativeRoute(
        tilesPath: String,
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        mode: String,
    ): String?

    private companion object {
        val LOADED: Boolean = runCatching { System.loadLibrary("valhalla_jni") }.isSuccess
    }
}
