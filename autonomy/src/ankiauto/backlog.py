"""Backlog loading + next-task selection. Pure (selection is a pure function)."""
from __future__ import annotations

import json
from pathlib import Path

from .models import Task, TaskStatus
from .state import StateStore

# Statuses that mean "do not pick this task again".
_TERMINAL = {TaskStatus.DONE, TaskStatus.BLOCKED}
# Resume these before starting a new pending task.
_RESUMABLE = (TaskStatus.IN_PROGRESS, TaskStatus.PAUSED_QUOTA)


def load_backlog(backlog_path: str | Path, spec_root: str | Path) -> list[Task]:
    """Load ordered tasks. backlog file is JSON: {"tasks":[{id,title,spec,...}]}.
    `spec` is a path (relative to spec_root) to the task's markdown spec, which
    is read in full into Task.spec."""
    backlog_path = Path(backlog_path).expanduser()
    spec_root = Path(spec_root).expanduser()
    data = json.loads(backlog_path.read_text())
    tasks: list[Task] = []
    for t in data.get("tasks", []):
        spec_text = ""
        sp = t.get("spec")
        if sp:
            f = (spec_root / sp)
            spec_text = f.read_text() if f.exists() else sp  # allow inline spec
        tasks.append(Task(
            id=t["id"], title=t.get("title", t["id"]), spec=spec_text,
            project=t.get("project", "anki-voice"), type=t.get("type", "implement"),
        ))
    return tasks


def next_task(tasks: list[Task], state: StateStore) -> Task | None:
    """Resume an in_progress/paused task first (backlog order), else the first
    pending task. Skips done/blocked. Returns None when nothing is actionable."""
    for resumable in _RESUMABLE:
        for t in tasks:
            if state.runtime(t.id).status == resumable:
                return t
    for t in tasks:
        if state.runtime(t.id).status == TaskStatus.PENDING:
            return t
    return None


def summary(tasks: list[Task], state: StateStore) -> dict[str, int]:
    counts = {s.value: 0 for s in TaskStatus}
    for t in tasks:
        counts[state.runtime(t.id).status.value] += 1
    return counts
