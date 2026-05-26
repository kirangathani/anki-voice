# anki-voice

Hands-free voice-driven Anki review on Android. Built so the user (preparing for CFA Level 3) can review cards eyes/hands-free. **Reviews flow through AnkiDroid's native scheduler** and sync via AnkiWeb to the user's Anki Desktop. We deliberately do **not** build our own SRS.

## Status

v0 spike only. Code proves the AnkiDroid ContentProvider primitives (list decks, fetch next due card, submit ease 1–4, broadcast `DO_SYNC`). **No voice yet** — that follows once the spike passes end-to-end on a real device with a real deck.

Canonical design (read before any architectural change): `/home/kiran/.claude/plans/can-we-please-build-sunny-rabin.md`.

## Architecture (immutable v1 decisions)

- **Native Android, Kotlin + Jetpack Compose.** No backend, no LLM, no cloud cost.
- **AnkiDroid ContentProvider** (`com.ichi2.anki.flashcards`) is the only integration point. We do not parse `.apkg`, do not talk to AnkiWeb directly, do not run a parallel scheduler.
- **Sync**: AnkiDroid ↔ AnkiWeb ↔ Anki Desktop, triggered by broadcasting `com.ichi2.anki.DO_SYNC` (set package `com.ichi2.anki`; rate-limited to once per 5 min by AnkiDroid).
- **Phone-first, Android-only.** iOS deferred (no AnkiMobile API). Desktop deferred (user has Anki Desktop).
- **No LLM in v1.** Dictation + TTS + verbal self-grading (user says "again/hard/good/easy"). LLM tutor mode is a v2 idea.

## Key files

- `app/src/main/java/dev/kiran/ankivoice/anki/AnkiContract.kt` — inlined FlashCardsContract constants. If the spike hits a column-not-found error, dump columns from the spike UI and reconcile against upstream `Anki-Android/api/src/main/java/com/ichi2/anki/FlashCardsContract.kt`.
- `app/src/main/java/dev/kiran/ankivoice/anki/AnkiRepository.kt` — `listDecks`, `nextDueCard(deckId)`, `submitReview(card, ease, timeTakenMs)`, `requestSync`, `debugColumnsFor`. Stateless, blocking — call from `Dispatchers.IO`.
- `app/src/main/java/dev/kiran/ankivoice/MainActivity.kt` — single-screen spike. To be split into `ui/`, `voice/`, `session/` packages after the spike passes (see plan §"Files to create").
- `app/src/main/AndroidManifest.xml` — requires BOTH `com.ichi2.anki.permission.READ_WRITE_DATABASE` AND `<queries><package android:name="com.ichi2.anki"/></queries>` (Android 11+ package visibility).

## Toolchain

- Kotlin 2.0, AGP 8.5, Compose BOM 2024.09.02, JDK 17, minSdk 26, targetSdk 35, Gradle 8.10.2.
- Dev environment is WSL2 Ubuntu; Android Studio runs on the Windows side. Phone connects over USB to Windows or `adb` over Wi-Fi.
- `gradle-wrapper.jar` is intentionally not committed — first `gradle wrapper` or Android Studio sync generates it.

## Sharp edges to remember

- `ContentResolver.update(ReviewInfo URI, ...)` with `EASE` and `TIME_TAKEN` is the load-bearing call. If AnkiDroid renames a column in a future release, the spike's "Dump columns" button is the diagnostic.
- The sync broadcast is flagged "experimental" on the AnkiDroid wiki. Fire once per session, not per card.
- Image-occlusion / media-heavy cards have empty `QUESTION_SIMPLE`/`ANSWER_SIMPLE`. Detect and skip; don't crash.
- `SpeechRecognizer`'s default end-of-speech timeout may cut off long CFA answers mid-thought. Tunable silence threshold + a "still thinking" wake phrase will likely be needed.

## What this codebase is NOT

- Not a spaced-repetition system. We rate; AnkiDroid schedules.
- Not a generic Anki client. Reviews only — no card authoring, no browsing.
- Not cross-platform. Android only.
- Not a general voice assistant. Voice-driven Anki reviewer, nothing more.
