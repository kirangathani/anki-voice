package dev.kiran.ankivoice.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tier-1 unit test: pure JVM, no Android, no emulator. Proves the test loop
 * works in CI and pins [TtsText]'s documented pacing rules.
 */
class TtsTextTest {

    @Test
    fun splitsAtSentencePunctuationFollowedByWhitespace() {
        assertEquals(
            listOf("One.", "Two?", "Three!", "Done."),
            TtsText.splitAtPausePoints("One. Two? Three! Done."),
        )
    }

    @Test
    fun splitsAtColonAndSemicolon() {
        assertEquals(
            listOf("A:", "B;", "C"),
            TtsText.splitAtPausePoints("A: B; C"),
        )
    }

    @Test
    fun splitsAtRunsOfNewlines() {
        assertEquals(
            listOf("line1", "line2", "line3"),
            TtsText.splitAtPausePoints("line1\n\nline2\nline3"),
        )
    }

    @Test
    fun doesNotSplitDecimalWithNoTrailingWhitespace() {
        // The period in "3.14" is followed by a digit, not whitespace.
        assertEquals(
            listOf("pi is 3.14 approx"),
            TtsText.splitAtPausePoints("pi is 3.14 approx"),
        )
    }

    @Test
    fun dropsBlankParts() {
        assertEquals(
            listOf("Hello.", "World."),
            TtsText.splitAtPausePoints("Hello.   \n  World."),
        )
    }

    @Test
    fun stripMarkersRemovesMathTokensAndTrims() {
        assertEquals(
            "x squared",
            TtsText.stripMarkers("[[MATH_START]] x squared [[MATH_END]]"),
        )
    }
}
