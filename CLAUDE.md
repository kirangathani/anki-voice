# anki-voice

Hands-free voice-driven Anki review on Android. Built so the user (preparing for CFA Level 3) can review cards eyes/hands-free. **Reviews flow through AnkiDroid's native scheduler** and sync via AnkiWeb to the user's Anki Desktop. We deliberately do **not** build our own SRS.

## Status (as of `75c8c0c`)

End-to-end review primitives + math read-out + voice-input primitive all working on real device. Hands-free review session loop not yet wired together.

- ✅ AnkiDroid ContentProvider — list decks, fetch next due card, submit ease 1–4, broadcast `DO_SYNC`. Round-trips to Anki Desktop via AnkiWeb.
- ✅ MathJax visual rendering per card (`MathView`) — auto-sizes to content via a JS→Kotlin bridge.
- ✅ LLM-based LaTeX → speech via Claude Haiku 4.5 (`LlmSpeechConverter`) with prompt caching + `SpeechCache` (SharedPreferences SHA256-keyed). Period-padded letter outputs ("A. L." for `a_L`).
- ✅ Android `TextToSpeech` playback (`TtsEngine`) with one tunable `pauseMs` (currently 120ms) that injects explicit silence via `playSilentUtterance` at every period/colon/semicolon/?/!/newline. Single knob controls all pacing.
- ✅ Android `SpeechRecognizer` wrapper (`SttEngine`) — one-shot `listen()` returning `Recognized | NoMatch | Error`. Defaults: 5-min silence cutoff, wake-word stop on `"execute"` (substring match in partial results triggers `stopListening()`).
- ⏳ `ReviewSession` state machine — pending (task #15).
- ⏳ Hands-free voice flow wired into UI — pending (task #16).
- ⏳ Voice command spec — in `TODO.md`: grade ("again/hard/good/easy"), repeat question, repeat equation, repeat answer.

`/home/kiran/.claude/plans/can-we-please-build-sunny-rabin.md` was the original full-app design plan; mostly executed. Latest aspirational items (hosted TTS upgrade, Wispr Flow STT) live in `TODO.md`.

## Architecture (immutable v1 decisions)

- **Native Android, Kotlin + Jetpack Compose.**
- **AnkiDroid ContentProvider** (`com.ichi2.anki.flashcards`) is the only integration point. We do not parse `.apkg`, do not talk to AnkiWeb directly, do not run a parallel scheduler.
- **Sync**: AnkiDroid ↔ AnkiWeb ↔ Anki Desktop, triggered by broadcasting `com.ichi2.anki.DO_SYNC` (set package `com.ichi2.anki`; rate-limited to once per 5 min by AnkiDroid).
- **Phone-first, Android-only.** iOS deferred (no AnkiMobile API). Desktop deferred (user has Anki Desktop).
- **LLM for math → speech only.** Claude Haiku 4.5 converts LaTeX in cards to TTS-ready prose. Voice synthesis is Android built-in TTS (with explicit silence injection for pacing) — replacing with hosted TTS (OpenAI gpt-4o-mini-tts ~$1/mo) is a future upgrade tracked in `TODO.md`.

## Key files

- `app/src/main/java/dev/kiran/ankivoice/anki/AnkiContract.kt` — inlined FlashCardsContract constants.
- `app/src/main/java/dev/kiran/ankivoice/anki/AnkiRepository.kt` — `listDecks`, `nextDueCard(deckId)` (suspending, runs cards through LLM/MathPipeline for speech text), `submitReview`, `requestSync`, `generateSpeech` (public for the synthetic Test-card path).
- `app/src/main/java/dev/kiran/ankivoice/math/MathPipeline.kt` — hidden WebView running MathJax + SRE for the SRE fallback path. Mostly unused now that LLM is the primary path; kept as fallback when no API key.
- `app/src/main/java/dev/kiran/ankivoice/math/MathView.kt` — Compose `AndroidView { WebView }` per visible card. Loads MathJax (`tex-chtml.js`) inline, auto-sizes height via ResizeObserver → JS bridge.
- `app/src/main/java/dev/kiran/ankivoice/voice/LlmSpeechConverter.kt` — Claude Haiku client (OkHttp + org.json), prompt-cached system block, period-padded letter output format.
- `app/src/main/java/dev/kiran/ankivoice/voice/SpeechCache.kt` — SharedPreferences cache, key namespaced (`llm_speech_cache_v3`) — bump version when prompt changes.
- `app/src/main/java/dev/kiran/ankivoice/voice/TtsEngine.kt` — TextToSpeech wrapper. `speak(text)` splits at every `.?!:;` followed by whitespace + every newline; injects `pauseMs` silence between chunks. **One knob (`pauseMs`) tunes overall pacing.**
- `app/src/main/java/dev/kiran/ankivoice/voice/SttEngine.kt` — SpeechRecognizer wrapper. `listen(silenceMs, wakeWords)` — silence default 5 min (no practical cutoff); wake-word substring match in partial results triggers `stopListening()`.
- `app/src/main/java/dev/kiran/ankivoice/MainActivity.kt` — single-screen spike still in use. Has all the diagnostic buttons (List decks, Next due card, Test math card, Show speech text, Read question, Stop, Test mic). To be split into proper `ui/ReviewScreen.kt` etc. when the hands-free flow is built.
- `app/src/main/AndroidManifest.xml` — `com.ichi2.anki.permission.READ_WRITE_DATABASE`, `INTERNET`, `RECORD_AUDIO`. `<queries>` includes both `com.ichi2.anki` package and `android.speech.RecognitionService` intent (required for Android 11+ `SpeechRecognizer.isRecognitionAvailable`).
- `app/src/main/assets/math/` — MathJax (`tex-chtml.js`) + SRE (`sre.js`, mathmaps). SRE bundle's hardcoded JSDelivr URL is patched at commit time to use the local mathmaps path.

## Toolchain & build flow

- Kotlin 2.0, AGP 8.5, Compose BOM 2024.09.02, JDK 17, minSdk 26, targetSdk 35, Gradle 8.10.2.
- Dev environment is WSL2 Ubuntu. User builds via GitHub Actions, not locally.
- CI workflow injects `ANTHROPIC_API_KEY` repo secret into `local.properties` before Gradle; exposed as `BuildConfig.ANTHROPIC_API_KEY`. Empty string = LLM disabled, MathPipeline SRE fallback activates.
- Every push to `main` builds + publishes to rolling `latest-debug` release (stable URL: <https://github.com/kirangathani/anki-voice/releases/tag/latest-debug>). Debug signing key cached via `actions/cache` so APK updates install in-place without uninstall.

## Sharp edges to remember

- **LLM cache invalidation.** Bump `SpeechCache` version suffix any time `LlmSpeechConverter.SYSTEM_PROMPT` changes — otherwise old outputs get served.
- **`SpeechRecognizer` and `EXTRA_PREFER_OFFLINE`.** Setting this true requires an installed offline language pack; without one it errors `ERROR_LANGUAGE_UNAVAILABLE` (13) instead of falling back to cloud. Currently NOT set — let Android choose.
- **`SpeechRecognizer` and `<queries>`.** Android 11+ needs an explicit `<intent action="android.speech.RecognitionService"/>` query entry or `isRecognitionAvailable()` returns false.
- **TTS pacing.** `TtsEngine.pauseMs` is the one variable. Periods inside math equations are advisory hints to TTS; we don't rely on TTS to honour them — we inject real silence.
- **Wake-word risk.** Whichever word we pick for the "I'm done" marker must be unlikely to appear in normal speech. Currently `"execute"` — user-chosen for that reason. Don't add `"done"` / `"stop"` back without consideration.
- **Image-occlusion / media-heavy cards** have empty `QUESTION_SIMPLE`/`ANSWER_SIMPLE`. Detect and skip; don't crash.
- **`DO_SYNC` broadcast** is flagged "experimental" on the AnkiDroid wiki and rate-limited to once per 5 min. Fire once per session, not per card.

## What this codebase is NOT

- Not a spaced-repetition system. We rate; AnkiDroid schedules.
- Not a generic Anki client. Reviews only — no card authoring, no browsing.
- Not cross-platform. Android only.
- Not a general voice assistant. Voice-driven Anki reviewer, nothing more.

## Next session pickup

Build out the hands-free review loop (tasks #15 + #16):

1. `session/ReviewSession.kt` — pure-Kotlin state machine. Driven by a coroutine scope. States: SpeakingQuestion → AwaitingAnswer (STT with "execute" wake word, transcript discarded) → PromptingForGrade ("how did you do") → AwaitingCommand (STT with grade-word vocab: again/hard/good/easy/answer/repeat/stop) → branches: speak answer / submit grade / repeat / end. Takes `TtsEngine`, `SttEngine`, `AnkiRepository` as constructor deps.
2. Add a "Start hands-free review" button to `MainActivity` that runs `ReviewSession` for the selected deck. Show speaking/listening state visually.
3. Voice command vocabulary per `TODO.md`.

Keep current spike buttons (Test mic, Test math card, etc.) as the debug surface.
