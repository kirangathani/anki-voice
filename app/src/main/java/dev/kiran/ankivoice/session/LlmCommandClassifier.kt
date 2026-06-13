package dev.kiran.ankivoice.session

import dev.kiran.ankivoice.anki.AnkiContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * LLM intent classifier for loose voice commands, consulted by [CommandResolver]
 * only when the keyword [CommandParser] returns [ReviewCommand.Unknown].
 *
 * Uses the same Claude Haiku 4.5 pattern as
 * [dev.kiran.ankivoice.voice.LlmSpeechConverter]: OkHttp + org.json, with the
 * static vocabulary system prompt sent as a cacheable block (~90% discount on
 * cached tokens within ~5 min).
 *
 * The model is asked to emit a single intent label from a fixed set; that label
 * is mapped back to a [ReviewCommand] locally. Anything off-vocabulary maps to
 * [ReviewCommand.Unknown].
 *
 * Graceful by design: an empty [apiKey], a blank transcript, or any network /
 * parse error yields [ReviewCommand.Unknown] (never throws), so the resolver
 * stays offline-safe and the session simply re-prompts.
 */
class LlmCommandClassifier(
    private val apiKey: String,
    private val cache: CommandCache? = null,
    private val onLog: (String) -> Unit = {},
) : CommandClassifier {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun classify(transcript: String): ReviewCommand {
        val text = transcript.trim()
        if (apiKey.isBlank() || text.isEmpty()) return ReviewCommand.Unknown(transcript)

        cache?.get(text)?.let { label ->
            onLog("[intent] cache hit -> $label")
            return commandForLabel(label, transcript)
        }

        val label = try {
            withContext(Dispatchers.IO) { callApi(text) }
        } catch (t: Throwable) {
            onLog("[intent] error, treating as Unknown: ${t.message}")
            return ReviewCommand.Unknown(transcript)
        }

        cache?.put(text, label)
        onLog("[intent] api -> $label")
        return commandForLabel(label, transcript)
    }

    private fun callApi(transcript: String): String {
        val body = buildRequestBody(transcript).toRequestBody(JSON_MEDIA)
        val request = Request.Builder()
            .url(API_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("Claude API HTTP ${response.code}: ${raw.take(500)}")
            }
            return parseLabel(raw)
        }
    }

    /** Builds the Anthropic messages request. Internal for pure unit testing. */
    internal fun buildRequestBody(transcript: String): String {
        val root = JSONObject().apply {
            put("model", MODEL)
            // A single label token is all we want back.
            put("max_tokens", 16)

            val systemArr = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", SYSTEM_PROMPT)
                        put("cache_control", JSONObject().apply { put("type", "ephemeral") })
                    },
                )
            }
            put("system", systemArr)

            val messagesArr = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", transcript)
                    },
                )
            }
            put("messages", messagesArr)
        }
        return root.toString()
    }

    companion object {
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private val JSON_MEDIA = "application/json".toMediaType()

        // Intent labels the model is allowed to emit.
        const val LABEL_AGAIN = "again"
        const val LABEL_HARD = "hard"
        const val LABEL_GOOD = "good"
        const val LABEL_EASY = "easy"
        const val LABEL_ANSWER = "answer"
        const val LABEL_REPEAT_QUESTION = "repeat_question"
        const val LABEL_REPEAT_EQUATION = "repeat_equation"
        const val LABEL_STOP = "stop"
        const val LABEL_UNKNOWN = "unknown"

        /**
         * Extracts the model's text and normalizes it to a bare label token.
         * Internal for pure unit testing (no network). Throws on malformed JSON.
         */
        internal fun parseLabel(rawJson: String): String {
            val obj = JSONObject(rawJson)
            val contentArr = obj.optJSONArray("content")
                ?: throw IOException("Claude API response missing 'content': ${rawJson.take(500)}")
            val first = contentArr.optJSONObject(0)
                ?: throw IOException("Claude API content empty: ${rawJson.take(500)}")
            return normalizeLabel(first.getString("text"))
        }

        /** Lowercase, trim, keep only [a-z_] so "Easy." -> "easy". */
        internal fun normalizeLabel(text: String): String =
            text.lowercase().trim().replace(Regex("[^a-z_]"), "")

        /**
         * Maps a normalized intent label back to a [ReviewCommand]. Off-vocabulary
         * labels (including the explicit "unknown") map to
         * [ReviewCommand.Unknown] carrying the original transcript.
         */
        fun commandForLabel(label: String, transcript: String): ReviewCommand =
            when (normalizeLabel(label)) {
                LABEL_AGAIN -> ReviewCommand.Grade(AnkiContract.Ease.AGAIN, "Again")
                LABEL_HARD -> ReviewCommand.Grade(AnkiContract.Ease.HARD, "Hard")
                LABEL_GOOD -> ReviewCommand.Grade(AnkiContract.Ease.GOOD, "Good")
                LABEL_EASY -> ReviewCommand.Grade(AnkiContract.Ease.EASY, "Easy")
                LABEL_ANSWER -> ReviewCommand.Answer
                LABEL_REPEAT_QUESTION -> ReviewCommand.RepeatQuestion
                LABEL_REPEAT_EQUATION -> ReviewCommand.RepeatEquation
                LABEL_STOP -> ReviewCommand.Stop
                else -> ReviewCommand.Unknown(transcript)
            }

        private val SYSTEM_PROMPT = """
            You classify a single short voice transcript spoken by someone doing a hands-free Anki flashcard review. They have just been asked "How did you do?" and may answer with a self-grade or a control command, phrased loosely and naturally.

            Output EXACTLY ONE of these labels and nothing else (no punctuation, no explanation):

              again            - they did badly / want to see this card again soon ("nope", "missed it", "no idea", "got it wrong")
              hard             - they got it but it was difficult ("tough", "barely", "that was rough")
              good             - they got it, normal effort ("yeah", "got it", "fine", "okay")
              easy             - they got it trivially ("too easy", "nailed it", "piece of cake")
              repeat_question  - re-read the question / card front ("say it again", "what was the question", "go back", "repeat that")
              repeat_equation  - re-read just the math / formula ("read the equation again", "the formula", "say the math part")
              answer           - reveal / read the answer ("show me", "what's the answer", "reveal", "I give up")
              stop             - end the review session ("I'm done", "quit", "that's enough", "exit")
              unknown          - none of the above, or genuinely unclear

            Rules:
            - Prefer a grade (again/hard/good/easy) when they describe how they did.
            - Prefer a control command (repeat_question/repeat_equation/answer/stop) when they ask for an action.
            - When truly ambiguous, output "unknown". Do not guess wildly.
            - Output only the bare label token.
        """.trimIndent()
    }
}
