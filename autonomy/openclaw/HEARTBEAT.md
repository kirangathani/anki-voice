# Heartbeat tasks

Deploy this to `~/.openclaw/workspace/HEARTBEAT.md` on Jupiter.

The anki-autonomy coding engine runs as its own systemd service and reports to
Telegram itself. On each heartbeat, do ONLY this cheap, read-only check (no
coding, no CI, no agent work):

1. Read `~/autonomy/state/status.json` (a small JSON the engine writes).
2. Check the engine service: `systemctl --user is-active ankiauto.service`.
   - If it is `failed` or `inactive` (and not intentionally stopped), run
     `systemctl --user restart ankiauto.service` and send ONE Telegram line:
     "anki-autonomy engine was down; restarted."
3. Do NOT try to resume the engine after a quota pause. The engine self-resumes
   via its own probe loop (and during a quota outage you, the heartbeat, are
   rate-limited too). At most, if `status.json` shows `quota_paused: true`, you
   may note it once; do not act.
4. Otherwise reply `HEARTBEAT_OK` and stay silent.

Keep this dirt cheap. The engine's own per-cycle Telegram messages are the
primary channel; this heartbeat is only a dead-service backstop.
