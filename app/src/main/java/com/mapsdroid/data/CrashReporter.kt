package com.mapsdroid.data

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Captures uncaught exceptions to a file so a crash becomes visible on the next launch (shown by
 * MainActivity) without needing adb/logcat. Personal-debugging aid, not analytics.
 */
object CrashReporter {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Log.e("MapsDroid", "Uncaught exception", throwable)
                File(appContext.filesDir, FILE).writeText(
                    "Thread: ${thread.name}\n\n" + Log.getStackTraceString(throwable),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun consume(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull()
        file.delete()
        return text?.takeIf { it.isNotBlank() }
    }
}
