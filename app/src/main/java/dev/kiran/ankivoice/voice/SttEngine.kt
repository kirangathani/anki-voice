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
     * Listens for one utterance. Suspends until the recogniser returns a
     * result or errors. Serialised — concurrent calls queue.
     *
     * @param languageTag BCP-47 language tag, default "en-US".
     */
    suspend fun listen(languageTag: String = "en-US"): Result = mutex.withLock {
        withContext(Dispatchers.Main) {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                return@withContext Result.Error(-1, "No SpeechRecognizer available on device")
            }
            suspendCancellableCoroutine<Result> { cont -> startListening(cont, languageTag) }
        }
    }

    private fun startListening(cont: CancellableContinuation<Result>, languageTag: String) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)

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

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
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

        onLog("[stt] startListening lang=$languageTag")
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
