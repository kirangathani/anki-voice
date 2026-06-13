import logging
import tempfile
import unittest
from pathlib import Path

from ankiauto import quota
from ankiauto.clock import FakeClock
from ankiauto.config import (Config, EngineCfg, NotifyCfg, PathsCfg, ProjectCfg, QuotaCfg)
from ankiauto.engine import Engine
from ankiauto.models import CIResult, ClaudeResult, Task, TaskStatus
from ankiauto.state import StateStore


class FakeRunner:
    def __init__(self, results): self.results = list(results); self.calls = 0
    def run(self, prompt, cwd, *, model, timeout_s):
        self.calls += 1
        return self.results.pop(0) if self.results else _ok()


class FakeProbe:
    def __init__(self, ok=True): self._ok = ok
    def probe_ok(self, cwd): return self._ok


class FakeCI:
    def __init__(self, conclusions): self.conclusions = list(conclusions)
    def find_run(self, branch, sha): return 111
    def poll(self, run_id):
        c = self.conclusions.pop(0) if self.conclusions else "failure"
        return CIResult(run_id=run_id, status="completed", conclusion=c)
    def failed_log(self, run_id): return "boom"
    def rerun(self, run_id): pass


class FakeGit:
    def __init__(self, touches=False): self.touches = touches; self.merged = None
    def fetch(self): pass
    def fresh_branch(self, name): pass
    def head_sha(self, ref): return "deadbeef0000"
    def diff_touches(self, branch, paths): return self.touches
    def merge_to_main(self, branch, message): self.merged = branch; return True


class FakeNotify:
    def __init__(self): self.msgs = []
    def send(self, m): self.msgs.append(m)


class FakeHealth:
    def write(self, *a, **k): pass


def _ok(): return ClaudeResult(exit_code=0, stdout="done", stderr="")
def _quota(): return ClaudeResult(exit_code=1, stdout="", stderr="You've hit your session limit · resets 3:45pm",
                                  quota_signal=quota.detect_quota("", "You've hit your session limit · resets 3:45pm", 1))


def build(tmp, runner, ci, git, notify, *, max_fix=2, probe_ok=True):
    pdir = Path(tmp) / "prompts"; pdir.mkdir()
    (pdir / "implement.md").write_text("implement {{TASK_TITLE}} on {{BRANCH}}\n{{TASK_SPEC}}")
    (pdir / "fix.md").write_text("fix {{BRANCH}}\n{{CI_FAILURE}}")
    proj = ProjectCfg(name="anki-voice", repo_slug="o/r", work_clone=tmp,
                      prompts_dir=str(pdir), backlog=str(Path(tmp) / "b.json"),
                      spec_root=tmp, protected_paths=(".github/workflows", "autonomy/"))
    cfg = Config(engine=EngineCfg(model="m", max_fix=max_fix, poll_interval_s=0,
                                  idle_interval_s=0, claude_timeout_s=5, task_wallclock_cap_s=10_000),
                 quota=QuotaCfg(jitter_s=0, probe_base_s=1, probe_cap_s=1, weekly_floor_s=1),
                 paths=PathsCfg(state_dir=tmp, status_file=str(Path(tmp) / "s.json"),
                                log_file=str(Path(tmp) / "l.jsonl"), lock_file=str(Path(tmp) / "lock")),
                 notify=NotifyCfg(), projects={"anki-voice": proj})
    state = StateStore(Path(tmp) / "anki-voice.json")
    eng = Engine(cfg, proj, state=state, tasks=[Task(id="t1", title="T1", spec="SPEC")],
                 runner=runner, probe=FakeProbe(probe_ok), ci=ci, git=git, notify=notify,
                 health=FakeHealth(), clock=FakeClock(), log=logging.getLogger("test"))
    return eng


class TestCycle(unittest.TestCase):
    def test_green_first_try_merges(self):
        with tempfile.TemporaryDirectory() as tmp:
            git = FakeGit()
            eng = build(tmp, FakeRunner([_ok()]), FakeCI(["success"]), git, FakeNotify())
            eng._cycle(eng.tasks[0])
            self.assertEqual(eng.state.runtime("t1").status, TaskStatus.DONE)
            self.assertEqual(git.merged, "auto/t1")

    def test_red_then_green_via_fix(self):
        with tempfile.TemporaryDirectory() as tmp:
            git = FakeGit()
            # poll: failure, (rerun) failure, then after fix: success
            eng = build(tmp, FakeRunner([_ok(), _ok()]), FakeCI(["failure", "failure", "success"]), git, FakeNotify())
            eng._cycle(eng.tasks[0])
            self.assertEqual(eng.state.runtime("t1").status, TaskStatus.DONE)
            self.assertEqual(eng.state.runtime("t1").fix_attempts, 1)

    def test_blocked_after_max_fix(self):
        with tempfile.TemporaryDirectory() as tmp:
            eng = build(tmp, FakeRunner([_ok()] * 10), FakeCI([]), FakeGit(), FakeNotify(), max_fix=2)
            eng._cycle(eng.tasks[0])
            self.assertEqual(eng.state.runtime("t1").status, TaskStatus.BLOCKED)

    def test_quota_on_implement_pauses_resumes_no_fix_bump(self):
        with tempfile.TemporaryDirectory() as tmp:
            notify = FakeNotify()
            # implement: quota hit, then (after probe) ok; CI green
            eng = build(tmp, FakeRunner([_quota(), _ok()]), FakeCI(["success"]), FakeGit(), notify, probe_ok=True)
            eng._cycle(eng.tasks[0])
            self.assertEqual(eng.state.runtime("t1").status, TaskStatus.DONE)
            self.assertEqual(eng.state.runtime("t1").fix_attempts, 0)  # quota != fix
            self.assertTrue(any("burned through" in m for m in notify.msgs))
            self.assertTrue(any("reset" in m.lower() for m in notify.msgs))

    def test_merge_guard_blocks_protected_diff(self):
        with tempfile.TemporaryDirectory() as tmp:
            eng = build(tmp, FakeRunner([_ok()]), FakeCI(["success"]), FakeGit(touches=True), FakeNotify())
            eng._cycle(eng.tasks[0])
            self.assertEqual(eng.state.runtime("t1").status, TaskStatus.BLOCKED)


if __name__ == "__main__":
    unittest.main()
