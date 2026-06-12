package dev.kiran.ankivoice.voice

/**
 * Pure, framework-free text helpers for TTS pacing. Kept separate from
 * [TtsEngine] (which needs an Android [android.content.Context]) so this logic
 * is unit-testable on the plain JVM. This is the pattern for the project: pull
 * pure logic out of Android-bound classes into objects/functions that tests can
 * exercise without an emulator or Robolectric.
 */
object TtsText {

    /** Internal pipeline marker tokens (e.g. from the SRE fallback path). */
    private val MATH_MARKER = Regex("""\s*\[\[MATH_(START|END)\]\]\s*""")

    /**
     * Pause points: any of `.` `?` `!` `:` `;` followed by whitespace, or any
     * run of newlines. The matched whitespace is the delimiter (consumed).
     */
    private val PAUSE_SPLIT = Regex("""(?<=[.?!:;])\s+|\n+""")

    /** Removes internal marker tokens, collapsing each to a single space, trimmed. */
    fun stripMarkers(text: String): String = text.replace(MATH_MARKER, " ").trim()

    /**
     * Splits [text] at pause points (see [PAUSE_SPLIT]); blank parts dropped.
     * A period with no trailing whitespace (e.g. "3.14") does NOT split.
     */
    fun splitAtPausePoints(text: String): List<String> =
        text.split(PAUSE_SPLIT).filter { it.isNotBlank() }
}
