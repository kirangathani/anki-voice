"""Durable, crash-resumable state. Atomic writes (tmp+rename). Pure-ish:
serialization logic is testable; only the file write touches disk."""
from __future__ import annotations

import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from .models import QuotaPhase, QuotaState, ResetHint, TaskRuntime, TaskStatus


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _runtime_to_dict(r: TaskRuntime) -> dict:
    return {
        "status": r.status.value, "branch": r.branch, "head_sha": r.head_sha,
        "ci_run_id": r.ci_run_id, "fix_attempts": r.fix_attempts,
        "session_id": r.session_id, "last_error": r.last_error, "updated_at": r.updated_at,
    }


def _runtime_from_dict(d: dict) -> TaskRuntime:
    return TaskRuntime(
        status=TaskStatus(d.get("status", "pending")), branch=d.get("branch"),
        head_sha=d.get("head_sha"), ci_run_id=d.get("ci_run_id"),
        fix_attempts=d.get("fix_attempts", 0), session_id=d.get("session_id"),
        last_error=d.get("last_error"), updated_at=d.get("updated_at"),
    )


def _quota_to_dict(q: QuotaState) -> dict:
    h = q.reset_hint
    return {
        "phase": q.phase.value,
        "reset_hint": {"raw": h.raw, "scope": h.scope, "parsed_epoch": h.parsed_epoch} if h else None,
        "paused_at": q.paused_at, "next_probe_at": q.next_probe_at, "probe_count": q.probe_count,
    }


def _quota_from_dict(d: dict | None) -> QuotaState:
    if not d:
        return QuotaState()
    h = d.get("reset_hint")
    return QuotaState(
        phase=QuotaPhase(d.get("phase", "active")),
        reset_hint=ResetHint(h["raw"], h["scope"], h.get("parsed_epoch")) if h else None,
        paused_at=d.get("paused_at"), next_probe_at=d.get("next_probe_at"),
        probe_count=d.get("probe_count", 0),
    )


class StateStore:
    """One JSON file: per-task runtimes + global quota state + manual pause flag."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path).expanduser()
        self.tasks: dict[str, TaskRuntime] = {}
        self.quota: QuotaState = QuotaState()
        self.paused: bool = False   # manual pause (CLI), distinct from quota pause

    @classmethod
    def load(cls, path: str | Path) -> "StateStore":
        s = cls(path)
        if s.path.exists():
            d = json.loads(s.path.read_text())
            s.tasks = {k: _runtime_from_dict(v) for k, v in d.get("tasks", {}).items()}
            s.quota = _quota_from_dict(d.get("quota"))
            s.paused = bool(d.get("paused", False))
        return s

    def to_dict(self) -> dict:
        return {
            "tasks": {k: _runtime_to_dict(v) for k, v in self.tasks.items()},
            "quota": _quota_to_dict(self.quota),
            "paused": self.paused,
            "updated_at": _now_iso(),
        }

    def persist(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=self.path.parent, suffix=".tmp")
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(self.to_dict(), f, indent=2)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, self.path)   # atomic
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)

    def runtime(self, task_id: str) -> TaskRuntime:
        r = self.tasks.get(task_id)
        if r is None:
            r = TaskRuntime()
            self.tasks[task_id] = r
        return r

    def mark(self, task_id: str, status: TaskStatus, **fields) -> None:
        r = self.runtime(task_id)
        r.status = status
        for k, v in fields.items():
            setattr(r, k, v)
        r.updated_at = _now_iso()
