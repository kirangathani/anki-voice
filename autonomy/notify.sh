#!/usr/bin/env bash
# Send a Telegram message to the user via the OpenClaw gateway running on this
# host. Used by the autonomy supervisor for progress/blocker reporting.
#
# Usage: notify.sh "message text"
set -uo pipefail

MSG="${1:-}"
[ -z "$MSG" ] && { echo "notify.sh: empty message" >&2; exit 1; }

CHAT_ID="${ANKI_AUTONOMY_CHAT_ID:-7853471624}"
export PATH="/usr/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"

# Prefix so the user can tell these apart from interactive bot replies.
openclaw agent \
  --agent main \
  --deliver \
  --reply-channel telegram \
  --reply-to "$CHAT_ID" \
  --message "[anki-autonomy] $MSG" \
  >/dev/null 2>&1 \
  && echo "notified: $MSG" \
  || echo "notify FAILED (gateway down?): $MSG" >&2
