"""The ONLY place `gh` is spawned. CI run discovery, polling, logs, flake rerun.
GH_TOKEN is read from the process env (injected by systemd EnvironmentFile);
never passed in argv, never logged."""
from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Protocol

from .models import CIResult


class CI(Protocol):
    def find_run(self, branch: str, head_sha: str) -> int | None: ...
    def poll(self, run_id: int) -> CIResult: ...
    def failed_log(self, run_id: int) -> str: ...
    def rerun(self, run_id: int) -> None: ...


class GhCI:
    def __init__(self, cwd: str, workflow: str = "CI") -> None:
        self._cwd = str(Path(cwd).expanduser())
        self._workflow = workflow

    def _gh(self, *args: str, timeout: int = 60) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["gh", *args], cwd=self._cwd, capture_output=True, text=True, timeout=timeout
        )

    def find_run(self, branch: str, head_sha: str) -> int | None:
        p = self._gh("run", "list", "--branch", branch, "-L", "10",
                     "--json", "databaseId,headSha,workflowName")
        if p.returncode != 0:
            return None
        try:
            runs = json.loads(p.stdout or "[]")
        except json.JSONDecodeError:
            return None
        for r in runs:
            if r.get("headSha") == head_sha and r.get("workflowName") == self._workflow:
                return int(r["databaseId"])
        # fall back to latest CI run on the branch if sha not matched yet
        for r in runs:
            if r.get("workflowName") == self._workflow:
                return int(r["databaseId"])
        return None

    def poll(self, run_id: int) -> CIResult:
        p = self._gh("run", "view", str(run_id), "--json", "status,conclusion")
        if p.returncode != 0:
            return CIResult(run_id=run_id, status="missing")
        d = json.loads(p.stdout or "{}")
        return CIResult(run_id=run_id, status=d.get("status", "unknown"),
                        conclusion=d.get("conclusion"))

    def failed_log(self, run_id: int) -> str:
        p = self._gh("run", "view", str(run_id), "--log-failed", timeout=120)
        log = p.stdout or ""
        return log[-12000:]   # tail; keep the prompt bounded

    def rerun(self, run_id: int) -> None:
        self._gh("run", "rerun", str(run_id))
