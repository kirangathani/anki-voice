"""The orchestrator: per-task cycle FSM + the loop + resume + quota-aware
Claude runs. Depends on adapter Protocols + an injectable Clock, so the whole
FSM is testable with fakes and zero real claude/gh/git."""
from __future__ import annotations

import re
from datetime import datetime
from pathlib import Path

from . import backlog as backlog_mod
from . import quota
from .config import Config, ProjectCfg
from .models import (CIResult, QuotaPhase, ResetHint, Task, TaskStatus)


class Engine:
    def __init__(self, cfg: Config, project: ProjectCfg, *, state, tasks,
                 runner, probe, ci, git, notify, health, clock, log):
        self.cfg = cfg
        self.p = project
        self.state = state
        self.tasks = tasks
        self.runner = runner
        self.probe = probe
        self.ci = ci
        self.git = git
        self.notify = notify
        self.health = health
        self.clock = clock
        self.log = log
        self._idle_announced = False

    # ---- prompts -------------------------------------------------------
    def _render(self, name: str, task: Task, branch: str, ci_failure: str = "") -> str:
        text = (Path(self.p.prompts_dir) / name).read_text()
        repl = {"BRANCH": branch, "TASK_TITLE": task.title,
                "TASK_SPEC": task.spec, "CI_FAILURE": ci_failure}
        for k, v in repl.items():
            text = text.replace("{{%s}}" % k, v)
        return text

    # ---- quota ---------------------------------------------------------
    def _claude(self, prompt: str) -> "object":
        """Run claude; transparently wait out Max usage limits and retry."""
        while True:
            res = self.runner.run(prompt, self.p.work_clone,
                                  model=self.cfg.engine.model,
                                  timeout_s=self.cfg.engine.claude_timeout_s)
            if not res.quota_hit:
                return res
            self._handle_quota(res.quota_signal)

    def _handle_quota(self, signal) -> None:
        now = self.clock.now()
        hint = signal.reset_hint
        if hint and hint.raw:
            hint = ResetHint(hint.raw, hint.scope,
                             quota.parse_reset(hint.raw, now, self.cfg.quota.tz))
        else:
            hint = ResetHint(raw=signal.scope, scope=signal.scope, parsed_epoch=None)
        pk = dict(base_s=self.cfg.quota.probe_base_s, cap_s=self.cfg.quota.probe_cap_s,
                  weekly_floor_s=self.cfg.quota.weekly_floor_s, jitter_s=self.cfg.quota.jitter_s)
        self.state.quota = quota.enter_paused(
            type(signal)(scope=signal.scope, reset_hint=hint, source=signal.source), now, **pk)
        self.state.persist()
        self._emit_health(None)
        self.notify.send(f"burned through Max tokens ({signal.scope}); resets {hint.raw}. "
                         f"Pausing; will auto-resume when quota returns.")
        self.log.warning("quota_paused", extra={"quota_phase": "paused"})
        # probe loop
        while self.state.quota.phase != QuotaPhase.ACTIVE:
            wait = max(0.0, (self.state.quota.next_probe_at or now) - self.clock.now())
            self.clock.sleep(wait)
            ok = self.probe.probe_ok(self.p.work_clone)
            self.state.quota = quota.on_probe_result(self.state.quota, ok, self.clock.now(), **pk)
            self.state.persist()
        self.notify.send("Max tokens reset, resuming.")
        self.log.info("quota_resumed", extra={"quota_phase": "active"})

    # ---- CI ------------------------------------------------------------
    def _wait_ci(self, run_id: int) -> str:
        deadline = self.clock.now() + self.cfg.engine.task_wallclock_cap_s
        while self.clock.now() < deadline:
            r: CIResult = self.ci.poll(run_id)
            if r.status == "completed":
                return r.conclusion or "unknown"
            if r.status == "missing":
                return "missing"
            self.clock.sleep(self.cfg.engine.poll_interval_s)
        return "timeout"

    # ---- one task ------------------------------------------------------
    def _cycle(self, task: Task, auto_merge: bool = True) -> None:
        rt = self.state.runtime(task.id)
        branch = f"auto/{task.id}"
        self.state.mark(task.id, TaskStatus.IN_PROGRESS, branch=branch)
        self.state.persist()
        self.notify.send(f"starting: {task.title}")
        self.log.info("task_start", extra={"task_id": task.id})

        self.git.fetch()
        self.git.fresh_branch(branch)
        self._claude(self._render("implement.md", task, branch))
        sha = self.git.head_sha(f"origin/{branch}")
        if not sha:
            return self._block(task, "agent pushed nothing (build/auth issue?)")
        self.state.mark(task.id, TaskStatus.IN_PROGRESS, branch=branch, head_sha=sha)
        self.state.persist()

        while True:
            run_id = self.ci.find_run(branch, sha)
            if run_id is None:
                return self._block(task, f"no CI run found for {sha[:7]}")
            rt.ci_run_id = run_id
            self.state.persist()
            self.notify.send(f"{task.id} pushed {sha[:7]}; CI run {run_id} running")
            conclusion = self._wait_ci(run_id)
            if conclusion != "success":
                self.ci.rerun(run_id)            # absorb one flake
                conclusion = self._wait_ci(run_id)
            if conclusion == "success":
                break
            rt.fix_attempts += 1
            self.state.persist()
            if rt.fix_attempts > self.cfg.engine.max_fix:
                return self._block(task, f"{self.cfg.engine.max_fix} fixes exhausted (CI {conclusion}); branch {branch} kept")
            self.notify.send(f"{task.id} CI {conclusion} (fix {rt.fix_attempts}/{self.cfg.engine.max_fix})")
            log = self.ci.failed_log(run_id)
            self._claude(self._render("fix.md", task, branch, ci_failure=log))
            sha = self.git.head_sha(f"origin/{branch}")
            if not sha:
                return self._block(task, "fix push missing")
            rt.head_sha = sha
            self.state.persist()

        # green
        if not auto_merge:
            self.state.mark(task.id, TaskStatus.DONE)
            self.state.persist()
            self.notify.send(f"GREEN {task.id} on {branch} (CI {rt.ci_run_id}). "
                             f"Review/merge: https://github.com/{self.p.repo_slug}/tree/{branch}")
            return
        if self.git.diff_touches(branch, list(self.p.protected_paths)):
            return self._block(task, "green but diff touches protected paths (ci/autonomy); needs review")
        if self.git.merge_to_main(branch, f"Merge {branch}: {task.title}"):
            self.state.mark(task.id, TaskStatus.DONE)
            self.state.persist()
            self.notify.send(f"MERGED {task.id} to main (CI {rt.ci_run_id} green)")
            self.log.info("task_merged", extra={"task_id": task.id})
        else:
            self._block(task, "green but merge to main failed (conflict?); branch kept")

    def _block(self, task: Task, reason: str) -> None:
        self.state.mark(task.id, TaskStatus.BLOCKED, last_error=reason)
        self.state.persist()
        self.notify.send(f"BLOCKED {task.id}: {reason}")
        self.log.warning("task_blocked", extra={"task_id": task.id})

    # ---- loop / resume -------------------------------------------------
    def _within_active_hours(self) -> bool:
        ah = self.cfg.engine.active_hours
        if not ah:
            return True
        m = re.match(r"(\d{2}):(\d{2})-(\d{2}):(\d{2})", ah)
        if not m:
            return True
        lo = int(m.group(1)) * 60 + int(m.group(2))
        hi = int(m.group(3)) * 60 + int(m.group(4))
        t = datetime.now().hour * 60 + datetime.now().minute
        return lo <= t <= hi if lo <= hi else (t >= lo or t <= hi)

    def resume(self) -> None:
        # quota self-heals: clear any stale pause; the next claude call re-detects.
        if self.state.quota.phase != QuotaPhase.ACTIVE:
            self.state.quota = quota.QuotaState()
            self.state.persist()
        self.notify.send("supervisor up (Python engine, model=%s)" % self.cfg.engine.model)

    def _emit_health(self, current: str | None) -> None:
        self.health.write(self.state, current_task=current,
                          counts=backlog_mod.summary(self.tasks, self.state))

    def run_once(self, task_id: str | None = None, auto_merge: bool = True) -> None:
        task = next((t for t in self.tasks if t.id == task_id), None) if task_id \
            else backlog_mod.next_task(self.tasks, self.state)
        if task is None:
            self.notify.send("nothing to do (no matching/pending task)")
            return
        self._emit_health(task.id)
        self._cycle(task, auto_merge=auto_merge)
        self._emit_health(None)

    def run_forever(self) -> None:
        from .state import StateStore
        self.resume()
        while True:
            # reload at the top so CLI pause/resume (a flag in the file) is seen
            self.state = StateStore.load(self.state.path)
            if self.state.paused:
                self._emit_health(None)
                self.clock.sleep(self.cfg.engine.idle_interval_s)
                continue
            if not self._within_active_hours():
                self.clock.sleep(self.cfg.engine.idle_interval_s)
                continue
            task = backlog_mod.next_task(self.tasks, self.state)
            if task is None:
                if not self._idle_announced:
                    self.notify.send("backlog complete (%s); idling" %
                                     backlog_mod.summary(self.tasks, self.state))
                    self._idle_announced = True
                self._emit_health(None)
                self.clock.sleep(self.cfg.engine.idle_interval_s)
                self.tasks = backlog_mod.load_backlog(self.p.backlog, self.p.spec_root)
                continue
            self._idle_announced = False
            self._emit_health(task.id)
            self._cycle(task)
