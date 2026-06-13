"""Core data types. Pure: no IO, no subprocess. Safe to import anywhere."""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum


class TaskStatus(str, Enum):
    PENDING = "pending"
    IN_PROGRESS = "in_progress"
    DONE = "done"
    BLOCKED = "blocked"
    PAUSED_QUOTA = "paused_quota"


class QuotaPhase(str, Enum):
    ACTIVE = "active"
    PAUSED = "paused"
    PROBING = "probing"


@dataclass(frozen=True)
class Task:
    """A unit of work from the backlog (immutable spec)."""
    id: str
    title: str
    spec: str            # the full task spec markdown (rendered into the prompt)
    project: str = "anki-voice"
    type: str = "implement"   # selects a Step pipeline; extensibility hook


@dataclass
class TaskRuntime:
    """Mutable per-task progress; lives in the durable state file."""
    status: TaskStatus = TaskStatus.PENDING
    branch: str | None = None
    head_sha: str | None = None
    ci_run_id: int | None = None
    fix_attempts: int = 0
    session_id: str | None = None   # claude --resume continuity for fix turns
    last_error: str | None = None
    updated_at: str | None = None


@dataclass(frozen=True)
class ResetHint:
    """Parsed (best-effort) Max-limit reset info from a claude limit message."""
    raw: str                  # e.g. "3:45pm" / "Mon 12:00am"
    scope: str                # "session" | "weekly" | "model" | "unknown"
    parsed_epoch: float | None  # None when unparseable -> fall back to backoff


@dataclass(frozen=True)
class QuotaSignal:
    """Result of detecting a usage-limit condition in a claude invocation."""
    scope: str
    reset_hint: ResetHint | None
    source: str               # "stderr" | "stdout" | "json" (for debugging)


@dataclass
class QuotaState:
    phase: QuotaPhase = QuotaPhase.ACTIVE
    reset_hint: ResetHint | None = None
    paused_at: float | None = None
    next_probe_at: float | None = None
    probe_count: int = 0


@dataclass
class ClaudeResult:
    exit_code: int
    stdout: str
    stderr: str
    quota_signal: QuotaSignal | None = None   # set when a usage limit was detected
    session_id: str | None = None
    pushed_sha: str | None = None

    @property
    def quota_hit(self) -> bool:
        return self.quota_signal is not None


@dataclass
class CIResult:
    run_id: int | None
    status: str               # "queued" | "in_progress" | "completed" | "missing"
    conclusion: str | None = None   # "success" | "failure" | "cancelled" | ...
    failed_log: str | None = None
