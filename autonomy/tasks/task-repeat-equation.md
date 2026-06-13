# Task: "Repeat the equation" - re-read only the math portion(s) of the card

## From TODO.md
"Repeat the equation - re-read only the math portion(s) of the current card (skip the surrounding prose). Useful when the formula went past too quickly and the user wants only that part again."

## Goal
When the user issues the `RepeatEquation` command during a hands-free review, speak ONLY the math from the current card (the LaTeX/equation segments), not the surrounding prose. The voice-vocab task likely left this as a stub or full re-read; implement it properly.

## Work
- Add a pure function, e.g. `math/MathExtractor.kt` `extractMath(cardHtmlOrText: String): List<String>`, that pulls the math segments out of a card. Cards carry LaTeX delimited by `$$...$$` and `\(...\)` (see `MainActivity` test card and `MathPipeline`/`LlmSpeechConverter` handling). Return the math segments in order; empty list if none.
- Wire `RepeatEquation` in `ReviewSession` to run the extracted math segment(s) through the existing speech path (`AnkiRepository.generateSpeech` / the speaker port) and speak them; if there is no math, fall back to repeating the question (and log that).
- Pure Kotlin for the extractor (framework-free, JVM-unit-testable).

## Tests
- `app/src/test/.../math/MathExtractorTest.kt`: `$$...$$` extraction, inline `\(...\)`, multiple segments in order, prose-only card -> empty list, mixed prose+math -> only math. Plain JUnit.
- Extend `ReviewSessionTest` (fakes) to assert the `RepeatEquation` branch speaks the math segment and falls back to the question when the card has no math.
- Logic/STT-side (no new UI) -> unit tests are the verification; the CI emulator job re-runs the existing UI suite as regression.

## Acceptance
- `./gradlew testDebugUnitTest` green in CI; existing tests still pass.
- Do not modify `.github/workflows/` or `autonomy/`. Do not weaken tests. No real AnkiWeb creds.
