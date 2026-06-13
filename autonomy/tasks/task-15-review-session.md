# Task 15: ReviewSession hands-free state machine (pure Kotlin) + unit tests

## Goal
Build the hands-free review loop as a pure-Kotlin, coroutine-driven state machine in `app/src/main/java/dev/kiran/ankivoice/session/ReviewSession.kt`, fully unit-testable on the JVM with fake engines (no Android, no emulator).

## Design (from CLAUDE.md "Next session pickup")
States and transitions:
- `SpeakingQuestion` — read the current card's question aloud (TTS).
- `AwaitingAnswer` — listen (STT) with wake word "execute"; the user's spoken answer transcript is discarded (they just think aloud). The wake word ends listening.
- `PromptingForGrade` — speak a short prompt ("how did you do?").
- `AwaitingCommand` — listen (STT) for a command from the grade vocabulary: again / hard / good / easy (and numeric 1–4), plus repeat / answer / stop.
- Branches from a command: speak the answer ("answer"); submit the grade and advance to the next card (again/hard/good/easy); repeat the question/answer ("repeat"); end the session ("stop") or when no more due cards.

## Testability requirement (critical)
`ReviewSession` MUST NOT depend on Android types directly, so JVM unit tests can drive it with fakes. The concrete `TtsEngine`/`SttEngine`/`AnkiRepository` are Android-bound (they need a `Context`). Therefore:
- Define small interfaces (ports) that `ReviewSession` depends on, e.g. a speaker (`suspend fun speak(text: String)`), a listener (`suspend fun listen(wakeWords: List<String>): <result>`), and a review data source (`suspend fun nextDueCard(deckId): DueCard?`, `suspend fun submitReview(card, ease, timeMs)`). Choose the cleanest shape.
- The existing `TtsEngine`, `SttEngine`, `AnkiRepository` should implement / be adapted to these interfaces (thin adapters are fine; do not rewrite the engines).
- `ReviewSession` takes the interfaces as constructor dependencies and is driven by an injected `CoroutineScope` (or is a `suspend fun run(...)`).

## Logging
Take an `onLog: (String) -> Unit` callback (mirror the app's pattern) and log each state transition (e.g. "ReviewSession: SpeakingQuestion", "ReviewSession: submitted Good"). This is what instrumented tests assert on later (task 16).

## Tests (this task)
Add `app/src/test/java/dev/kiran/ankivoice/session/ReviewSessionTest.kt`:
- Fake speaker (records spoken text), fake listener (returns scripted transcripts/commands in sequence), fake data source (serves 1–2 canned `DueCard`s then null).
- Cover: a full review of one card (question spoken → answer awaited → grade prompted → "good" → grade submitted → next card), the "repeat" command re-speaks, the "answer" command speaks the answer, "stop" ends the session, and empty-deck ends gracefully.
- Pure JUnit assertions (no Google Truth — it does not resolve under AGP here; see existing `TtsTextTest.kt`).

## Acceptance criteria
- `./gradlew testDebugUnitTest` passes in CI (the `unit-tests` job).
- `ReviewSession.kt` has no `android.*` imports (pure JVM).
- Do not modify `.github/workflows/` or `autonomy/`. Do not weaken or delete existing tests.
- Reuse existing types: `DueCard`, `AnkiContract.Ease`, the existing engines' public APIs. Follow CLAUDE.md sharp edges (e.g. fire `DO_SYNC` once per session, not per card).
