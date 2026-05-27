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
    /** Rate for plain prose. */
    private val speechRate: Float = 0.9f,
    /** Rate for [[MATH_START]]...[[MATH_END]] blocks. Slower so equations are intelligible. */
    private val mathRate: Float = 0.65f,
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
     * Speaks [text] at the prose rate, replacing anything currently playing.
     * Suspends until the utterance finishes. For text containing
     * [[MATH_START]]...[[MATH_END]] markers, prefer [speakMarked].
     */
    suspend fun speak(text: String) {
        val engine = ready.await()
        if (text.isBlank()) return
        engine.setSpeechRate(speechRate)
        speakChunk(engine, text)
    }

    /**
     * Main speak entry point. Auto-detects which pipeline produced the text:
     *  - Contains [[MATH_START]] markers → SRE pipeline, use [speakMarked].
     *  - Otherwise → LLM pipeline with period-padded letters, use [speakLlmText].
     *
     * Adjacent period-padded letters (e.g. "A. L." from `a_L`) get an explicit
     * silence injected between them so TTS doesn't slur them into one syllable
     * ("A. L." → was being read as "ale").
     */
    suspend fun speakSmart(text: String) {
        val engine = ready.await()
        if (text.isBlank()) return
        if (text.contains("[[MATH_START]]")) {
            speakMarked(text)
        } else {
            speakLlmText(engine, text)
        }
    }

    /**
     * Speaks LLM output by splitting at adjacent period-padded letter
     * boundaries and inserting explicit 350ms silence between each. Forces a
     * real pause between letters of a subscript like "A. L." so TTS pronounces
     * them as distinct letters instead of running them together.
     */
    private suspend fun speakLlmText(engine: TextToSpeech, text: String) {
        engine.setSpeechRate(speechRate)
        val parts = splitOnAdjacentLetters(text)
        for ((i, part) in parts.withIndex()) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) speakChunk(engine, trimmed)
            // Insert silence between parts — only between, not after the last one.
            if (i < parts.size - 1) playSilence(engine, 350)
        }
    }

    /**
     * Splits [text] at the boundary between two adjacent period-padded letters.
     * E.g. "A. L. times B. over C." → ["A. ", "L. times B. over C."].
     * "covariance of A. and B." doesn't get split because "A." is followed by "and",
     * not another single letter.
     */
    private fun splitOnAdjacentLetters(text: String): List<String> {
        val pattern = Regex("""([A-Za-z]\.)(?=\s+[A-Za-z]\.)""")
        val parts = mutableListOf<String>()
        var lastIndex = 0
        for (match in pattern.findAll(text)) {
            parts.add(text.substring(lastIndex, match.range.last + 1))
            lastIndex = match.range.last + 1
        }
        parts.add(text.substring(lastIndex))
        return parts
    }

    private suspend fun playSilence(engine: TextToSpeech, durationMs: Long) {
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Unit>()
        pending[id] = deferred
        val rc = engine.playSilentUtterance(durationMs, TextToSpeech.QUEUE_FLUSH, id)
        if (rc == TextToSpeech.ERROR) {
            pending.remove(id)
            return  // silence is non-critical; don't throw
        }
        deferred.await()
    }

    /**
     * Speaks [text] containing [[MATH_START]]...[[MATH_END]] markers. Splits at
     * boundaries and uses [speechRate] for prose, [mathRate] for math chunks.
     * Also rewrites standalone 'a' / 'I' inside math chunks to 'letter A' /
     * 'letter I' so TTS doesn't read them as articles/pronouns.
     */
    suspend fun speakMarked(text: String) {
        val engine = ready.await()
        if (text.isBlank()) return
        val chunks = parseMarkedChunks(text)
        for (chunk in chunks) {
            val rate = if (chunk.isMath) mathRate else speechRate
            engine.setSpeechRate(rate)
            val ttsText = if (chunk.isMath) fixMathPronunciation(chunk.text) else chunk.text
            if (ttsText.isNotBlank()) speakChunk(engine, ttsText)
        }
        // Restore prose rate so any subsequent plain speak() calls aren't stuck slow.
        engine.setSpeechRate(speechRate)
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

    private data class MarkedChunk(val text: String, val isMath: Boolean)

    private fun parseMarkedChunks(text: String): List<MarkedChunk> {
        val pattern = Regex("""\[\[MATH_START\]\]\s*(.*?)\s*\[\[MATH_END\]\]""", RegexOption.DOT_MATCHES_ALL)
        val chunks = mutableListOf<MarkedChunk>()
        var lastIndex = 0
        for (match in pattern.findAll(text)) {
            val before = text.substring(lastIndex, match.range.first).trim()
            if (before.isNotEmpty()) chunks.add(MarkedChunk(before, isMath = false))
            val mathText = match.groupValues[1].trim()
            if (mathText.isNotEmpty()) chunks.add(MarkedChunk(mathText, isMath = true))
            lastIndex = match.range.last + 1
        }
        val tail = text.substring(lastIndex).trim()
        if (tail.isNotEmpty()) chunks.add(MarkedChunk(tail, isMath = false))
        return chunks
    }

    private fun fixMathPronunciation(text: String): String {
        // Applied ONLY inside [[MATH]] chunks — so aggressive substitution is
        // safe; prose isn't affected. Goal: make SRE's ClearSpeak output sound
        // like how a person actually reads equations.
        var out = text

        // 1. Drop verbose fraction boilerplate.
        //    "the fraction with numerator X and denominator Y" → "X all over Y"
        out = out.replace(
            Regex("""\bthe fraction with numerator\s+(.+?)\s+and denominator\s+""", RegexOption.IGNORE_CASE),
            "$1 all over ",
        )
        //    "the fraction X over Y" → "X over Y"
        out = out.replace(Regex("""\bthe fraction\s+""", RegexOption.IGNORE_CASE), "")

        // 2. Common finance-operator expansions that SRE leaves as letter
        //    sequences ("Cov sub a comma b").
        out = out.replace(
            Regex("""\bCov\s+sub\s+(\S+)\s+comma\s+(\S+)""", RegexOption.IGNORE_CASE),
            "covariance of $1 and $2",
        )
        out = out.replace(Regex("""\bCov\b"""), "covariance")
        out = out.replace(Regex("""\bVar\b"""), "variance")

        // 3. Phonetic substitution for single letters TTS routinely mispronounces.
        //    "a" → article schwa ("uh"); "I" → pronoun ("eye" is actually fine but
        //    be explicit). Order matters: capital first so lowercase replacement
        //    doesn't run twice.
        out = out.replace(Regex("""\bA\b"""), "ay")
        out = out.replace(Regex("""\ba\b"""), "ay")
        out = out.replace(Regex("""\bI\b"""), "eye")

        return out
    }

    /** Strips marker tokens for display purposes. */
    fun stripMarkers(text: String): String =
        text.replace(Regex("""\s*\[\[MATH_(START|END)\]\]\s*"""), " ").trim()

    /** Stops any in-flight utterance. Safe to call before init. */
    fun stop() {
        if (::tts.isInitialized) tts.stop()
    }

    /** Releases native resources. Call when the host is being destroyed. */
    fun shutdown() {
        if (::tts.isInitialized) tts.shutdown()
    }
}
