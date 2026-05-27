package dev.kiran.ankivoice.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Suspending wrapper over Android's [TextToSpeech]. One instance per process.
 *
 * Construction kicks off async init; [warmUp] suspends until it's ready (or
 * fails). [speak] queues an utterance with QUEUE_FLUSH (interrupts whatever's
 * currently playing) and resolves when its onDone fires. Errors surface as
 * exceptions.
 *
 * Voice selection picks the highest-quality English voice that doesn't need a
 * network connection.
 */
class TtsEngine(
    context: Context,
    private val onLog: (String) -> Unit = {},
    private val speechRate: Float = 0.9f,
    private val pitch: Float = 1.0f,
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
                IllegalStateException("TTS error (unspecified) for utterance $utteranceId")
            )
        }
        override fun onError(utteranceId: String, errorCode: Int) {
            pending.remove(utteranceId)?.completeExceptionally(
                IllegalStateException("TTS error $errorCode for utterance $utteranceId")
            )
        }
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            handleInit(status)
        }
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
            onLog("[tts] ready rate=$speechRate pitch=$pitch")
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

    /** Lists all installed English voices so we can see what's available. */
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
    suspend fun warmUp(): Unit { ready.await() }

    /**
     * Speaks [text], replacing anything currently playing. Suspends until the
     * utterance finishes (or errors).
     */
    suspend fun speak(text: String) {
        val engine = ready.await()
        if (text.isBlank()) return
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

    /** Stops any in-flight utterance. Safe to call before init. */
    fun stop() {
        if (::tts.isInitialized) tts.stop()
    }

    /** Releases native resources. Call when the host is being destroyed. */
    fun shutdown() {
        if (::tts.isInitialized) tts.shutdown()
    }
}
