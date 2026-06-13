"""Typed config load/validate from config.yaml. No secrets here (those live in
secrets.env). Fail-fast with clear errors; expands ~ in paths."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


def _expand(p: str) -> str:
    return str(Path(p).expanduser())


@dataclass(frozen=True)
class EngineCfg:
    model: str = "claude-opus-4-8"
    fallback_model: str | None = None
    poll_interval_s: int = 30
    idle_interval_s: int = 600
    max_fix: int = 5
    task_wallclock_cap_s: int = 3600
    run_wallclock_cap_s: int = 28800
    active_hours: str | None = None      # e.g. "06:00-23:00"; None = 24/7
    claude_timeout_s: int = 1800


@dataclass(frozen=True)
class QuotaCfg:
    probe_base_s: int = 300
    probe_cap_s: int = 1800
    weekly_floor_s: int = 3600
    jitter_s: int = 60
    tz: str = "Europe/London"


@dataclass(frozen=True)
class PathsCfg:
    state_dir: str = "~/autonomy/state"
    status_file: str = "~/autonomy/state/status.json"
    log_file: str = "~/autonomy/logs/engine.jsonl"
    lock_file: str = "~/autonomy/engine.lock"


@dataclass(frozen=True)
class NotifyCfg:
    channel: str = "telegram"
    chat_id: str = "7853471624"
    openclaw_agent: str = "main"
    notify_script: str = "~/autonomy/bin/notify.sh"   # reuse the v1 notifier


@dataclass(frozen=True)
class ProjectCfg:
    name: str
    repo_slug: str                # "kirangathani/anki-voice"
    work_clone: str
    base_branch: str = "origin/main"
    gh_token_env: str = "GH_TOKEN"
    deploy_key: str | None = None
    backlog: str = "~/autonomy/backlog.json"
    spec_root: str = "~/autonomy"
    prompts_dir: str = "~/autonomy/prompts"
    protected_paths: tuple[str, ...] = (".github/workflows", "autonomy/")


@dataclass(frozen=True)
class Config:
    engine: EngineCfg
    quota: QuotaCfg
    paths: PathsCfg
    notify: NotifyCfg
    projects: dict[str, ProjectCfg]

    @property
    def default_project(self) -> ProjectCfg:
        return next(iter(self.projects.values()))


def load(path: str | Path) -> Config:
    import json  # config is JSON: zero third-party deps, runs on system python3
    raw = json.loads(Path(path).expanduser().read_text() or "{}")
    projects: dict[str, ProjectCfg] = {}
    for name, p in (raw.get("projects") or {}).items():
        if "repo_slug" not in p and "repo" in p:
            # accept "github.com/owner/repo" and reduce to owner/repo
            p["repo_slug"] = p["repo"].split("github.com/")[-1].strip("/")
        projects[name] = ProjectCfg(
            name=name,
            repo_slug=p["repo_slug"],
            work_clone=_expand(p["work_clone"]),
            base_branch=p.get("base_branch", "origin/main"),
            gh_token_env=p.get("gh_token_env", "GH_TOKEN"),
            deploy_key=_expand(p["deploy_key"]) if p.get("deploy_key") else None,
            backlog=_expand(p.get("backlog", "~/autonomy/backlog.json")),
            spec_root=_expand(p.get("spec_root", "~/autonomy")),
            prompts_dir=_expand(p.get("prompts_dir", "~/autonomy/prompts")),
            protected_paths=tuple(p.get("protected_paths", [".github/workflows", "autonomy/"])),
        )
    if not projects:
        raise ValueError("config: at least one project is required")

    pe = raw.get("engine") or {}
    pq = raw.get("quota") or {}
    pp = raw.get("paths") or {}
    pn = raw.get("notify") or {}
    paths = PathsCfg(
        state_dir=_expand(pp.get("state_dir", "~/autonomy/state")),
        status_file=_expand(pp.get("status_file", "~/autonomy/state/status.json")),
        log_file=_expand(pp.get("log_file", "~/autonomy/logs/engine.jsonl")),
        lock_file=_expand(pp.get("lock_file", "~/autonomy/engine.lock")),
    )
    notify = NotifyCfg(
        channel=pn.get("channel", "telegram"), chat_id=str(pn.get("chat_id", "7853471624")),
        openclaw_agent=pn.get("openclaw_agent", "main"),
        notify_script=_expand(pn.get("notify_script", "~/autonomy/bin/notify.sh")),
    )
    return Config(
        engine=EngineCfg(**{k: pe[k] for k in pe if k in EngineCfg.__dataclass_fields__}),
        quota=QuotaCfg(**{k: pq[k] for k in pq if k in QuotaCfg.__dataclass_fields__}),
        paths=paths, notify=notify, projects=projects,
    )
