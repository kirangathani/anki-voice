package dev.kiran.ankivoice.voice

import com.google.truth.Truth.assertThat
import org.junit.Test

/**
 * Tier-1 unit test: pure JVM, no Android, no emulator. Proves the test loop
 * works on the autonomous host and pins [TtsText]'s documented pacing rules.
 */
class TtsTextTest {

    @Test
    fun splitsAtSentencePunctuationFollowedByWhitespace() {
        assertThat(TtsText.splitAtPausePoints("One. Two? Three! Done."))
            .containsExactly("One.", "Two?", "Three!", "Done.").inOrder()
    }

    @Test
    fun splitsAtColonAndSemicolon() {
        assertThat(TtsText.splitAtPausePoints("A: B; C"))
            .containsExactly("A:", "B;", "C").inOrder()
    }

    @Test
    fun splitsAtRunsOfNewlines() {
        assertThat(TtsText.splitAtPausePoints("line1\n\nline2\nline3"))
            .containsExactly("line1", "line2", "line3").inOrder()
    }

    @Test
    fun doesNotSplitDecimalWithNoTrailingWhitespace() {
        // The period in "3.14" is followed by a digit, not whitespace.
        assertThat(TtsText.splitAtPausePoints("pi is 3.14 approx"))
            .containsExactly("pi is 3.14 approx").inOrder()
    }

    @Test
    fun dropsBlankParts() {
        assertThat(TtsText.splitAtPausePoints("Hello.   \n  World."))
            .containsExactly("Hello.", "World.").inOrder()
    }

    @Test
    fun stripMarkersRemovesMathTokensAndTrims() {
        assertThat(TtsText.stripMarkers("[[MATH_START]] x squared [[MATH_END]]"))
            .isEqualTo("x squared")
    }
}
