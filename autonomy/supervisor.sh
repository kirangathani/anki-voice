#!/usr/bin/env bash
# anki-voice autonomy supervisor.
#
# Owns the loop: pick next backlog task -> branch from main -> have headless
# Claude Code implement+push -> watch CI -> on red feed logs back to Claude
# (bounded retries) -> on green merge to main (via git + deploy key) -> report
# to Telegram. Crash-resumable via a JSON state file. Single-flight via flock.
#
# Flags:
#   --once            do one task (or the --task) then exit (default: loop backlog)
#   --task <id>       work only this backlog task id
#   --no-auto-merge   verify green but do NOT merge; leave branch for human review
set -uo pipefail

AUTONOMY_DIR="${AUTONOMY_DIR:-$HOME/autonomy}"
REPO="${ANKI_REPO:-$AUTONOMY_DIR/work/anki-voice}"
BIN="$AUTONOMY_DIR/bin"
STATE="$AUTONOMY_DIR/state/anki-voice.json"
BACKLOG="$AUTONOMY_DIR/backlog.json"
PROMPTS="$AUTONOMY_DIR/prompts"
STATE_PY="$BIN/state.py"
NOTIFY="$BIN/notify.sh"
LOCK="$AUTONOMY_DIR/supervisor.lock"
LOGDIR="$AUTONOMY_DIR/logs"
REPO_SLUG="kirangathani/anki-voice"

MAX_FIX="${MAX_FIX:-5}"
CLAUDE_TIMEOUT="${CLAUDE_TIMEOUT:-1800}"
POLL_INTERVAL="${POLL_INTERVAL:-30}"
CI_TIMEOUT="${CI_TIMEOUT:-1800}"
IDLE_SLEEP="${IDLE_SLEEP:-300}"

export PATH="/usr/bin:$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"
export JAVA_HOME="${JAVA_HOME:-$HOME/opt/jdk17}"
[ -f "$AUTONOMY_DIR/env" ] && . "$AUTONOMY_DIR/env"   # GH_TOKEN etc.

ONCE=0; ONLY_TASK=""; AUTO_MERGE=1
while [ $# -gt 0 ]; do case "$1" in
  --once) ONCE=1 ;;
  --task) ONLY_TASK="${2:-}"; shift ;;
  --no-auto-merge) AUTO_MERGE=0 ;;
  *) echo "unknown arg: $1" >&2; exit 2 ;;
esac; shift; done

mkdir -p "$LOGDIR" "$(dirname "$STATE")"

notify(){ "$NOTIFY" "$1" || true; }
st_set(){ python3 "$STATE_PY" set "$STATE" "$1" "$2" "$3"; }
task_title(){ python3 "$STATE_PY" title "$BACKLOG" "$1"; }
task_specfile(){ python3 "$STATE_PY" specfile "$BACKLOG" "$1"; }
git_repo(){ git -C "$REPO" "$@"; }
gh_repo(){ ( cd "$REPO" && gh "$@" ); }

render(){ python3 - "$1" <<'PY'
import os, sys
t = open(sys.argv[1]).read()
for k in ("BRANCH", "TASK_TITLE", "TASK_SPEC", "CI_FAILURE"):
    t = t.replace("{{%s}}" % k, os.environ.get(k, ""))
sys.stdout.write(t)
PY
}

run_claude(){
  local prompt="$1" log="$LOGDIR/claude-$(date +%s)-$$.log"
  ( cd "$REPO" && timeout "$CLAUDE_TIMEOUT" claude -p "$prompt" --dangerously-skip-permissions ) >"$log" 2>&1
  local rc=$?
  echo "claude rc=$rc; tail:" ; tail -2 "$log"
  return $rc
}

ci_run_for(){  # <branch> <sha> -> run id (waits for the run to appear)
  local branch="$1" sha="$2" id=""
  for _ in $(seq 1 12); do
    id=$(gh_repo run list --branch "$branch" -L 10 --json databaseId,headSha \
         --jq ".[] | select(.headSha==\"$sha\") | .databaseId" 2>/dev/null | head -1)
    [ -n "$id" ] && { echo "$id"; return; }
    sleep 10
  done
  echo ""
}

ci_wait(){  # <run_id> -> conclusion
  local id="$1" end=$(( $(date +%s) + CI_TIMEOUT ))
  while [ "$(date +%s)" -lt "$end" ]; do
    if [ "$(gh_repo run view "$id" --json status --jq .status 2>/dev/null)" = "completed" ]; then
      gh_repo run view "$id" --json conclusion --jq .conclusion 2>/dev/null
      return
    fi
    sleep "$POLL_INTERVAL"
  done
  echo "timeout"
}

do_task(){
  local tid="$1" branch title sha run_id="" attempt=0 conclusion=""
  title=$(task_title "$tid"); branch="auto/$tid"
  st_set "$tid" status in_progress; st_set "$tid" branch "$branch"
  notify "starting: $title"

  if ! ( git_repo fetch -q origin && git_repo checkout -q -B "$branch" origin/main ); then
    notify "BLOCKED $tid: git checkout failed"; st_set "$tid" status blocked; return 1
  fi

  export BRANCH="$branch" TASK_TITLE="$title" CI_FAILURE=""
  export TASK_SPEC="$(cat "$AUTONOMY_DIR/$(task_specfile "$tid")" 2>/dev/null)"
  run_claude "$(render "$PROMPTS/implement.md")" || true

  git_repo fetch -q origin "$branch" 2>/dev/null
  sha=$(git_repo rev-parse "origin/$branch" 2>/dev/null || true)
  if [ -z "$sha" ]; then
    notify "BLOCKED $tid: agent pushed nothing (build/auth issue?)"; st_set "$tid" status blocked; return 1
  fi

  while :; do
    run_id=$(ci_run_for "$branch" "$sha")
    if [ -z "$run_id" ]; then
      notify "BLOCKED $tid: no CI run found for ${sha:0:7}"; st_set "$tid" status blocked; return 1
    fi
    st_set "$tid" run_id "$run_id"
    notify "$tid pushed ${sha:0:7}; CI run $run_id running"
    conclusion=$(ci_wait "$run_id")
    if [ "$conclusion" != "success" ]; then           # one rerun to absorb flake
      gh_repo run rerun "$run_id" >/dev/null 2>&1 && conclusion=$(ci_wait "$run_id")
    fi
    [ "$conclusion" = "success" ] && break

    attempt=$((attempt + 1)); st_set "$tid" fix_attempts "$attempt"
    if [ "$attempt" -gt "$MAX_FIX" ]; then
      notify "BLOCKED $tid after $MAX_FIX fixes (CI $conclusion). Branch $branch kept for review."
      st_set "$tid" status blocked; return 1
    fi
    notify "$tid CI $conclusion (fix $attempt/$MAX_FIX), diagnosing"
    export CI_FAILURE="$(gh_repo run view "$run_id" --log-failed 2>/dev/null | tail -150)"
    run_claude "$(render "$PROMPTS/fix.md")" || true
    git_repo fetch -q origin "$branch" 2>/dev/null
    sha=$(git_repo rev-parse "origin/$branch" 2>/dev/null || true)
    [ -z "$sha" ] && { notify "BLOCKED $tid: fix push missing"; st_set "$tid" status blocked; return 1; }
  done

  if [ "$AUTO_MERGE" = "1" ]; then
    if ( git_repo checkout -q main && git_repo pull -q --ff-only origin main \
         && git_repo merge -q --no-ff "$branch" -m "Merge $branch: $title" \
         && git_repo push -q origin main ); then
      git_repo push -q origin --delete "$branch" 2>/dev/null || true
      notify "MERGED $tid to main (CI $run_id green)"
      st_set "$tid" status done
    else
      notify "BLOCKED $tid: green but merge to main failed (conflict?). Branch $branch kept."
      st_set "$tid" status blocked; return 1
    fi
  else
    notify "GREEN $tid on $branch (CI $run_id). Review/merge: https://github.com/$REPO_SLUG/tree/$branch"
    st_set "$tid" status done
  fi
}

main(){
  exec 9>"$LOCK"
  flock -n 9 || { echo "another supervisor holds the lock; exiting"; exit 0; }

  python3 "$STATE_PY" init "$BACKLOG" "$STATE"
  notify "supervisor up ($(python3 "$STATE_PY" summary "$STATE"); auto_merge=$AUTO_MERGE)"

  if [ -n "$ONLY_TASK" ]; then do_task "$ONLY_TASK" || true; exit 0; fi

  while :; do
    local tid; tid=$(python3 "$STATE_PY" next "$BACKLOG" "$STATE")
    if [ -z "$tid" ]; then
      notify "backlog complete ($(python3 "$STATE_PY" summary "$STATE")); idling"
      [ "$ONCE" = "1" ] && exit 0
      sleep "$IDLE_SLEEP"; python3 "$STATE_PY" init "$BACKLOG" "$STATE"; continue
    fi
    do_task "$tid" || true
    [ "$ONCE" = "1" ] && exit 0
  done
}
main
