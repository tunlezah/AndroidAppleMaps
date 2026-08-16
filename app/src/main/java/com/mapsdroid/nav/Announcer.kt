package com.mapsdroid.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks maneuver instructions through the navigation-guidance audio channel. Requests transient
 * "may duck" focus with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` so music lowers rather than stops,
 * and — importantly for Android Auto — the head unit routes this usage to the car speakers and
 * ducks its media automatically.
 */
class Announcer(context: Context) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var ready = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var focusRequest: AudioFocusRequest? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            tts?.setAudioAttributes(attributes)
            ready = true
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        requestFocus()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        // Focus is released opportunistically on the next utterance; nav prompts are frequent enough
        // that holding transient-duck focus briefly between them is preferable to audible refocus gaps.
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .build().also { focusRequest = it }
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(audioManager::abandonAudioFocusRequest)
        }
    }
}
