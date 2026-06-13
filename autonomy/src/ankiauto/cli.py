"""argparse CLI + engine factory. Subcommands: run, run-once, status, pause,
resume, add-task, reconcile, health, quota-sim."""
from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

from . import backlog as backlog_mod
from . import config as config_mod
from . import logging_setup, quota
from .ci import GhCI
from .clock import RealClock
from .engine import Engine
from .git import RealGit
from .health import Health
from .models import TaskStatus
from .notify import OpenClawNotifier
from .runner import ProbeRunner, SubprocessClaudeRunner
from .state import StateStore

DEFAULT_CFG = "~/autonomy/config.json"


def _build(cfg_path: str, project: str | None):
    cfg = config_mod.load(cfg_path)
    p = cfg.projects[project] if project else cfg.default_project
    state = StateStore.load(Path(cfg.paths.state_dir).expanduser() / f"{p.name}.json")
    tasks = backlog_mod.load_backlog(p.backlog, p.spec_root)
    log = logging_setup.setup(cfg.paths.log_file)
    runner = SubprocessClaudeRunner()
    eng = Engine(
        cfg, p, state=state, tasks=tasks, runner=runner,
        probe=ProbeRunner(runner, cfg.engine.model),
        ci=GhCI(p.work_clone), git=RealGit(p.work_clone, p.base_branch, p.deploy_key),
        notify=OpenClawNotifier(cfg.notify.notify_script), health=Health(cfg.paths.status_file),
        clock=RealClock(), log=log,
    )
    return cfg, eng


def _flock(lock_file: str):
    import fcntl
    Path(lock_file).expanduser().parent.mkdir(parents=True, exist_ok=True)
    fh = open(Path(lock_file).expanduser(), "w")
    try:
        fcntl.flock(fh, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        print("another ankiauto holds the lock; exiting", file=sys.stderr)
        sys.exit(0)
    return fh   # keep handle alive for process lifetime


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="ankiauto")
    ap.add_argument("--config", default=DEFAULT_CFG)
    ap.add_argument("--project", default=None)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("run")
    ro = sub.add_parser("run-once"); ro.add_argument("--task"); ro.add_argument("--no-auto-merge", action="store_true")
    sub.add_parser("status"); sub.add_parser("pause"); sub.add_parser("resume")
    at = sub.add_parser("add-task"); at.add_argument("--id", required=True); at.add_argument("--title", required=True); at.add_argument("--spec", required=True)
    rc = sub.add_parser("reconcile"); rc.add_argument("--import-bash")
    he = sub.add_parser("health"); he.add_argument("--emit", action="store_true")
    qs = sub.add_parser("quota-sim"); qs.add_argument("--phrase", required=True)
    a = ap.parse_args(argv)

    if a.cmd == "run":
        cfg, eng = _build(a.config, a.project)
        _flock(cfg.paths.lock_file)
        eng.run_forever()
        return 0

    if a.cmd == "run-once":
        _, eng = _build(a.config, a.project)
        eng.run_once(task_id=a.task, auto_merge=not a.no_auto_merge)
        return 0

    if a.cmd == "status":
        _, eng = _build(a.config, a.project)
        s = eng.state
        print(json.dumps({
            "quota_phase": s.quota.phase.value,
            "reset_hint": s.quota.reset_hint.raw if s.quota.reset_hint else None,
            "manual_paused": s.paused,
            "counts": backlog_mod.summary(eng.tasks, s),
            "tasks": {t.id: s.runtime(t.id).status.value for t in eng.tasks},
        }, indent=2))
        return 0

    if a.cmd in ("pause", "resume"):
        _, eng = _build(a.config, a.project)
        eng.state.paused = (a.cmd == "pause")
        eng.state.persist()
        print(f"manual_paused = {eng.state.paused}")
        return 0

    if a.cmd == "add-task":
        cfg, eng = _build(a.config, a.project)
        bp = Path(eng.p.backlog)
        data = json.loads(bp.read_text())
        if any(t["id"] == a.id for t in data["tasks"]):
            print("task id exists", file=sys.stderr); return 1
        data["tasks"].append({"id": a.id, "title": a.title, "spec": a.spec})
        bp.write_text(json.dumps(data, indent=2))
        print(f"added {a.id}")
        return 0

    if a.cmd == "reconcile":
        _, eng = _build(a.config, a.project)
        if a.import_bash:
            bash = json.loads(Path(a.import_bash).expanduser().read_text())
            for tid, r in bash.get("tasks", {}).items():
                eng.state.mark(tid, TaskStatus(r.get("status", "pending")),
                               branch=r.get("branch"), fix_attempts=r.get("fix_attempts", 0),
                               ci_run_id=r.get("run_id"))
            eng.state.persist()
            print(f"imported {len(bash.get('tasks', {}))} task states from bash")
        print(json.dumps(backlog_mod.summary(eng.tasks, eng.state), indent=2))
        return 0

    if a.cmd == "health":
        _, eng = _build(a.config, a.project)
        eng._emit_health(None)
        if a.emit:
            eng.notify.send("engine health: %s" % json.dumps(backlog_mod.summary(eng.tasks, eng.state)))
        print(Path(eng.cfg.paths.status_file).expanduser().read_text())
        return 0

    if a.cmd == "quota-sim":
        cfg, eng = _build(a.config, a.project)
        sig = quota.detect_quota("", a.phrase, 1)
        if not sig:
            print("NOT detected as a quota limit"); return 1
        ep = quota.parse_reset(sig.reset_hint.raw if sig.reset_hint else None, RealClock().now(), cfg.quota.tz)
        delay = quota.next_probe_delay(0, sig.reset_hint, RealClock().now(), jitter_s=0)
        print(json.dumps({"detected": True, "scope": sig.scope,
                          "reset_raw": sig.reset_hint.raw if sig.reset_hint else None,
                          "parsed_epoch": ep, "first_probe_in_s": round(delay)}, indent=2))
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
