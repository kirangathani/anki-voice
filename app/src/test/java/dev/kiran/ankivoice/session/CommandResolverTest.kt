package dev.kiran.ankivoice.session

import dev.kiran.ankivoice.anki.AnkiContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-1 unit test: pure JVM, no Android, no network. Pins the two-tier
 * resolution contract of [CommandResolver] -- keyword first, LLM only on a
 * keyword miss -- using a fake [CommandClassifier].
 */
class CommandResolverTest {

    /** Records every transcript it was asked to classify; returns a canned result. */
    private class FakeClassifier(
        private val result: ReviewCommand,
    ) : CommandClassifier {
        val calls = mutableListOf<String>()
        override suspend fun classify(transcript: String): ReviewCommand {
            calls += transcript
            return result
        }
    }

    @Test
    fun keywordHit_neverCallsLlm() = runBlocking {
        val fake = FakeClassifier(ReviewCommand.Grade(AnkiContract.Ease.EASY, "Easy"))
        val resolver = CommandResolver(classifier = fake)

        assertEquals(
            ReviewCommand.Grade(AnkiContract.Ease.GOOD, "Good"),
            resolver.resolve("yeah I think good"),
        )
        assertEquals(ReviewCommand.Answer, resolver.resolve("give me the answer"))
        assertEquals(ReviewCommand.Stop, resolver.resolve("let's stop"))

        assertTrue("LLM must not be consulted on keyword hits", fake.calls.isEmpty())
    }

    @Test
    fun keywordMiss_fallsThroughToLlm() = runBlocking {
        val fake = FakeClassifier(ReviewCommand.Grade(AnkiContract.Ease.EASY, "Easy"))
        val resolver = CommandResolver(classifier = fake)

        val cmd = resolver.resolve("yeah okay that one was a piece of cake")

        assertEquals(ReviewCommand.Grade(AnkiContract.Ease.EASY, "Easy"), cmd)
        assertEquals(1, fake.calls.size)
        assertEquals("yeah okay that one was a piece of cake", fake.calls[0])
    }

    @Test
    fun llmUnknown_staysUnknown() = runBlocking {
        val fake = FakeClassifier(ReviewCommand.Unknown("nonsense words here"))
        val resolver = CommandResolver(classifier = fake)

        val cmd = resolver.resolve("nonsense words here")

        assertEquals(ReviewCommand.Unknown("nonsense words here"), cmd)
        assertEquals("LLM is consulted exactly once on a keyword miss", 1, fake.calls.size)
    }

    @Test
    fun noClassifier_keywordMissStaysUnknown() = runBlocking {
        val resolver = CommandResolver(classifier = null)

        assertEquals(
            ReviewCommand.Unknown("nonsense words here"),
            resolver.resolve("nonsense words here"),
        )
    }

    @Test
    fun blankTranscript_neverCallsLlm() = runBlocking {
        val fake = FakeClassifier(ReviewCommand.Stop)
        val resolver = CommandResolver(classifier = fake)

        assertEquals(ReviewCommand.Unknown(""), resolver.resolve("   "))
        assertTrue("blank input is not worth an LLM call", fake.calls.isEmpty())
    }

    @Test
    fun defaultResolver_isKeywordOnly() = runBlocking {
        // The no-arg default (used by ReviewSession when no key is set) must
        // preserve pure keyword behaviour.
        val resolver = CommandResolver()
        assertEquals(
            ReviewCommand.Grade(AnkiContract.Ease.HARD, "Hard"),
            resolver.resolve("that was hard"),
        )
        assertEquals(ReviewCommand.Unknown("blah blah"), resolver.resolve("blah blah"))
    }

    @Test
    fun keywordMiss_doesNotShortCircuitLlmResult() = runBlocking {
        // Sanity: a control-command intent comes back intact through the resolver.
        val fake = FakeClassifier(ReviewCommand.RepeatQuestion)
        val resolver = CommandResolver(classifier = fake)

        assertEquals(ReviewCommand.RepeatQuestion, resolver.resolve("uh say that one more time"))
        assertFalse(fake.calls.isEmpty())
    }
}
