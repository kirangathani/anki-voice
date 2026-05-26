package dev.kiran.ankivoice.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor

data class Deck(
    val id: Long,
    val name: String,
)

data class DueCard(
    val noteId: Long,
    val cardOrd: Int,
    val buttonCount: Int,
    val question: String,
    val answer: String,
)

/**
 * Thin wrapper over AnkiDroid's ContentProvider. Stateless — safe to construct
 * per-call or hold as a singleton. All blocking I/O; call from a background
 * dispatcher.
 */
class AnkiRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Returns true if AnkiDroid's ContentProvider is reachable on this device. */
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
     * Pulls the next card AnkiDroid's scheduler would surface for [deckId].
     * Returns null if nothing is due (or the deck is empty).
     *
     * Implementation note: AnkiDroid's ReviewInfo endpoint requires a selection
     * string of the form "limit=?,deckID=?" with positional args.
     */
    fun nextDueCard(deckId: Long): DueCard? {
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
            arrayOf(AnkiContract.Card.QUESTION_SIMPLE, AnkiContract.Card.ANSWER_SIMPLE),
            null, null, null,
        ) ?: return null

        return cardCursor.use { c ->
            if (!c.moveToFirst()) return null
            DueCard(
                noteId = noteId,
                cardOrd = cardOrd,
                buttonCount = buttonCount,
                question = c.getString(c.getColumnIndexOrThrow(AnkiContract.Card.QUESTION_SIMPLE)).orEmpty(),
                answer = c.getString(c.getColumnIndexOrThrow(AnkiContract.Card.ANSWER_SIMPLE)).orEmpty(),
            )
        }
    }

    /**
     * Submits the user's grade for [card]. AnkiDroid routes this to
     * `col.sched.answerCard(...)`, identical to its own review buttons.
     *
     * @param ease one of [AnkiContract.Ease]: AGAIN=1, HARD=2, GOOD=3, EASY=4.
     * @param timeTakenMs how long the user spent on this card, in ms.
     * @return rows updated (1 on success).
     */
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

    /**
     * Asks AnkiDroid to sync with AnkiWeb. AnkiDroid rate-limits this to once
     * per 5 minutes. Fire-and-forget — the broadcast returns immediately.
     */
    fun requestSync() {
        val intent = Intent(AnkiContract.DO_SYNC_ACTION).apply {
            setPackage(AnkiContract.ANKIDROID_PACKAGE)
        }
        context.sendBroadcast(intent)
    }

    /**
     * Dumps the column names returned for a given URI — useful when the spike
     * blows up on `getColumnIndexOrThrow` because AnkiDroid renamed something.
     */
    fun debugColumnsFor(uri: android.net.Uri): List<String> {
        val cursor: Cursor = resolver.query(uri, null, null, null, null) ?: return emptyList()
        return cursor.use { it.columnNames.toList() }
    }
}
