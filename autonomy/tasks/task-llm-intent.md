# Task: LLM-based voice-command intent classification (Claude Haiku), keyword-first

## From TODO.md
"Intent classification: keyword match vs LLM intent classification (use Claude Haiku to parse loose phrasings like 'say it again' / 'go back' / 'yeah okay easy one'?). Keyword match is fast/offline; LLM is more forgiving. Probably keyword first, LLM later."

## Goal
Layer an optional LLM intent classifier on top of the existing keyword `CommandParser` (from the voice-vocab task) so loose, natural phrasings still map to the right `ReviewCommand`. Keyword match stays the fast first pass; the LLM is only consulted when the keyword parser returns `Unknown`.

## Work
- Add `session/LlmCommandClassifier.kt`: given a transcript and the allowed command set, classify into the same `ReviewCommand` sealed type (Grade(ease)/RepeatQuestion/RepeatEquation/Answer/Stop/Unknown). Use the SAME Claude Haiku client pattern as `voice/LlmSpeechConverter.kt` (OkHttp + `BuildConfig.ANTHROPIC_API_KEY`, prompt-cached system block). If the API key is empty, the classifier is disabled and returns `Unknown` (graceful, offline-safe).
- Add a resolver (e.g. `CommandResolver`) that does: keyword `CommandParser.parse()` first; if `Unknown` AND the LLM classifier is available, fall back to the LLM. Wire it into `ReviewSession`'s `AwaitingCommand` state in place of the bare keyword parse.
- Keep it cheap: only call the LLM on `Unknown`; consider a small SharedPreferences cache keyed by transcript hash (mirror `voice/SpeechCache.kt`) so repeated phrasings do not re-call.

## Tests
- `app/src/test/.../session/CommandResolverTest.kt`: with a FAKE LLM classifier, assert keyword hits never call the LLM; `Unknown` keyword results fall through to the LLM; LLM `Unknown` stays `Unknown`. Plain JUnit.
- Pure-test the LLM request/prompt building (no network) the way `LlmSpeechConverter` logic can be exercised.
- This is STT-side logic (no new UI), so unit tests are the verification; do not add audio-dependent instrumented tests. The CI emulator job still runs the full existing UI suite as a regression check.

## Acceptance
- `./gradlew testDebugUnitTest` green in CI; existing tests still pass.
- Do not modify `.github/workflows/` or `autonomy/`. Do not weaken tests. No real AnkiWeb creds. Keyword-first behaviour preserved when the LLM is unavailable.
