package dev.kiran.ankivoice.session

import dev.kiran.ankivoice.anki.AnkiContract
import dev.kiran.ankivoice.anki.DueCard

/**
 * Port: something that can speak text aloud and block until done.
 * Implemented by TtsEngine in production.
 */
interface Speaker {
    suspend fun speak(text: String)
    fun stop()
}

/**
 * Port: something that can listen for speech and return a transcript.
 * Implemented by SttEngine in production.
 */
interface Listener {
    sealed class Result {
        data class Recognized(val transcript: String) : Result()
        data class Error(val code: Int, val message: String) : Result()
        data object NoMatch : Result()
    }

    suspend fun listen(wakeWords: List<String> = emptyList()): Result
}

/**
 * Port: card data source. Thin surface over AnkiRepository.
 */
interface CardSource {
    suspend fun nextDueCard(deckId: Long): DueCard?
    fun submitReview(card: DueCard, ease: Int, timeTakenMs: Long)
    fun requestSync()
}

/**
 * Hands-free review loop driven entirely by TTS and STT.
 *
 * Pure Kotlin, no android.* imports. Runs as a suspend function inside
 * whatever coroutine scope the caller provides.
 *
 * Flow per card:
 *  SpeakingQuestion -> AwaitingAnswer (wake word "execute") ->
 *  PromptingForGrade -> AwaitingCommand -> (grade | answer | repeat | stop)
 *
 * Fires requestSync once at session end, not per card.
 */
class ReviewSession(
    private val speaker: Speaker,
    private val listener: Listener,
    private val cardSource: CardSource,
    private val onLog: (String) -> Unit = {},
) {

    /** Visible state for UI observation. */
    enum class State {
        Idle,
        SpeakingQuestion,
        AwaitingAnswer,
        PromptingForGrade,
        AwaitingCommand,
        SpeakingAnswer,
        Finished,
    }

    /**
     * Runs a full review session for [deckId]. Returns when the deck is
     * exhausted or the user says "stop". Suspends for the entire duration.
     */
    suspend fun run(deckId: Long) {
        onLog("ReviewSession: started for deck $deckId")
        var gradedCount = 0

        while (true) {
            val card = cardSource.nextDueCard(deckId)
            if (card == null) {
                onLog("ReviewSession: no more due cards")
                speaker.speak("No more cards due. Session complete.")
                break
            }

            val graded = reviewCard(card)
            if (graded) gradedCount++ else break
        }

        if (gradedCount > 0) {
            cardSource.requestSync()
            onLog("ReviewSession: sync requested ($gradedCount graded)")
        }
        onLog("ReviewSession: finished")
    }

    /**
     * Reviews a single card through the full state cycle.
     * Returns true to continue to next card, false to end the session.
     */
    private suspend fun reviewCard(card: DueCard): Boolean {
        val cardStartMs = System.currentTimeMillis()

        // SpeakingQuestion
        onLog("ReviewSession: SpeakingQuestion")
        speaker.speak(card.speechQuestion)

        // AwaitingAnswer -- user thinks aloud, transcript discarded
        onLog("ReviewSession: AwaitingAnswer")
        listener.listen(wakeWords = listOf("execute"))
        // We discard the result; the wake word just ends listening.

        // Grade loop: prompt for grade, listen for command, handle it
        while (true) {
            // PromptingForGrade
            onLog("ReviewSession: PromptingForGrade")
            speaker.speak("How did you do?")

            // AwaitingCommand
            onLog("ReviewSession: AwaitingCommand")
            val result = listener.listen()

            when (val command = parseCommand(result)) {
                is Command.Grade -> {
                    val timeMs = System.currentTimeMillis() - cardStartMs
                    cardSource.submitReview(card, command.ease, timeMs)
                    onLog("ReviewSession: submitted ${command.label}")
                    return true // next card
                }
                is Command.Answer -> {
                    onLog("ReviewSession: SpeakingAnswer")
                    speaker.speak(card.speechAnswer)
                    // Loop back to PromptingForGrade
                }
                is Command.Repeat -> {
                    onLog("ReviewSession: repeating question")
                    speaker.speak(card.speechQuestion)
                    // Loop back to PromptingForGrade
                }
                is Command.Stop -> {
                    onLog("ReviewSession: user stopped")
                    return false
                }
                is Command.Unknown -> {
                    onLog("ReviewSession: unrecognized command '${command.raw}'")
                    speaker.speak("Sorry, I didn't understand. Say again, hard, good, easy, answer, repeat, or stop.")
                    // Loop back to PromptingForGrade
                }
            }
        }
    }

    private sealed class Command {
        data class Grade(val ease: Int, val label: String) : Command()
        data object Answer : Command()
        data object Repeat : Command()
        data object Stop : Command()
        data class Unknown(val raw: String) : Command()
    }

    private fun parseCommand(result: Listener.Result): Command {
        val transcript = when (result) {
            is Listener.Result.Recognized -> result.transcript.trim().lowercase()
            is Listener.Result.NoMatch -> return Command.Unknown("")
            is Listener.Result.Error -> return Command.Unknown("error:${result.code}")
        }

        return when {
            transcript.contains("again") || transcript == "1" ->
                Command.Grade(AnkiContract.Ease.AGAIN, "Again")
            transcript.contains("hard") || transcript == "2" ->
                Command.Grade(AnkiContract.Ease.HARD, "Hard")
            transcript.contains("good") || transcript == "3" ->
                Command.Grade(AnkiContract.Ease.GOOD, "Good")
            transcript.contains("easy") || transcript == "4" ->
                Command.Grade(AnkiContract.Ease.EASY, "Easy")
            transcript.contains("answer") -> Command.Answer
            transcript.contains("repeat") -> Command.Repeat
            transcript.contains("stop") -> Command.Stop
            else -> Command.Unknown(transcript)
        }
    }
}
