# Task: Voice command vocabulary (grade / repeat / answer), keyword-match first

## Depends on
task-15-review-session.

## Goal
Implement parsing of the spoken commands the user issues during a hands-free review, per TODO.md. Keyword-match first (fast, offline); LLM intent classification is explicitly deferred.

## Vocabulary (from TODO.md, verbatim intent)
- Grade the answer: "again", "hard", "good", "easy". Also accept "1", "2", "3", "4" as numeric synonyms (1=again … 4=easy, matching `AnkiContract.Ease`).
- Repeat the question: full re-read of the current card's front.
- Repeat the equation: re-read only the math portion(s) of the current card (skip surrounding prose). (If the math-only extraction is non-trivial, implement a reasonable first version and note the limitation in the PR; do not block.)
- Repeat / give the answer: read the card's back. Two phrasings: "what was the answer?" and "repeat the answer".

## Work
- Add a pure function/object, e.g. `session/CommandParser.kt`: `parse(transcript: String): ReviewCommand` where `ReviewCommand` is a sealed type (Grade(ease), RepeatQuestion, RepeatEquation, Answer, Stop, Unknown). Pure Kotlin, case-insensitive, tolerant of extra words (substring/keyword match).
- Wire it into `ReviewSession`'s `AwaitingCommand` state (replace any ad-hoc matching from task 15).

## Tests
- `app/src/test/java/dev/kiran/ankivoice/session/CommandParserTest.kt`: cover each keyword, numeric synonyms, the two "answer" phrasings, case-insensitivity, extra-words tolerance, and Unknown for gibberish. Plain JUnit assertions.

## Acceptance criteria
- `./gradlew testDebugUnitTest` passes in CI.
- Do not modify `.github/workflows/` or `autonomy/`. Do not weaken existing tests.
