package dev.kiran.ankivoice.anki

import android.net.Uri

/**
 * Constants for AnkiDroid's public ContentProvider.
 *
 * Canonical source: AnkiDroid repo, `api/src/main/java/com/ichi2/anki/FlashCardsContract.kt`.
 * https://github.com/ankidroid/Anki-Android/blob/main/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt
 *
 * If the spike fails on a column-not-found error, dump cursor.columnNames in
 * the spike UI and reconcile against the file above.
 */
object AnkiContract {
    const val AUTHORITY = "com.ichi2.anki.flashcards"
    const val PERMISSION_READ_WRITE = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    const val ANKIDROID_PACKAGE = "com.ichi2.anki"
    const val DO_SYNC_ACTION = "com.ichi2.anki.DO_SYNC"

    object Deck {
        val CONTENT_ALL_URI: Uri = Uri.parse("content://$AUTHORITY/decks")
        const val DECK_ID = "deck_id"
        const val DECK_NAME = "deck_name"
        const val DECK_COUNTS = "deck_count"
    }

    object ReviewInfo {
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")
        const val NOTE_ID = "note_id"
        const val CARD_ORD = "ord"
        const val BUTTON_COUNT = "button_count"
        const val NEXT_REVIEW_TIMES = "next_review_times"
        const val MEDIA_FILES = "media_files"
        // Write-only (used in ContentValues on update):
        const val EASE = "answer_ease"
        const val TIME_TAKEN = "time_taken"
    }

    object Card {
        const val QUESTION_SIMPLE = "question_simple"
        const val ANSWER_SIMPLE = "answer_simple"
        const val DECK_ID = "deck_id"
        const val CARD_NAME = "card_name"

        fun uri(noteId: Long, cardOrd: Int): Uri =
            Uri.parse("content://$AUTHORITY/notes/$noteId/cards/$cardOrd")
    }

    object Ease {
        const val AGAIN = 1
        const val HARD = 2
        const val GOOD = 3
        const val EASY = 4
    }
}
