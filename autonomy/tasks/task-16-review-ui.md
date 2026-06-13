# Task 16: "Start hands-free review" button wiring ReviewSession into the UI

## Depends on
task-15-review-session (ReviewSession must exist and be merged to main).

## Goal
Add a "Start hands-free review" control to `MainActivity` that runs the `ReviewSession` (from task 15) for the currently selected deck, using the real engines, and shows the speaking/listening state visually.

## Work
- In `MainActivity` (`app/src/main/java/dev/kiran/ankivoice/MainActivity.kt`), add a button "Start hands-free review", enabled when `permissionGranted && selectedDeck != null && mathPipelineReady && micGranted`.
- On tap: construct `ReviewSession` with adapters wrapping the existing `tts`, `stt`, and `repo`, and run it in the activity's coroutine scope for `selectedDeck`.
- Surface session state visually: a status line that reflects the current state (e.g. "Speaking…", "Listening…", "Grade?"). Drive it from the session's state/log callback.
- Keep all existing spike buttons and behavior intact.

## Tests
Extend `app/src/androidTest/java/dev/kiran/ankivoice/SpikeUiTest.kt` following the existing conventions:
- Use the logcat-assertion pattern: tests read the app's own logcat (tag `SpikeLog`) via the `logcatContains` helper, NOT by scraping the Compose log pane.
- Use `tapUntilLog(...)` / `scrollTo(...)` / `scrollToEnabled(...)` helpers (re-find buttons each attempt to survive recomposition).
- Add a test that, with AnkiDroid present and a seeded card (the suite seeds one in `@BeforeClass seedTestCard`), taps "Start hands-free review" and asserts the session starts (e.g. logcat contains "ReviewSession: SpeakingQuestion"). The emulator has no audio, so do not assert real speech/recognition; assert the state-machine progress logged via `onLog`. Gate audio-dependent steps so they error gracefully (no crash), like the existing `readQuestion`/`testMic` tests.
- `assumeTrue(isAnkiDroidInstalled())` to skip cleanly when AnkiDroid is absent.

## Acceptance criteria
- `./gradlew connectedDebugAndroidTest` passes in CI (the `ui-check` job), all existing tests still green.
- Do not modify `.github/workflows/` or `autonomy/`. Do not weaken existing tests.
