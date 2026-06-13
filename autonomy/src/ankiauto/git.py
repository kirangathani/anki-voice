"""The ONLY place `git` is spawned. Branch/merge via the deploy key (path only,
never logged). Never force-pushes; never pushes straight to a feature push (the
coding agent pushes its own branch; this module fetches, branches, and merges)."""
from __future__ import annotations

import subprocess
from pathlib import Path
from typing import Protocol


class Git(Protocol):
    def fetch(self) -> None: ...
    def fresh_branch(self, name: str) -> None: ...
    def head_sha(self, ref: str) -> str | None: ...
    def merge_to_main(self, branch: str, message: str) -> bool: ...
    def diff_touches(self, branch: str, paths: list[str]) -> bool: ...


class RealGit:
    def __init__(self, cwd: str, base_branch: str = "origin/main",
                 deploy_key: str | None = None) -> None:
        self._cwd = str(Path(cwd).expanduser())
        self._base = base_branch
        self._env = None
        if deploy_key:
            import os
            self._env = {**os.environ,
                         "GIT_SSH_COMMAND": f"ssh -i {Path(deploy_key).expanduser()} -o IdentitiesOnly=yes"}

    def _git(self, *args: str, timeout: int = 120) -> subprocess.CompletedProcess:
        return subprocess.run(["git", *args], cwd=self._cwd, env=self._env,
                              capture_output=True, text=True, timeout=timeout)

    def fetch(self) -> None:
        self._git("fetch", "-q", "origin")

    def fresh_branch(self, name: str) -> None:
        # idempotent: resets the local branch to the base; discards stale local work
        self._git("checkout", "-q", "-B", name, self._base)

    def head_sha(self, ref: str) -> str | None:
        p = self._git("rev-parse", ref)
        return p.stdout.strip() if p.returncode == 0 and p.stdout.strip() else None

    def diff_touches(self, branch: str, paths: list[str]) -> bool:
        p = self._git("diff", "--name-only", f"origin/main...{branch}")
        if p.returncode != 0:
            return True   # fail safe: if we can't tell, treat as touched -> block merge
        changed = [ln.strip() for ln in p.stdout.splitlines() if ln.strip()]
        return any(c.startswith(pre) for c in changed for pre in paths)

    def merge_to_main(self, branch: str, message: str) -> bool:
        steps = [
            ("checkout", "-q", "main"),
            ("pull", "-q", "--ff-only", "origin", "main"),
            ("merge", "-q", "--no-ff", branch, "-m", message),
            ("push", "-q", "origin", "main"),
        ]
        for s in steps:
            if self._git(*s).returncode != 0:
                return False
        self._git("push", "-q", "origin", "--delete", branch)   # best-effort cleanup
        return True
