#!/usr/bin/env python3
"""State + backlog helper for the anki-voice autonomy supervisor.

Keeps per-task status in a JSON state file so the loop is crash-resumable.
All subcommands print to stdout; the supervisor (bash) consumes them.

Usage:
  state.py init     <backlog.json> <state.json>     # ensure every backlog task exists (default pending)
  state.py next     <backlog.json> <state.json>     # print id of next task (in_progress first, else first pending); empty if none
  state.py title    <backlog.json> <task_id>        # print task title
  state.py specfile <backlog.json> <task_id>        # print task spec path (relative to autonomy/)
  state.py get      <state.json> <task_id> <field>  # print a field (status|branch|run_id|fix_attempts)
  state.py set      <state.json> <task_id> <field> <value>
  state.py summary  <state.json>                    # "done=N blocked=M pending=K in_progress=J"
"""
import json
import sys
from datetime import datetime, timezone


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except FileNotFoundError:
        return {}


def save(path, obj):
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)


def backlog_ids(backlog):
    return [t["id"] for t in backlog.get("tasks", [])]


def main():
    cmd = sys.argv[1]

    if cmd == "init":
        backlog, state_path = load(sys.argv[2]), sys.argv[3]
        state = load(state_path)
        tasks = state.setdefault("tasks", {})
        for tid in backlog_ids(backlog):
            tasks.setdefault(tid, {
                "status": "pending", "branch": None, "run_id": None,
                "fix_attempts": 0, "updated": None,
            })
        save(state_path, state)
        return

    if cmd == "next":
        backlog, state = load(sys.argv[2]), load(sys.argv[3])
        tasks = state.get("tasks", {})
        order = backlog_ids(backlog)
        # resume an in_progress task first
        for tid in order:
            if tasks.get(tid, {}).get("status") == "in_progress":
                print(tid)
                return
        for tid in order:
            if tasks.get(tid, {}).get("status", "pending") == "pending":
                print(tid)
                return
        print("")
        return

    if cmd == "title":
        backlog = load(sys.argv[2])
        for t in backlog.get("tasks", []):
            if t["id"] == sys.argv[3]:
                print(t.get("title", t["id"]))
                return
        print(sys.argv[3])
        return

    if cmd == "specfile":
        backlog = load(sys.argv[2])
        for t in backlog.get("tasks", []):
            if t["id"] == sys.argv[3]:
                print(t.get("spec", ""))
                return
        print("")
        return

    if cmd == "get":
        state = load(sys.argv[2])
        print(state.get("tasks", {}).get(sys.argv[3], {}).get(sys.argv[4], ""))
        return

    if cmd == "set":
        state_path, tid, field, value = sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5]
        state = load(state_path)
        task = state.setdefault("tasks", {}).setdefault(tid, {})
        if field == "fix_attempts":
            task[field] = int(value)
        else:
            task[field] = value if value != "null" else None
        task["updated"] = datetime.now(timezone.utc).isoformat()
        save(state_path, state)
        return

    if cmd == "summary":
        state = load(sys.argv[2])
        counts = {"done": 0, "blocked": 0, "pending": 0, "in_progress": 0}
        for t in state.get("tasks", {}).values():
            counts[t.get("status", "pending")] = counts.get(t.get("status", "pending"), 0) + 1
        print(" ".join(f"{k}={v}" for k, v in counts.items()))
        return

    sys.exit(f"unknown command: {cmd}")


if __name__ == "__main__":
    main()
