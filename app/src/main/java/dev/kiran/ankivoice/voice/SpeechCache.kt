package dev.kiran.ankivoice.voice

import android.content.Context
import java.security.MessageDigest

/**
 * Persistent cache for LLM-converted speech text. Keyed by SHA-256 of the
 * input HTML so the same card never hits the API twice across reviews.
 *
 * Backed by SharedPreferences — fine for tens of thousands of cards, no
 * separate DB needed.
 */
class SpeechCache(context: Context) {
    // Bump the version suffix any time the LLM prompt changes — the old
    // cache becomes irrelevant and we want fresh API calls with the new prompt.
    private val prefs = context.applicationContext.getSharedPreferences(
        "llm_speech_cache_v2",
        Context.MODE_PRIVATE,
    )

    fun get(input: String): String? = prefs.getString(hashKey(input), null)

    fun put(input: String, speech: String) {
        prefs.edit().putString(hashKey(input), speech).apply()
    }

    fun size(): Int = prefs.all.size

    fun clear() { prefs.edit().clear().apply() }

    private fun hashKey(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
