# anki-voice

Hands-free voice review of your Anki cards on Android, using AnkiDroid's native scheduler. Reviews submitted by this app go through AnkiDroid's own scheduler and sync to AnkiWeb (and therefore back to Anki Desktop).

**Status: v0 spike.** Proves the AnkiDroid Content Provider primitives end-to-end on a real device. No voice yet — that comes after the spike passes.

See `/home/kiran/.claude/plans/can-we-please-build-sunny-rabin.md` for the full design.

## Prerequisites

1. **AnkiDroid installed on the phone**, signed in to AnkiWeb, with at least one deck that has due cards. Confirm AnkiDroid ↔ AnkiWeb ↔ Anki Desktop sync already works before building.
2. **Android Studio** (Hedgehog or later) installed on a machine with the Android SDK. WSL note: install Android Studio on the Windows side; you can either connect the phone over USB to Windows, or set up `adb` over Wi-Fi.
3. **JDK 17** (bundled with current Android Studio).

## One-time setup

The repo intentionally does **not** include the `gradle-wrapper.jar` binary. To generate it:

- **Easiest**: open the project in Android Studio (File → Open → select this folder). On first sync it will offer to generate the wrapper. Accept.
- **CLI alternative** (if you have a system Gradle installed): from the repo root, run `gradle wrapper --gradle-version 8.10.2`.

After that, `./gradlew assembleDebug` should produce an APK at `app/build/outputs/apk/debug/app-debug.apk`.

## Running the spike

1. Plug your Android phone in via USB. Enable USB debugging in developer options.
2. In Android Studio: select your device in the device dropdown → Run.
3. On the phone, AnkiDroid will prompt for the `READ_WRITE_DATABASE` permission the first time the app accesses it. Allow.

## What the spike proves

The spike's UI is a log window plus six buttons. Walk through each numbered step in the log to verify the design's assumptions hold on your specific device + AnkiDroid version:

1. **Permission flow** — on first launch, the log shows "Requesting AnkiDroid READ_WRITE_DATABASE permission..." and AnkiDroid prompts. After "Permission granted." appears, the other buttons enable.
2. **List decks** — tap. The log should show your deck count and the first ~10 names. The spike auto-selects the first non-Default deck.
3. **Next due card** — tap. The log should show `noteId=… ord=… buttons=…`. The card's question appears on screen. Tap "Show answer" to reveal the back.
4. **Grade buttons** — Again / Hard / Good / Easy. Each submits an ease (1–4) to AnkiDroid's scheduler and auto-fetches the next card. After grading, open AnkiDroid → that deck → verify the card you just graded has advanced (its next-due time changed).
5. **Sync now** — tap. The log says "DO_SYNC broadcast sent." Open AnkiDroid and check the sync icon spins (or that the last-sync time updated). Note: AnkiDroid rate-limits this to once per 5 minutes; if it doesn't fire, that's expected if you synced recently.
6. **End-to-end sync** — after a Sync now, open Anki Desktop on your computer and click sync. The cards you graded in the spike should show the advanced intervals there too.

## If something breaks

The spike has a **Dump columns** button. It queries each ContentProvider URI and logs the actual column names AnkiDroid returns. If `nextDueCard` blows up with a "column not found" error, dump and reconcile against `dev/kiran/ankivoice/anki/AnkiContract.kt`.

Common failure modes:

- **"AnkiDroid ContentProvider reachable: false"** — AnkiDroid isn't installed, OR the `<queries><package android:name="com.ichi2.anki"/></queries>` entry in `AndroidManifest.xml` got dropped (Android 11+ package visibility).
- **Permission permanently denied** — uninstall the spike and reinstall to re-trigger the prompt. Or grant manually: Android Settings → Apps → anki-voice spike → Permissions → AnkiDroid database → Allow.
- **`nextDueCard` returns null even though cards are due** — the deck you selected may have no cards due *right now* per AnkiDroid's scheduler. The spike auto-skips "Default"; try tapping List decks and verifying your CFA deck got selected.
- **Sync broadcast does nothing** — AnkiDroid's sync intent is rate-limited and flagged experimental. Open AnkiDroid manually to force sync if needed.

## After the spike passes

Build out the polished app per `/home/kiran/.claude/plans/can-we-please-build-sunny-rabin.md`:

- `voice/TtsEngine.kt` — wrap `TextToSpeech` with suspending API
- `voice/SttEngine.kt` — wrap `SpeechRecognizer` for end-of-speech + command words
- `session/ReviewSession.kt` — the hands-free state machine
- `ui/DeckListScreen.kt` and `ui/ReviewScreen.kt`

Do **not** start that work until every step in "What the spike proves" passes on your device.
