package dev.kiran.ankivoice.session

import dev.kiran.ankivoice.anki.AnkiContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-1 unit test: pure JVM, no Android. Pins [CommandParser]'s keyword-first
 * matching rules for the hands-free review vocabulary (TODO.md).
 */
class CommandParserTest {

    private fun ease(transcript: String): Int {
        val cmd = CommandParser.parse(transcript)
        assertTrue("expected Grade for '$transcript' but got $cmd", cmd is ReviewCommand.Grade)
        return (cmd as ReviewCommand.Grade).ease
    }

    @Test
    fun gradeKeywordsMapToEase() {
        assertEquals(AnkiContract.Ease.AGAIN, ease("again"))
        assertEquals(AnkiContract.Ease.HARD, ease("hard"))
        assertEquals(AnkiContract.Ease.GOOD, ease("good"))
        assertEquals(AnkiContract.Ease.EASY, ease("easy"))
    }

    @Test
    fun numericSynonymsMapToEase() {
        assertEquals(AnkiContract.Ease.AGAIN, ease("1"))
        assertEquals(AnkiContract.Ease.HARD, ease("2"))
        assertEquals(AnkiContract.Ease.GOOD, ease("3"))
        assertEquals(AnkiContract.Ease.EASY, ease("4"))
    }

    @Test
    fun multiDigitNumberIsNotAGradeSynonym() {
        // "10" must not be mistaken for the "1" synonym.
        assertEquals(ReviewCommand.Unknown("10"), CommandParser.parse("10"))
    }

    @Test
    fun bothAnswerPhrasingsResolveToAnswer() {
        assertEquals(ReviewCommand.Answer, CommandParser.parse("what was the answer?"))
        assertEquals(ReviewCommand.Answer, CommandParser.parse("repeat the answer"))
    }

    @Test
    fun repeatQuestionPhrasings() {
        assertEquals(ReviewCommand.RepeatQuestion, CommandParser.parse("repeat the question"))
        assertEquals(ReviewCommand.RepeatQuestion, CommandParser.parse("repeat"))
        assertEquals(ReviewCommand.RepeatQuestion, CommandParser.parse("question"))
    }

    @Test
    fun repeatEquationPhrasings() {
        assertEquals(ReviewCommand.RepeatEquation, CommandParser.parse("repeat the equation"))
        assertEquals(ReviewCommand.RepeatEquation, CommandParser.parse("equation"))
        assertEquals(ReviewCommand.RepeatEquation, CommandParser.parse("read the formula"))
    }

    @Test
    fun stopKeyword() {
        assertEquals(ReviewCommand.Stop, CommandParser.parse("stop"))
    }

    @Test
    fun isCaseInsensitive() {
        assertEquals(AnkiContract.Ease.EASY, ease("EASY"))
        assertEquals(AnkiContract.Ease.AGAIN, ease("Again"))
        assertEquals(ReviewCommand.Answer, CommandParser.parse("What Was The ANSWER?"))
        assertEquals(ReviewCommand.Stop, CommandParser.parse("STOP"))
    }

    @Test
    fun toleratesExtraSurroundingWords() {
        assertEquals(AnkiContract.Ease.HARD, ease("oh that was really hard for me"))
        assertEquals(AnkiContract.Ease.GOOD, ease("yeah I think good"))
        assertEquals(ReviewCommand.Answer, CommandParser.parse("hmm can you give me the answer please"))
        assertEquals(ReviewCommand.Stop, CommandParser.parse("ok let's stop now"))
    }

    @Test
    fun gibberishIsUnknown() {
        assertEquals(ReviewCommand.Unknown("asdf qwer"), CommandParser.parse("asdf qwer"))
    }

    @Test
    fun blankIsUnknown() {
        assertEquals(ReviewCommand.Unknown(""), CommandParser.parse("   "))
    }
}
