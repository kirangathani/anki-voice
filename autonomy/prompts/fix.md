You are working autonomously on the anki-voice Android app, on git branch `{{BRANCH}}`. Your last push FAILED CI. Diagnose and fix it, then commit and push again.

## Task being implemented: {{TASK_TITLE}}

## CI failure (job logs / summary)
{{CI_FAILURE}}

## How to fix
- Read the failure carefully. Distinguish a real code/test bug from CI flakiness. If it looks like genuine flakiness (e.g. transient adb/emulator hiccup unrelated to your change), say so in your summary — the supervisor will have already retried once.
- Common patterns in this repo (from prior debugging):
  - Instrumented UI assertions must read logcat (tag `SpikeLog`), not the Compose pane; re-find buttons each tap (recomposition moves them).
  - Unit tests use PLAIN JUnit assertions (no Google Truth).
  - Build memory is constrained; do not add heavy dependencies.
- Make the minimal correct change. Do NOT disable, skip (`@Ignore`/`assumeFalse`), or delete tests to make CI pass. Do NOT modify `.github/workflows/` or `autonomy/`.
- Do NOT run Gradle/builds on this host (RAM is constrained/faulty, the JVM crashes); push and let CI verify.

## Finish
Commit and push to the same branch:
  git add -A && git commit -m "<what you fixed>"
  git push
Then print a one-line summary of the fix. No AI attribution. No em-dashes.
