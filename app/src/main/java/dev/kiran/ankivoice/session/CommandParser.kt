package dev.kiran.ankivoice.session

import dev.kiran.ankivoice.anki.AnkiContract

/**
 * A voice command the user can issue during a hands-free review, parsed from
 * a raw STT transcript.
 */
sealed interface ReviewCommand {
    /** Grade the current card. [ease] maps to [AnkiContract.Ease]. */
    data class Grade(val ease: Int, val label: String) : ReviewCommand
    /** Re-read the full question (card front). */
    data object RepeatQuestion : ReviewCommand
    /** Re-read only the math portion(s) of the current card. */
    data object RepeatEquation : ReviewCommand
    /** Read the answer (card back). */
    data object Answer : ReviewCommand
    /** End the session. */
    data object Stop : ReviewCommand
    /** Transcript matched no known command. [raw] is the original text. */
    data class Unknown(val raw: String) : ReviewCommand
}

/**
 * Keyword-first command parser. Pure Kotlin, case-insensitive, tolerant of
 * extra surrounding words (substring / token match). LLM intent classification
 * for loose phrasings ("say it again", "go back") is deliberately deferred --
 * see TODO.md.
 *
 * Match order matters where keywords overlap. "repeat the answer" contains both
 * "repeat" and "answer", so [Answer] is checked before [RepeatQuestion].
 * Likewise "repeat the equation" is caught by the equation rule before the
 * generic repeat rule.
 */
object CommandParser {

    fun parse(transcript: String): ReviewCommand {
        val raw = transcript.trim()
        if (raw.isEmpty()) return ReviewCommand.Unknown(raw)

        val text = raw.lowercase()
        // Tokenize on non-alphanumerics so digit synonyms match standalone
        // (so "10" does not match "1").
        val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

        fun has(word: String) = text.contains(word)
        fun token(t: String) = tokens.contains(t)

        return when {
            has("stop") -> ReviewCommand.Stop
            has("answer") -> ReviewCommand.Answer
            has("equation") || has("formula") -> ReviewCommand.RepeatEquation
            has("question") || has("repeat") -> ReviewCommand.RepeatQuestion
            has("again") || token("1") -> ReviewCommand.Grade(AnkiContract.Ease.AGAIN, "Again")
            has("hard") || token("2") -> ReviewCommand.Grade(AnkiContract.Ease.HARD, "Hard")
            has("good") || token("3") -> ReviewCommand.Grade(AnkiContract.Ease.GOOD, "Good")
            has("easy") || token("4") -> ReviewCommand.Grade(AnkiContract.Ease.EASY, "Easy")
            else -> ReviewCommand.Unknown(raw)
        }
    }
}
