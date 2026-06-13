"""Writes status.json: the cheap surface the OpenClaw heartbeat reads (a file
read, not an agent call) to report/restart without itself running the loop."""
from __future__ import annotations

import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from .models import QuotaPhase
from .state import StateStore


class Health:
    def __init__(self, status_file: str) -> None:
        self.path = Path(status_file).expanduser()

    def write(self, state: StateStore, *, current_task: str | None,
              counts: dict[str, int], note: str = "") -> None:
        q = state.quota
        doc = {
            "alive": True,
            "ts": datetime.now(timezone.utc).isoformat(),
            "current_task": current_task,
            "quota_phase": q.phase.value,
            "quota_paused": q.phase != QuotaPhase.ACTIVE,
            "reset_hint": q.reset_hint.raw if q.reset_hint else None,
            "next_probe_at": q.next_probe_at,
            "manual_paused": state.paused,
            "counts": counts,
            "note": note,
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=self.path.parent, suffix=".tmp")
        with os.fdopen(fd, "w") as f:
            json.dump(doc, f, indent=2)
        os.replace(tmp, self.path)
