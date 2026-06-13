# anki-voice autonomy

A self-driving coding loop: it picks the next task from `backlog.json`, has headless Claude Code implement it on a branch, watches GitHub CI, feeds failures back to Claude (bounded retries), and merges to `main` only when CI is green. Reports progress to Telegram. Built to survive the host's faulty RAM (systemd `Restart=always` + crash-resumable JSON state).

## Architecture
`anki-autonomy.service` (systemd user, Restart=always) → `supervisor.sh` (the loop: state, locking, CI polling, merge) → `claude -p` (Claude Code: edits + tests + commit + push, in a dedicated clone) → GitHub Actions CI (heavy build/test in the cloud; `gh` polls it) → Telegram via `notify.sh`.

Heavy build/test runs in CI, not on the host. The host only edits + pushes + polls (light).

## Files
- `supervisor.sh` — the loop owner. Flags: `--once`, `--task <id>`, `--no-auto-merge`.
- `state.py` — crash-resumable per-task state (`~/autonomy/state/anki-voice.json`).
- `backlog.json` — ordered tasks; `tasks/*.md` — per-task specs.
- `prompts/implement.md`, `prompts/fix.md` — the Claude prompts (placeholders filled per cycle).
- `notify.sh` — Telegram via the OpenClaw gateway.
- `anki-autonomy.service` — the systemd unit.

## Deploy (on Jupiter)
1. `~/autonomy/bin/` ← these scripts; `~/autonomy/{backlog.json,tasks,prompts}` ← copied here.
2. Dedicated work clone: `git clone git@github.com:kirangathani/anki-voice.git ~/autonomy/work/anki-voice` (uses the deploy key).
3. `~/autonomy/env` with `export GH_TOKEN=<fine-grained PAT, anki-voice, Actions:read>` (chmod 600). Only used to poll CI; merges go via git + the deploy key.
4. Install the unit; do NOT enable it until after the supervised trial.

## Rollout
- 5a (supervised): `bin/supervisor.sh --once --task task-15-review-session --no-auto-merge` — verifies green, leaves the branch for you to review/merge.
- 5b: drop `--no-auto-merge`, run the backlog.
- 5c: `systemctl --user enable --now anki-autonomy.service` for continuous always-on operation.

## Guardrails
- Branch per task; merge to `main` only when that branch's CI is green (`main` always builds).
- `MAX_FIX=5` fix attempts/task, then BLOCK + Telegram, continue to next task.
- Prompts forbid editing `.github/workflows/` or `autonomy/` and forbid weakening tests.
- No real AnkiWeb credentials anywhere; CI uses a fresh/seeded local AnkiDroid collection.
- `GH_TOKEN` is Actions:read only (low blast radius); `ANTHROPIC_API_KEY` stays a CI secret. Claude on the host auths via the Max subscription (ToS gray area for headless 24/7 use).
