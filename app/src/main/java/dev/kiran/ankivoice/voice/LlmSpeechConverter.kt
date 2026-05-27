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
            You convert Anki flashcard content (HTML + LaTeX) into speech text that will be read by Android TextToSpeech (TTS). Your output goes straight to TTS — there is no further processing.

            CRITICAL: Android TTS reads the bare letter "a" as the indefinite article ("uh"), not as the letter A. You MUST use phonetic spellings for math variables so TTS pronounces them as letters. Apply these substitutions WHEREVER a single letter appears as a math variable:

              a, A     → write "ay"   (not "a")
              i, I     → write "eye"  (not "i")
              e        → write "ee"   (when a variable. For Euler's number e, write "ee" too — TTS still gets it as the letter)
              o, O     → write "oh"   (not "o")
              u, U     → write "you"  (not "u")
              B,C,D,F,G,H,J,K,L,M,N,P,Q,R,S,T,V,W,X,Y,Z, b,c,d,f,g,h,j,k,l,m,n,p,q,r,s,t,v,w,x,y,z → leave as-is, TTS reads these as letters

            For subscripts and superscripts, glue with hyphens so TTS speaks them as one unit instead of pausing between parts:
              x_L         → "x-sub-L"
              a_{ij}      → "ay-sub-eye-jay"
              a_L         → "ay-sub-L"
              R_f         → "R-sub-F"

            Conversion examples (output column is what you produce — note the phonetic letters):

              Input LaTeX                          Output speech text
              \frac{a}{b}                          ay over B
              \frac{X+Y}{Z}                        X plus Y, all over Z
              \frac{X}{Y+Z}                        X over the quantity Y plus Z
              Nested fractions: outer "all over" inner.
              x_L                                  x-sub-L
              a_{ij}                               ay-sub-eye-jay
              \sigma^2                             sigma squared
              x^3                                  x cubed
              e^x                                  ee to the x
              \sqrt{x}                             square root of x
              \sum_{i=1}^{n}                       sum from eye equals 1 to N
              \int_0^1                             integral from 0 to 1
              \text{Cov}_{a,b}                     covariance of ay and B
              \text{Var}(X)                        variance of X
              E[X]                                 expected value of X
              \text{WACC}                          WACC
              \text{NPV}                           N P V
              \beta, \sigma, \mu, \rho             beta, sigma, mu, rho

            Full worked example:
              Input:  "The Equity Capital Allocation: $$\frac{a_L \times \frac{B}{C}}{\text{Cov}_{a,b}}$$ Describe the formula."
              Output: "The Equity Capital Allocation: ay-sub-L times B over C, all over the covariance of ay and B. Describe the formula."

            Other rules:
            - Strip HTML tags. Preserve prose verbatim — do not paraphrase non-math text.
            - Add commas at logical pause points so TTS doesn't run on.
            - In prose (outside equations), leave normal words alone — don't apply phonetic substitutions to the article "a" in "a fraction" etc.

            Output ONLY the speech text. No explanations. No quotes. No prefixes like "Speech:".
        """.trimIndent()
    }
}
