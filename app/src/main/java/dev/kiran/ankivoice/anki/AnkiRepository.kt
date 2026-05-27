package dev.kiran.ankivoice.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import dev.kiran.ankivoice.math.MathPipeline

data class Deck(
    val id: Long,
    val name: String,
)

/**
 * One card the AnkiDroid scheduler has surfaced.
 *
 * Two parallel representations of the card's text:
 *  - displayHtmlQuestion / displayHtmlAnswer: raw HTML (with LaTeX delimiters
 *    intact) suitable for rendering by [dev.kiran.ankivoice.math.MathView].
 *  - speechQuestion / speechAnswer: plain text with LaTeX blocks converted
 *    to ClearSpeak via [MathPipeline]. Suitable for direct TTS playback.
 */
data class DueCard(
    val noteId: Long,
    val cardOrd: Int,
    val buttonCount: Int,
    val displayHtmlQuestion: String,
    val displayHtmlAnswer: String,
    val speechQuestion: String,
    val speechAnswer: String,
)

class AnkiRepository(
    private val context: Context,
    private val mathPipeline: MathPipeline,
) {

    private val resolver get() = context.contentResolver

    fun isAnkiDroidAvailable(): Boolean {
        val info = context.packageManager.resolveContentProvider(AnkiContract.AUTHORITY, 0)
        return info != null
    }

    fun listDecks(): List<Deck> {
        val cursor = resolver.query(
            AnkiContract.Deck.CONTENT_ALL_URI,
            arrayOf(AnkiContract.Deck.DECK_ID, AnkiContract.Deck.DECK_NAME),
            null, null, null,
        ) ?: return emptyList()

        return cursor.use { c ->
            buildList {
                val idCol = c.getColumnIndexOrThrow(AnkiContract.Deck.DECK_ID)
                val nameCol = c.getColumnIndexOrThrow(AnkiContract.Deck.DECK_NAME)
                while (c.moveToNext()) {
                    add(Deck(id = c.getLong(idCol), name = c.getString(nameCol)))
                }
            }
        }
    }

    /**
     * Pulls the next card AnkiDroid's scheduler would surface, then runs each
     * side through [MathPipeline] to derive TTS-ready speech text. Suspending
     * because the math pipeline lives on a WebView (Main dispatcher).
     */
    suspend fun nextDueCard(deckId: Long): DueCard? {
        val reviewCursor = resolver.query(
            AnkiContract.ReviewInfo.CONTENT_URI,
            null,
            "limit=?,deckID=?",
            arrayOf("1", deckId.toString()),
            null,
        ) ?: return null

        val (noteId, cardOrd, buttonCount) = reviewCursor.use { c ->
            if (!c.moveToFirst()) return null
            Triple(
                c.getLong(c.getColumnIndexOrThrow(AnkiContract.ReviewInfo.NOTE_ID)),
                c.getInt(c.getColumnIndexOrThrow(AnkiContract.ReviewInfo.CARD_ORD)),
                c.getInt(c.getColumnIndexOrThrow(AnkiContract.ReviewInfo.BUTTON_COUNT)),
            )
        }

        val cardCursor = resolver.query(
            AnkiContract.Card.uri(noteId, cardOrd),
            arrayOf(
                AnkiContract.Card.QUESTION_SIMPLE,
                AnkiContract.Card.ANSWER_PURE,
            ),
            null, null, null,
        ) ?: return null

        val (rawQ, rawA) = cardCursor.use { c ->
            if (!c.moveToFirst()) return null
            val q = c.getString(c.getColumnIndexOrThrow(AnkiContract.Card.QUESTION_SIMPLE)).orEmpty()
            val a = c.getString(c.getColumnIndexOrThrow(AnkiContract.Card.ANSWER_PURE)).orEmpty()
            q to a
        }

        val speechQ = mathPipeline.extractSpeech(rawQ)
        val speechA = mathPipeline.extractSpeech(rawA)

        return DueCard(
            noteId = noteId,
            cardOrd = cardOrd,
            buttonCount = buttonCount,
            displayHtmlQuestion = rawQ,
            displayHtmlAnswer = rawA,
            speechQuestion = speechQ,
            speechAnswer = speechA,
        )
    }

    fun submitReview(card: DueCard, ease: Int, timeTakenMs: Long): Int {
        require(ease in 1..4) { "ease must be 1..4, got $ease" }
        val values = ContentValues().apply {
            put(AnkiContract.ReviewInfo.NOTE_ID, card.noteId)
            put(AnkiContract.ReviewInfo.CARD_ORD, card.cardOrd)
            put(AnkiContract.ReviewInfo.EASE, ease)
            put(AnkiContract.ReviewInfo.TIME_TAKEN, timeTakenMs)
        }
        return resolver.update(AnkiContract.ReviewInfo.CONTENT_URI, values, null, null)
    }

    fun requestSync() {
        val intent = Intent(AnkiContract.DO_SYNC_ACTION).apply {
            setPackage(AnkiContract.ANKIDROID_PACKAGE)
        }
        context.sendBroadcast(intent)
    }

    fun debugColumnsFor(uri: android.net.Uri): List<String> {
        val cursor: Cursor = resolver.query(uri, null, null, null, null) ?: return emptyList()
        return cursor.use { it.columnNames.toList() }
    }
}
