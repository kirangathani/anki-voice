package dev.kiran.ankivoice.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Suspending wrapper over Android [TextToSpeech].
 *
 * Single tunable pause: every `.` `?` `!` `:` `;` (followed by whitespace) and
 * every newline in the input triggers a [pauseMs] of explicit silence via
 * [TextToSpeech.playSilentUtterance]. TTS's built-in punctuation hints are
 * unreliable (it routinely runs sentences together); injecting real silence
 * guarantees a pause.
 *
 * To make speech faster overall: lower [pauseMs] (e.g. 80).
 * To make it more deliberate: raise [pauseMs] (e.g. 200).
 */
class TtsEngine(
    context: Context,
    private val onLog: (String) -> Unit = {},
    private val speechRate: Float = 0.85f,
    private val pitch: Float = 1.0f,
    /**
     * Silence in milliseconds injected at every pause point. THIS is the one
     * knob to tune playback pacing — affects every period, colon, newline, etc.
     */
    private val pauseMs: Long = 120,
) {
    private val ready = CompletableDeferred<TextToSpeech>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private lateinit var tts: TextToSpeech

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {}
        override fun onDone(utteranceId: String) {
            pending.remove(utteranceId)?.complete(Unit)
        }
        @Deprecated("Old API")
        override fun onError(utteranceId: String) {
            pending.remove(utteranceId)?.completeExceptionally(
                IllegalStateException("TTS error (unspecified) for utterance $utteranceId"),
            )
        }
        override fun onError(utteranceId: String, errorCode: Int) {
            pending.remove(utteranceId)?.completeExceptionally(
                IllegalStateException("TTS error $errorCode for utterance $utteranceId"),
            )
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status -> handleInit(status) }
        tts.setOnUtteranceProgressListener(progressListener)
    }

    private fun handleInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready.completeExceptionally(IllegalStateException("TTS init failed: status=$status"))
            return
        }
        try {
            tts.language = Locale.US
            tts.setSpeechRate(speechRate)
            tts.setPitch(pitch)
            pickBestVoice()
            logAvailableVoices()
            ready.complete(tts)
            onLog("[tts] ready rate=$speechRate pitch=$pitch pauseMs=$pauseMs")
        } catch (e: Exception) {
            ready.completeExceptionally(e)
        }
    }

    private fun pickBestVoice() {
        val voices = tts.voices ?: return
        val best = voices
            .filter { it.locale.language == "en" }
            .filterNot { it.features?.contains("notInstalled") == true }
            .filterNot { it.isNetworkConnectionRequired }
            .maxByOrNull { it.quality }
        if (best != null) {
            tts.voice = best
            onLog("[tts] picked voice=${best.name} q=${best.quality} locale=${best.locale}")
        } else {
            onLog("[tts] no preferred voice; using engine default")
        }
    }

    private fun logAvailableVoices() {
        val voices = tts.voices ?: return
        val englishVoices = voices
            .filter { it.locale.language == "en" }
            .filterNot { it.features?.contains("notInstalled") == true }
            .filterNot { it.isNetworkConnectionRequired }
            .sortedByDescending { it.quality }
        onLog("[tts] ${englishVoices.size} en voices available:")
        englishVoices.take(10).forEach { v ->
            onLog("[tts]   ${v.name} q=${v.quality} locale=${v.locale}")
        }
    }

    /** Awaits init. Idempotent. */
    suspend fun warmUp() { ready.await() }

    /**
     * Speaks [text]. Splits at every period/colon/semicolon/?/! followed by
     * whitespace, and at every newline. Each chunk is spoken separately and a
     * [pauseMs] silent utterance is queued between chunks — a guaranteed pause
     * regardless of how the underlying TTS voice handles punctuation.
     */
    suspend fun speak(text: String) {
        val engine = ready.await()
        if (text.isBlank()) return
        // Strip any internal pipeline markers (e.g. [[MATH_START]]/[[MATH_END]]
        // from the SRE fallback path) so they don't reach the TTS engine.
        val clean = text.replace(Regex("""\s*\[\[MATH_(START|END)\]\]\s*"""), " ")
        val parts = splitAtPausePoints(clean)
        for ((i, part) in parts.withIndex()) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) speakChunk(engine, trimmed)
            if (i < parts.size - 1) playSilence(engine, pauseMs)
        }
    }

    /**
     * Splits text wherever a pause should occur:
     *  - any of `.` `?` `!` `:` `;` followed by whitespace
     *  - any run of newlines
     */
    private fun splitAtPausePoints(text: String): List<String> {
        val pattern = Regex("""(?<=[.?!:;])\s+|\n+""")
        return text.split(pattern).filter { it.isNotBlank() }
    }

    private suspend fun speakChunk(engine: TextToSpeech, text: String) {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Unit>()
        pending[id] = deferred
        val rc = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (rc == TextToSpeech.ERROR) {
            pending.remove(id)
            throw IllegalStateException("TTS speak() returned ERROR")
        }
        deferred.await()
    }

    private suspend fun playSilence(engine: TextToSpeech, durationMs: Long) {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Unit>()
        pending[id] = deferred
        val rc = engine.playSilentUtterance(durationMs, TextToSpeech.QUEUE_FLUSH, id)
        if (rc == TextToSpeech.ERROR) {
            pending.remove(id)
            return  // silence is non-critical
        }
        deferred.await()
    }

    /** Stops any in-flight utterance. Safe to call before init. */
    fun stop() {
        if (::tts.isInitialized) tts.stop()
    }

    /** Releases native resources. Call when the host is being destroyed. */
    fun shutdown() {
        if (::tts.isInitialized) tts.shutdown()
    }

    /** Strips internal pipeline marker tokens for display purposes. */
    fun stripMarkers(text: String): String =
        text.replace(Regex("""\s*\[\[MATH_(START|END)\]\]\s*"""), " ").trim()
}
