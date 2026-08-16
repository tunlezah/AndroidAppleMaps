package com.mapsdroid.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Downloads a `.pmtiles` regional basemap pack (e.g. a Protomaps extract) to local storage, emitting
 * progress. This is the real offline-data acquisition step; the app renders the resulting file via
 * MapLibre's PMTiles support (no server needed at run time).
 */
class PmTilesDownloader(private val client: OkHttpClient = OkHttpClient()) {

    sealed interface Progress {
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : Progress
        data class Done(val file: File) : Progress
        data class Failed(val error: Throwable) : Progress
    }

    fun download(url: String, dest: File): Flow<Progress> = flow {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                emit(Progress.Failed(IOException("HTTP ${response.code}")))
                return@flow
            }
            val body = response.body ?: run {
                emit(Progress.Failed(IOException("empty body")))
                return@flow
            }
            val total = body.contentLength()
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        readTotal += n
                        emit(Progress.Downloading(readTotal, total))
                    }
                }
            }
            emit(Progress.Done(dest))
        }
    }.flowOn(Dispatchers.IO)
}
