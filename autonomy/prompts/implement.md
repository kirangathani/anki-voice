You are working autonomously on the anki-voice Android app (Kotlin + Jetpack Compose). You are on git branch `{{BRANCH}}`, freshly branched from `origin/main`. Implement ONE task, fully, then commit and push.

## Task: {{TASK_TITLE}}

{{TASK_SPEC}}

## How to work
- Read CLAUDE.md and the referenced existing files first. Match the existing code's style, naming, and patterns.
- Work TEST-FIRST where practical. The repo's testing conventions (follow them exactly):
  - Unit tests live in `app/src/test/...`, pure JVM, PLAIN JUnit assertions only (Google Truth does NOT resolve under AGP here — see `app/src/test/.../voice/TtsTextTest.kt`).
  - Instrumented tests live in `app/src/androidTest/.../SpikeUiTest.kt`, UIAutomator-driven, and assert on the app's own logcat (tag `SpikeLog`, mirrored from `MainActivity.append`) via the `logcatContains` helper — NOT by scraping the Compose log pane. Use `tapUntilLog` / `scrollTo` / `scrollToEnabled` (they re-find elements each attempt to survive recomposition).
- Keep pure logic in framework-free classes so it is JVM-unit-testable (the project already does this, e.g. `voice/TtsText.kt`).
- UI VERIFICATION (required): if your change affects the UI or user-visible behaviour, you MUST add/extend an instrumented test in `app/src/androidTest/.../SpikeUiTest.kt` that exercises it through the UI and asserts via the app's logcat (tag `SpikeLog`) using the `logcatContains`/`tapUntilLog` helpers. CI's emulator `ui-check` job boots a real device, drives the UI, and captures a screenshot + ui dump; that job is how the UI is verified. A UI change without a passing instrumented test is INCOMPLETE.
- Do NOT run Gradle or any build/test on this host: its RAM is constrained/faulty and the JVM will crash. Reason carefully about correctness, then push and let GitHub CI build and test. Each CI cycle is ~7 minutes, so think before pushing rather than trial-and-error.

## Hard rules
- Do NOT modify anything under `.github/workflows/` or `autonomy/`. Do NOT weaken, skip, or delete existing tests.
- Do NOT add the user's real AnkiWeb credentials anywhere; tests use a fresh/seeded local AnkiDroid collection only.
- Make small, focused commits with clear messages. NO AI attribution / Co-Authored-By lines. No em-dashes in code, comments, or commit messages.
- If a requirement is ambiguous, make a reasonable, documented choice and note it in the commit message; do not stall.

## Finish
Commit your work and push the branch:
  git add -A && git commit -m "<concise message>"
  git push -u origin {{BRANCH}}
Then print a one-line summary of what you changed. Do NOT merge to main, do NOT open a PR (the supervisor handles CI verification and merge).
