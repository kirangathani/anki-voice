package dev.kiran.ankivoice.session

import dev.kiran.ankivoice.anki.AnkiContract
import dev.kiran.ankivoice.anki.DueCard
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSessionTest {

    // -- Fakes --

    private class FakeSpeaker : Speaker {
        val spoken = mutableListOf<String>()
        override suspend fun speak(text: String) { spoken += text }
        override fun stop() {}
    }

    private class FakeListener(private val responses: List<Listener.Result>) : Listener {
        private var index = 0
        override suspend fun listen(wakeWords: List<String>): Listener.Result {
            check(index < responses.size) { "FakeListener exhausted at call $index" }
            return responses[index++]
        }
    }

    private class FakeCardSource(cards: List<DueCard>) : CardSource {
        private val queue = ArrayDeque(cards)
        val reviews = mutableListOf<Triple<DueCard, Int, Long>>()
        var syncRequested = false

        override suspend fun nextDueCard(deckId: Long): DueCard? = queue.removeFirstOrNull()
        override fun submitReview(card: DueCard, ease: Int, timeTakenMs: Long) {
            reviews += Triple(card, ease, timeTakenMs)
        }
        override fun requestSync() { syncRequested = true }
        // Mirrors AnkiRepository.generateSpeech with an identifiable marker so
        // tests can assert the equation re-read path went through it.
        override suspend fun generateSpeech(rawHtml: String): String = "SPEECH($rawHtml)"
    }

    // -- Helpers --

    private fun card(q: String = "What is 2+2?", a: String = "4") = DueCard(
        noteId = 1L,
        cardOrd = 0,
        buttonCount = 4,
        displayHtmlQuestion = q,
        displayHtmlAnswer = a,
        speechQuestion = q,
        speechAnswer = a,
    )

    private fun recognized(text: String) = Listener.Result.Recognized(text)

    // -- Tests --

    @Test
    fun fullReviewOneCard_gradeGood() = runBlocking {
        val c = card()
        val speaker = FakeSpeaker()
        val listener = FakeListener(listOf(
            recognized("blah blah execute"),  // AwaitingAnswer
            recognized("good"),               // AwaitingCommand
        ))
        val source = FakeCardSource(listOf(c))
        val logs = mutableListOf<String>()

        ReviewSession(speaker, listener, source) { logs += it }.run(1L)

        // Question was spoken
        assertTrue("question spoken", speaker.spoken.contains(c.speechQuestion))
        // Grade prompt was spoken
        assertTrue("grade prompt spoken", speaker.spoken.contains("How did you do?"))
        // Grade submitted
        assertEquals(1, source.reviews.size)
        assertEquals(AnkiContract.Ease.GOOD, source.reviews[0].second)
        // Sync fired once
        assertTrue("sync requested", source.syncRequested)
        // Session end announced
        assertTrue("end announced", speaker.spoken.any { it.contains("No more cards") })
        // Log trace
        assertTrue("log SpeakingQuestion", logs.any { it.contains("SpeakingQuestion") })
        assertTrue("log submitted", logs.any { it.contains("submitted Good") })
        assertTrue("log finished", logs.any { it.contains("finished") })
    }

    @Test
    fun repeatCommand_reSpeaksQuestion() = runBlocking {
        val c = card()
        val speaker = FakeSpeaker()
        // repeat re-speaks question then loops back to PromptingForGrade,
        // so the next listen call is AwaitingCommand (not AwaitingAnswer).
        val listener = FakeListener(listOf(
            recognized("execute"),   // AwaitingAnswer
            recognized("repeat"),    // AwaitingCommand -> repeat
            recognized("easy"),      // AwaitingCommand -> grade
        ))
        val source = FakeCardSource(listOf(c))

        ReviewSession(speaker, listener, source).run(1L)

        // Question spoken twice: once initially, once on repeat
        val qCount = speaker.spoken.count { it == c.speechQuestion }
        assertEquals("question spoken twice", 2, qCount)
        // Grade submitted as Easy
        assertEquals(AnkiContract.Ease.EASY, source.reviews[0].second)
    }

    @Test
    fun repeatEquationCommand_speaksOnlyMath() = runBlocking {
        val c = card(q = "Compute the ratio \$\$\\frac{a}{b}\$\$ now.", a = "done")
        val speaker = FakeSpeaker()
        val listener = FakeListener(listOf(
            recognized("execute"),    // AwaitingAnswer
            recognized("equation"),   // AwaitingCommand -> repeat equation
            recognized("good"),       // AwaitingCommand -> grade
        ))
        val source = FakeCardSource(listOf(c))

        ReviewSession(speaker, listener, source).run(1L)

        // The math segment was re-wrapped and run through generateSpeech, then spoken.
        assertTrue(
            "math speech spoken",
            speaker.spoken.contains("SPEECH(\$\$\\frac{a}{b}\$\$)"),
        )
        // The surrounding prose was NOT spoken on the repeat-equation path.
        assertTrue(
            "prose question not re-spoken on equation repeat",
            speaker.spoken.count { it == c.speechQuestion } == 1,
        )
        assertEquals(AnkiContract.Ease.GOOD, source.reviews[0].second)
    }

    @Test
    fun repeatEquationCommand_noMath_fallsBackToQuestion() = runBlocking {
        val c = card(q = "What is the capital of France?", a = "Paris")
        val speaker = FakeSpeaker()
        val listener = FakeListener(listOf(
            recognized("execute"),    // AwaitingAnswer
            recognized("equation"),   // AwaitingCommand -> repeat equation (no math)
            recognized("easy"),       // AwaitingCommand -> grade
        ))
        val source = FakeCardSource(listOf(c))
        val logs = mutableListOf<String>()

        ReviewSession(speaker, listener, source) { logs += it }.run(1L)

        // No math -> falls back to repeating the question (spoken twice total).
        assertEquals("question spoken twice", 2, speaker.spoken.count { it == c.speechQuestion })
        assertTrue("log fallback", logs.any { it.contains("no equation on card, repeating question") })
        assertEquals(AnkiContract.Ease.EASY, source.reviews[0].second)
    }

    @Test
    fun answerCommand_speaksAnswerThenGrades() = runBlocking {
        val c = card()
        val speaker = FakeSpeaker()
        val listener = FakeListener(listOf(
            recognized("execute"),   // AwaitingAnswer
            recognized("answer"),    // AwaitingCommand -> speak answer
            recognized("hard"),      // AwaitingCommand -> grade
        ))
        val source = FakeCardSource(listOf(c))

        ReviewSession(speaker, listener, source).run(1L)

        // Answer was spoken
        assertTrue("answer spoken", speaker.spoken.contains(c.speechAnswer))
        // Grade submitted as Hard
        assertEquals(AnkiContract.Ease.HARD, source.reviews[0].second)
    }

    @Test
    fun stopCommand_endsSession() = runBlocking {
        val c = card()
        val speaker = FakeSpeaker()
        val listener = FakeListener(listOf(
            recognized("execute"),   // AwaitingAnswer
            recognized("stop"),      // AwaitingCommand -> stop
        ))
        val source = FakeCardSource(listOf(c))

        ReviewSession(speaker, listener, source).run(1L)

        // No grade submitted
        assertTrue("no reviews", source.reviews.isEmpty())
        // No sync when nothing was graded
        assertTrue("no sync when stopped without grading", !source.syncRequested)
    }

    @Test
    fun emptyDeck_endsGracefully() = runBlocking {
        val speaker = FakeSpeaker()
        val listener = FakeListener(emptyList())
        val source = FakeCardSource(emptyList())

        ReviewSession(speaker, listener, source).run(1L)

        assertTrue("end announced", speaker.spoken.any { it.contains("No more cards") })
        // No sync when nothing was reviewed
        assertTrue("no sync for empty deck", !source.syncRequested)
    }

    @Test
    fun twoCards_bothReviewed() = runBlocking {
        val c1 = card(q = "Q1", a = "A1")
        val c2 = card(q = "Q2", a = "A2")
        val speaker = FakeSpeaker()
        val listener = FakeListener(listOf(
            recognized("execute"),   // card 1: AwaitingAnswer
            recognized("again"),     // card 1: AwaitingCommand
            recognized("execute"),   // card 2: AwaitingAnswer
            recognized("good"),      // card 2: AwaitingCommand
        ))
        val source = FakeCardSource(listOf(c1, c2))

        ReviewSession(speaker, listener, source).run(1L)

        assertEquals(2, source.reviews.size)
        assertEquals(AnkiContract.Ease.AGAIN, source.reviews[0].second)
        assertEquals(AnkiContract.Ease.GOOD, source.reviews[1].second)
        assertTrue(source.syncRequested)
    }

    @Test
    fun numericGrades_parsedCorrectly() = runBlocking {
        val c = card()
        val speaker = FakeSpeaker()
        val listener = FakeListener(listOf(
            recognized("execute"),
            recognized("2"),         // "2" -> Hard
        ))
        val source = FakeCardSource(listOf(c))

        ReviewSession(speaker, listener, source).run(1L)

        assertEquals(AnkiContract.Ease.HARD, source.reviews[0].second)
    }

    @Test
    fun unrecognizedCommand_promptsRetry() = runBlocking {
        val c = card()
        val speaker = FakeSpeaker()
        val listener = FakeListener(listOf(
            recognized("execute"),
            recognized("banana"),    // unknown
            recognized("good"),      // valid grade
        ))
        val source = FakeCardSource(listOf(c))
        val logs = mutableListOf<String>()

        ReviewSession(speaker, listener, source) { logs += it }.run(1L)

        assertTrue("error prompt spoken", speaker.spoken.any { it.contains("didn't understand") })
        assertTrue("log unrecognized", logs.any { it.contains("unrecognized") })
        assertEquals(AnkiContract.Ease.GOOD, source.reviews[0].second)
    }

    @Test
    fun noMatch_treatedAsUnknown() = runBlocking {
        val c = card()
        val speaker = FakeSpeaker()
        val listener = FakeListener(listOf(
            recognized("execute"),
            Listener.Result.NoMatch,  // no match
            recognized("easy"),
        ))
        val source = FakeCardSource(listOf(c))

        ReviewSession(speaker, listener, source).run(1L)

        assertTrue("retry prompt spoken", speaker.spoken.any { it.contains("didn't understand") })
        assertEquals(AnkiContract.Ease.EASY, source.reviews[0].second)
    }
}
