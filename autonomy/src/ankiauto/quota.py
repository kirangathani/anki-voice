"""Max-subscription usage-limit handling. PURE: detection + reset parsing +
pause/probe state transitions. No IO. Fully table-testable.

Claude Code prints, on hitting the Max limit, lines like:
    You've hit your session limit · resets 3:45pm
    You've hit your weekly limit · resets Mon 12:00am
    You've hit your <model> limit · resets 3:45pm
The reset time is human-readable and fuzzy, so resume is PROBE-driven, never
sleep-until-parsed-time. parse_reset() is best-effort and never raises.
"""
from __future__ import annotations

import json
import random
import re
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from .models import QuotaPhase, QuotaSignal, QuotaState, ResetHint

# A usage/quota limit phrase (NOT transient retries). Case-insensitive.
_LIMIT_RE = re.compile(
    r"hit your\s+\w*\s*limit|usage limit|rate limit reached|limit reached|usage cap",
    re.IGNORECASE,
)
_SCOPE_RE = re.compile(r"hit your\s+(session|weekly|\w+)\s+limit", re.IGNORECASE)
_RESET_RE = re.compile(r"reset[s]?\s+(.+?)\s*$", re.IGNORECASE)

_WEEKDAYS = {"mon": 0, "tue": 1, "wed": 2, "thu": 3, "fri": 4, "sat": 5, "sun": 6}
_TIME_RE = re.compile(r"(\d{1,2})(?::(\d{2}))?\s*([ap]m)", re.IGNORECASE)


def detect_quota(
    stdout: str, stderr: str, exit_code: int, json_obj: dict | None = None
) -> QuotaSignal | None:
    """Return a QuotaSignal if this invocation hit a usage limit, else None.

    The limit PHRASE is authoritative (exit code is undocumented/secondary): a
    phrase match is a quota hit regardless of exit code; a nonzero exit WITHOUT
    the phrase is a normal task failure, not quota.
    """
    for source, blob in (("stderr", stderr or ""), ("stdout", stdout or "")):
        for line in blob.splitlines():
            if not _LIMIT_RE.search(line):
                continue
            scope = "unknown"
            ms = _SCOPE_RE.search(line)
            if ms:
                s = ms.group(1).lower()
                scope = s if s in ("session", "weekly") else "model"
            raw = None
            mr = _RESET_RE.search(line)
            if mr:
                raw = mr.group(1).strip(" .·\t")
            hint = ResetHint(raw=raw, scope=scope, parsed_epoch=None) if raw else None
            return QuotaSignal(scope=scope, reset_hint=hint, source=source)

    if json_obj:  # defensive: undocumented schema, dict-walk
        blob = json.dumps(json_obj).lower()
        if "limit" in blob and ("reset" in blob or "usage" in blob):
            return QuotaSignal(scope="unknown", reset_hint=None, source="json")
    return None


def _parse_clock(s: str) -> tuple[int, int] | None:
    m = _TIME_RE.search(s)
    if not m:
        return None
    hour = int(m.group(1)) % 12
    if m.group(3).lower() == "pm":
        hour += 12
    return hour, int(m.group(2) or 0)


def parse_reset(raw: str | None, now: float, tz_name: str = "Europe/London") -> float | None:
    """Best-effort epoch for a reset phrase like '3:45pm' or 'Mon 12:00am'.
    Returns None when unparseable (caller then relies on backoff probing)."""
    if not raw:
        return None
    try:
        tz = ZoneInfo(tz_name)
    except Exception:
        tz = None
    try:
        base = datetime.fromtimestamp(now, tz)
        text = raw.lower().strip()
        weekday = None
        mwd = re.match(r"(mon|tue|wed|thu|fri|sat|sun)", text)
        if mwd:
            weekday = _WEEKDAYS[mwd.group(1)]
            text = text[mwd.end():].strip()
        hm = _parse_clock(text)
        if hm is None:
            return None
        target = base.replace(hour=hm[0], minute=hm[1], second=0, microsecond=0)
        if weekday is not None:
            days = (weekday - base.weekday()) % 7
            if days == 0 and target <= base:
                days = 7
            target += timedelta(days=days)
        elif target <= base:
            target += timedelta(days=1)
        return target.timestamp()
    except Exception:
        return None


def next_probe_delay(
    probe_count: int,
    hint: ResetHint | None,
    now: float,
    *,
    base_s: float = 300,
    cap_s: float = 1800,
    weekly_floor_s: float = 3600,
    jitter_s: float = 60,
) -> float:
    """Seconds to wait before the next quota probe."""
    if probe_count == 0 and hint and hint.parsed_epoch and hint.parsed_epoch > now:
        return (hint.parsed_epoch - now) + 60.0  # just after the stated reset
    delay = min(cap_s, base_s * (2 ** probe_count))
    if hint and hint.scope == "weekly":
        delay = max(delay, weekly_floor_s)
    return delay + (random.uniform(0, jitter_s) if jitter_s else 0.0)


def enter_paused(signal: QuotaSignal, now: float, **probe_kw) -> QuotaState:
    return QuotaState(
        phase=QuotaPhase.PAUSED,
        reset_hint=signal.reset_hint,
        paused_at=now,
        probe_count=0,
        next_probe_at=now + next_probe_delay(0, signal.reset_hint, now, **probe_kw),
    )


def due_for_probe(qs: QuotaState, now: float) -> bool:
    return qs.phase == QuotaPhase.PAUSED and qs.next_probe_at is not None and now >= qs.next_probe_at


def on_probe_result(qs: QuotaState, ok: bool, now: float, **probe_kw) -> QuotaState:
    if ok:
        return QuotaState(phase=QuotaPhase.ACTIVE)
    count = qs.probe_count + 1
    return QuotaState(
        phase=QuotaPhase.PAUSED,
        reset_hint=qs.reset_hint,
        paused_at=qs.paused_at,
        probe_count=count,
        next_probe_at=now + next_probe_delay(count, qs.reset_hint, now, **probe_kw),
    )
