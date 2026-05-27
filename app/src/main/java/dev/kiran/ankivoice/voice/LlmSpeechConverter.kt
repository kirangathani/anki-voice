package dev.kiran.ankivoice.voice

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
 * Converts raw Anki card HTML (including LaTeX equations) to natural English
 * speech text via Claude Haiku 4.5. Results are cached so the same card never
 * hits the API twice.
 *
 * Uses Anthropic's prompt caching on the system block — the system prompt is
 * static across all card calls, so we get ~90% discount on cached tokens.
 *
 * Errors propagate up; caller decides whether to fall back to non-LLM pipeline.
 */
class LlmSpeechConverter(
    private val apiKey: String,
    private val cache: SpeechCache,
    private val onLog: (String) -> Unit = {},
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun convert(cardHtml: String): String {
        if (cardHtml.isBlank()) return ""
        cache.get(cardHtml)?.let { hit ->
            onLog("[llm] cache hit (${hit.length} chars)")
            return hit
        }
        val result = withContext(Dispatchers.IO) { callApi(cardHtml) }
        cache.put(cardHtml, result)
        onLog("[llm] api → ${result.length} chars, cached")
        return result
    }

    private fun callApi(cardHtml: String): String {
        val body = buildRequestBody(cardHtml).toRequestBody(JSON_MEDIA)
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
            val obj = JSONObject(raw)
            val contentArr = obj.optJSONArray("content")
                ?: throw IOException("Claude API response missing 'content': ${raw.take(500)}")
            // Cached-tokens telemetry for debugging cost.
            obj.optJSONObject("usage")?.let { usage ->
                onLog(
                    "[llm] usage in=${usage.optInt("input_tokens")} " +
                        "cached_read=${usage.optInt("cache_read_input_tokens")} " +
                        "cached_write=${usage.optInt("cache_creation_input_tokens")} " +
                        "out=${usage.optInt("output_tokens")}",
                )
            }
            val first = contentArr.optJSONObject(0)
                ?: throw IOException("Claude API content empty: ${raw.take(500)}")
            return first.getString("text").trim()
        }
    }

    private fun buildRequestBody(cardHtml: String): String {
        val root = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 1024)

            // System prompt as a cacheable block so subsequent calls within ~5min
            // reuse the cached prefix at 0.1x cost.
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
                        put("content", cardHtml)
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

        private val SYSTEM_PROMPT = """
            You convert Anki flashcard content (HTML + LaTeX) into speech text that goes straight to Android TextToSpeech (TTS). No further processing.

            Two TTS problems you must design around:
            1. TTS reads the bare letter "a" as the article ("uh") — never as the letter A.
            2. TTS races through text without punctuation, making equations unintelligible.

            FIX BOTH WITH "PERIOD-PADDED LETTERS". Inside equations, write every single math variable letter as the letter followed by a period. The period forces TTS to:
               (a) treat the character as a stand-alone item (so it pronounces "A" instead of "uh"), and
               (b) pause briefly after it (so the equation reads at a deliberate pace).

            DO NOT use the word "sub" or "subscript". DO NOT use hyphens between subscripted letters. Just period-separate them.

            Period-padding examples (output column is exactly what you write):

              Input LaTeX        Output
              a                  A.
              a_L                A. L.
              R_f                R. F.
              a_{ij}             A. I. J.
              x_t                X. T.
              y_{t+1}            Y. T plus 1.

            For superscripts/powers, use natural English (no period-padding needed):

              x^2                X. squared
              e^x                E. to the X.
              x^n                X. to the N.

            For fractions, use "over" or "all over":

              \frac{a}{b}                    A. over B.
              \frac{X+Y}{Z}                  X plus Y, all over Z.
              \frac{X}{Y+Z}                  X over the quantity Y plus Z.
              \frac{a_L}{\text{Cov}_{a,b}}   A. L. all over the covariance of A. and B.

            Common finance / Greek notation:

              \text{Cov}_{a,b}      the covariance of A. and B.
              \text{Var}(X)         the variance of X.
              E[X]                  the expected value of X.
              \sigma^2              sigma squared
              \beta                 beta
              \mu, \rho, \pi        mu, rho, pi
              \text{WACC}           WACC
              \text{NPV}            N. P. V.
              \sqrt{x}              square root of X.
              \sum_{i=1}^{n}        the sum from I. equals 1 to N.

            Full worked example (this is exactly the output style we want):

              Input:  "The Equity Capital Allocation: $$\frac{a_L \times \frac{B}{C}}{\text{Cov}_{a,b}}$$ Describe the formula."
              Output: "The Equity Capital Allocation. A. L. times B. over C. All over the covariance of A. and B. Describe the formula."

            Notice in the example:
            - Every math letter has a trailing period.
            - "sub" never appears — subscripts are just two period-padded letters next to each other.
            - Long phrases broken by periods at logical pause points.
            - Prose ("The Equity Capital Allocation", "Describe the formula") preserved verbatim, NOT period-padded — only equation letters get the treatment.

            PROSE RULES:
            - Strip HTML tags. Preserve prose verbatim.
            - The article "a" in prose ("a fraction", "a variable") stays as "a" — NO period-padding for normal English words.
            - Only apply period-padded letters inside what was a LaTeX equation.

            PACE:
            - Assume the listener is processing math while walking. Better too slow than too fast.
            - Use periods aggressively inside equations to force pauses. Don't worry about sounding "robotic" — pace matters more.

            Output ONLY the speech text. No explanations. No quotes. No prefixes like "Speech:" or "Output:".
        """.trimIndent()
    }
}
