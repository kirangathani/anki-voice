"""The ONLY place OpenClaw is invoked: Telegram notifications via the v1
notify.sh wrapper (openclaw agent --deliver). OpenClaw is the messaging channel,
not the engine."""
from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Protocol


class Notifier(Protocol):
    def send(self, message: str) -> None: ...


class OpenClawNotifier:
    def __init__(self, notify_script: str) -> None:
        self._script = str(Path(notify_script).expanduser())

    def send(self, message: str) -> None:
        try:
            subprocess.run(["bash", self._script, message], timeout=120,
                           capture_output=True, text=True)
        except Exception:
            pass   # never let a notification failure crash the loop


class NullNotifier:
    def send(self, message: str) -> None:
        pass
