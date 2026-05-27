package dev.kiran.ankivoice.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Suspending wrapper over Android [SpeechRecognizer].
 *
 * Single-utterance model: [listen] starts the recogniser, awaits either a
 * recognised transcript or an error, then tears the recogniser down. Callers
 * can serialise multiple listen calls back-to-back.
 *
 * Threading: SpeechRecognizer must be created and called on the main thread.
 * All Main-thread work is wrapped internally; callers can be on any dispatcher.
 *
 * Requires [android.Manifest.permission.RECORD_AUDIO] — caller must check
 * and request the permission before invoking [listen].
 */
class SttEngine(
    context: Context,
    private val onLog: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mutex = Mutex()

    sealed class Result {
        data class Recognized(val transcript: String) : Result()
        data class Error(val code: Int, val message: String) : Result()
        data object NoMatch : Result()
    }

    /**
     * Listens for one utterance.
     *
     * @param languageTag BCP-47 language tag, default "en-US".
     * @param silenceMs how long the user can be silent before the recogniser
     *   treats them as done. Default 300000ms (5 minutes) — effectively no
     *   silence-based cutoff for normal use; the user is expected to end the
     *   utterance with a wake word. Advisory — some recogniser implementations
     *   cap this at lower values internally.
     * @param wakeWords if non-empty, partial-results mode is enabled and the
     *   recogniser stops as soon as any of these words appears in the in-flight
     *   transcript (case-insensitive substring match). Useful for "done" / "stop"
     *   / "next" style explicit-end commands.
     */
    suspend fun listen(
        languageTag: String = "en-US",
        silenceMs: Long = 300_000,
        wakeWords: List<String> = emptyList(),
    ): Result = mutex.withLock {
        withContext(Dispatchers.Main) {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                return@withContext Result.Error(-1, "No SpeechRecognizer available on device")
            }
            suspendCancellableCoroutine<Result> { cont ->
                startListening(cont, languageTag, silenceMs, wakeWords)
            }
        }
    }

    private fun startListening(
        cont: CancellableContinuation<Result>,
        languageTag: String,
        silenceMs: Long,
        wakeWords: List<String>,
    ) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        // Avoid calling stopListening() more than once when several partial-result
        // callbacks all match the wake word in quick succession.
        var stopRequested = false
        val wakeLower = wakeWords.map { it.lowercase() }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onLog("[stt] ready for speech")
            }

            override fun onBeginningOfSpeech() {
                onLog("[stt] speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                onLog("[stt] speech ended")
            }

            override fun onError(error: Int) {
                val msg = errorName(error)
                onLog("[stt] error: $msg ($error)")
                recognizer.destroy()
                if (cont.isActive) cont.resume(Result.Error(error, msg))
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull().orEmpty().trim()
                onLog("[stt] recognised: '$text' (${list?.size ?: 0} alts)")
                recognizer.destroy()
                if (cont.isActive) {
                    cont.resume(if (text.isEmpty()) Result.NoMatch else Result.Recognized(text))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (stopRequested || wakeLower.isEmpty()) return
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.lowercase()
                    .orEmpty()
                if (partial.isEmpty()) return
                val hit = wakeLower.firstOrNull { partial.contains(it) }
                if (hit != null) {
                    stopRequested = true
                    onLog("[stt] wake word '$hit' matched in partial '$partial', stopping")
                    try { recognizer.stopListening() } catch (_: Throwable) {}
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, wakeLower.isNotEmpty())
            // Hint extras: how long silence before recogniser treats utterance
            // as complete. Default Android value is ~2000ms which is too short
            // for thinking pauses. These are advisory — some implementations
            // ignore them — but worth setting.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000L)
            // EXTRA_PREFER_OFFLINE intentionally NOT set — without an installed
            // offline language pack it errors out as ERROR_LANGUAGE_UNAVAILABLE
            // (13) instead of falling back to the cloud recogniser.
        }

        cont.invokeOnCancellation {
            try {
                recognizer.cancel()
                recognizer.destroy()
            } catch (_: Throwable) {}
        }

        onLog("[stt] startListening lang=$languageTag silenceMs=$silenceMs wakeWords=$wakeLower")
        recognizer.startListening(intent)
    }

    private fun errorName(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"           // 1
        SpeechRecognizer.ERROR_NETWORK -> "network"                            // 2
        SpeechRecognizer.ERROR_AUDIO -> "audio"                                // 3
        SpeechRecognizer.ERROR_SERVER -> "server"                              // 4
        SpeechRecognizer.ERROR_CLIENT -> "client"                              // 5
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech_timeout"              // 6
        SpeechRecognizer.ERROR_NO_MATCH -> "no_match"                          // 7
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer_busy"            // 8
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient_permissions" // 9
        10 -> "too_many_requests"
        11 -> "server_disconnected"
        12 -> "language_not_supported"
        13 -> "language_unavailable (no offline pack — set EXTRA_PREFER_OFFLINE=false)"
        14 -> "cannot_check_support"
        15 -> "cannot_listen_to_download_events"
        else -> "unknown_$code"
    }
}
