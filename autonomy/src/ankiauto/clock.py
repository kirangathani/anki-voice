"""Injectable clock so backoff/quota timing is testable without real time."""
from __future__ import annotations

import time
from typing import Protocol


class Clock(Protocol):
    def now(self) -> float: ...
    def sleep(self, seconds: float) -> None: ...


class RealClock:
    def now(self) -> float:
        return time.time()

    def sleep(self, seconds: float) -> None:
        time.sleep(max(0.0, seconds))


class FakeClock:
    """Deterministic clock for tests. sleep() advances virtual time instantly."""

    def __init__(self, start: float = 1_000_000.0) -> None:
        self._t = start
        self.sleeps: list[float] = []

    def now(self) -> float:
        return self._t

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self._t += max(0.0, seconds)

    def advance(self, seconds: float) -> None:
        self._t += seconds
