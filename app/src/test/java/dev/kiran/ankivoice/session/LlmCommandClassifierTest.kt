package dev.kiran.ankivoice.session

import dev.kiran.ankivoice.anki.AnkiContract
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier-1 unit test: pure JVM, no network. Exercises [LlmCommandClassifier]'s
 * request building and response/label mapping in isolation (the same way
 * LlmSpeechConverter's pure logic can be checked without hitting the API).
 */
class LlmCommandClassifierTest {

    private val classifier = LlmCommandClassifier(apiKey = "test-key", cache = null)

    @Test
    fun buildRequestBody_hasModelCachedSystemAndUserTranscript() {
        val json = JSONObject(classifier.buildRequestBody("say it again"))

        assertEquals("claude-haiku-4-5-20251001", json.getString("model"))

        // System prompt is a cacheable block.
        val systemBlock = json.getJSONArray("system").getJSONObject(0)
        assertEquals("text", systemBlock.getString("type"))
        assertEquals("ephemeral", systemBlock.getJSONObject("cache_control").getString("type"))
        assertTrue(
            "system prompt enumerates the intent labels",
            systemBlock.getString("text").contains("repeat_question"),
        )

        // User message carries the raw transcript verbatim.
        val userMsg = json.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", userMsg.getString("role"))
        assertEquals("say it again", userMsg.getString("content"))
    }

    @Test
    fun parseLabel_extractsAndNormalizesModelText() {
        val raw = """{"content":[{"type":"text","text":"easy"}]}"""
        assertEquals("easy", LlmCommandClassifier.parseLabel(raw))

        // Stray punctuation / casing is normalized away.
        val noisy = """{"content":[{"type":"text","text":"  Repeat_Question.\n"}]}"""
        assertEquals("repeat_question", LlmCommandClassifier.parseLabel(noisy))
    }

    @Test
    fun normalizeLabel_stripsNonLabelChars() {
        assertEquals("good", LlmCommandClassifier.normalizeLabel("Good!"))
        assertEquals("stop", LlmCommandClassifier.normalizeLabel("  STOP  "))
        assertEquals("repeat_equation", LlmCommandClassifier.normalizeLabel("repeat_equation\n"))
    }

    @Test
    fun commandForLabel_mapsEveryGradeLabel() {
        assertEquals(
            ReviewCommand.Grade(AnkiContract.Ease.AGAIN, "Again"),
            LlmCommandClassifier.commandForLabel("again", "nope"),
        )
        assertEquals(
            ReviewCommand.Grade(AnkiContract.Ease.HARD, "Hard"),
            LlmCommandClassifier.commandForLabel("hard", "tough"),
        )
        assertEquals(
            ReviewCommand.Grade(AnkiContract.Ease.GOOD, "Good"),
            LlmCommandClassifier.commandForLabel("good", "got it"),
        )
        assertEquals(
            ReviewCommand.Grade(AnkiContract.Ease.EASY, "Easy"),
            LlmCommandClassifier.commandForLabel("easy", "nailed it"),
        )
    }

    @Test
    fun commandForLabel_mapsControlCommands() {
        assertEquals(
            ReviewCommand.Answer,
            LlmCommandClassifier.commandForLabel("answer", "show me"),
        )
        assertEquals(
            ReviewCommand.RepeatQuestion,
            LlmCommandClassifier.commandForLabel("repeat_question", "go back"),
        )
        assertEquals(
            ReviewCommand.RepeatEquation,
            LlmCommandClassifier.commandForLabel("repeat_equation", "the formula"),
        )
        assertEquals(
            ReviewCommand.Stop,
            LlmCommandClassifier.commandForLabel("stop", "I'm done"),
        )
    }

    @Test
    fun commandForLabel_offVocabularyIsUnknownWithTranscript() {
        assertEquals(
            ReviewCommand.Unknown("weird input"),
            LlmCommandClassifier.commandForLabel("unknown", "weird input"),
        )
        assertEquals(
            ReviewCommand.Unknown("weird input"),
            LlmCommandClassifier.commandForLabel("banana", "weird input"),
        )
    }

    @Test
    fun classify_emptyKeyIsOfflineSafeUnknown() = runBlocking {
        val disabled = LlmCommandClassifier(apiKey = "", cache = null)
        assertEquals(
            ReviewCommand.Unknown("say it again"),
            disabled.classify("say it again"),
        )
    }

    @Test
    fun classify_blankTranscriptIsUnknownWithoutNetwork() = runBlocking {
        // Non-empty key but blank transcript must not attempt a network call.
        assertEquals(ReviewCommand.Unknown("   "), classifier.classify("   "))
    }
}
