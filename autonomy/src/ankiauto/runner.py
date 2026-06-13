"""The ONLY place `claude -p` is spawned. Detects usage limits from output.
Never puts Anthropic credentials in argv/prompt (Claude uses the host's Max
OAuth login)."""
from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Protocol

from . import quota
from .models import ClaudeResult


class ClaudeRunner(Protocol):
    def run(self, prompt: str, cwd: str, *, model: str, timeout_s: int) -> ClaudeResult: ...


class SubprocessClaudeRunner:
    def run(self, prompt: str, cwd: str, *, model: str, timeout_s: int) -> ClaudeResult:
        argv = ["claude", "-p", prompt, "--model", model, "--dangerously-skip-permissions"]
        try:
            proc = subprocess.run(
                argv, cwd=str(Path(cwd).expanduser()),
                capture_output=True, text=True, timeout=timeout_s,
            )
            out, err, rc = proc.stdout, proc.stderr, proc.returncode
        except subprocess.TimeoutExpired as e:
            out = e.stdout or ""
            err = (e.stderr or "") + "\n[runner] claude timed out"
            rc = 124
        sig = quota.detect_quota(out, err, rc)
        return ClaudeResult(exit_code=rc, stdout=out, stderr=err, quota_signal=sig)


class ProbeRunner:
    """Tiny quota probe: a 1-token call to see if the Max quota is back."""

    def __init__(self, inner: ClaudeRunner, model: str) -> None:
        self._inner = inner
        self._model = model

    def probe_ok(self, cwd: str) -> bool:
        res = self._inner.run("Reply with: READY", cwd, model=self._model, timeout_s=90)
        return not res.quota_hit and res.exit_code == 0
