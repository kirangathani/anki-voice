package dev.kiran.ankivoice.session

import android.content.Context
import java.security.MessageDigest

/**
 * Persistent cache for LLM-classified voice commands. Keyed by SHA-256 of the
 * (trimmed, lowercased) transcript so a repeated loose phrasing does not
 * re-call the API. Mirrors [dev.kiran.ankivoice.voice.SpeechCache].
 *
 * Stored values are the normalized intent label (e.g. "easy", "repeat_question",
 * "unknown"), not the full command object.
 */
class CommandCache(context: Context) {
    // Bump the version suffix any time the classifier prompt or label set
    // changes -- old labels become irrelevant.
    private val prefs = context.applicationContext.getSharedPreferences(
        "llm_command_cache_v1",
        Context.MODE_PRIVATE,
    )

    fun get(transcript: String): String? = prefs.getString(hashKey(transcript), null)

    fun put(transcript: String, label: String) {
        prefs.edit().putString(hashKey(transcript), label).apply()
    }

    fun size(): Int = prefs.all.size

    fun clear() { prefs.edit().clear().apply() }

    private fun hashKey(text: String): String {
        val normalized = text.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
