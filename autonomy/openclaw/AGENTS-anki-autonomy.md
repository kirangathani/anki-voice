# anki-autonomy engine (append this section to ~/.openclaw/workspace/AGENTS.md)

There is an autonomous coding engine on this host (`anki-autonomy`, the Python
`ankiauto` package at `~/autonomy/`, run as the `ankiauto.service` systemd user
service). It owns the anki-voice coding loop end to end. As the OpenClaw agent:

- The engine is AUTHORITATIVE for the coding loop. Do NOT edit anything under
  `~/autonomy/`, the repo's `.github/workflows/`, or the backlog while it runs.
- It reports its own progress to Telegram (messages prefixed `[anki-autonomy]`).
  Those are the primary channel; your heartbeat is only a dead-service backstop
  (see HEARTBEAT.md).
- To answer questions about it, read `~/autonomy/state/status.json` (fields:
  `current_task`, `quota_phase`, `quota_paused`, `reset_hint`, `counts`,
  `manual_paused`) and `journalctl --user -u ankiauto.service`.
- Control verbs (the user may ask): `python3 -m ankiauto <status|pause|resume|
  run-once|add-task>` run with `PYTHONPATH=~/autonomy/src`. Pause/resume set a
  flag the engine reads at its next task boundary.
- The engine uses the Claude Max subscription (headless); if the user asks about
  token usage, it pauses on the usage limit and auto-resumes when quota returns.
