import json
import tempfile
import unittest
from pathlib import Path

from ankiauto.backlog import next_task, summary
from ankiauto.models import Task, TaskStatus
from ankiauto.state import StateStore


class TestState(unittest.TestCase):
    def test_roundtrip_and_atomic(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "state.json"
            s = StateStore(p)
            s.mark("t1", TaskStatus.IN_PROGRESS, branch="auto/t1", head_sha="abc", fix_attempts=2)
            s.persist()
            self.assertTrue(p.exists())
            # no leftover temp files (atomic replace cleaned up)
            self.assertEqual([f.name for f in Path(d).iterdir()], ["state.json"])
            s2 = StateStore.load(p)
            r = s2.runtime("t1")
            self.assertEqual(r.status, TaskStatus.IN_PROGRESS)
            self.assertEqual(r.branch, "auto/t1")
            self.assertEqual(r.fix_attempts, 2)

    def test_missing_file_defaults(self):
        with tempfile.TemporaryDirectory() as d:
            s = StateStore.load(Path(d) / "nope.json")
            self.assertEqual(s.runtime("x").status, TaskStatus.PENDING)


class TestBacklog(unittest.TestCase):
    def _tasks(self):
        return [Task(id=f"t{i}", title=f"T{i}", spec="...") for i in range(1, 4)]

    def test_pending_in_order(self):
        with tempfile.TemporaryDirectory() as d:
            s = StateStore(Path(d) / "s.json")
            self.assertEqual(next_task(self._tasks(), s).id, "t1")

    def test_resume_in_progress_first(self):
        with tempfile.TemporaryDirectory() as d:
            s = StateStore(Path(d) / "s.json")
            s.mark("t1", TaskStatus.DONE)
            s.mark("t3", TaskStatus.IN_PROGRESS)
            # t2 is pending but t3 is in_progress -> resume t3 first
            self.assertEqual(next_task(self._tasks(), s).id, "t3")

    def test_skip_done_blocked(self):
        with tempfile.TemporaryDirectory() as d:
            s = StateStore(Path(d) / "s.json")
            s.mark("t1", TaskStatus.DONE)
            s.mark("t2", TaskStatus.BLOCKED)
            self.assertEqual(next_task(self._tasks(), s).id, "t3")

    def test_none_when_all_terminal(self):
        with tempfile.TemporaryDirectory() as d:
            s = StateStore(Path(d) / "s.json")
            for t in self._tasks():
                s.mark(t.id, TaskStatus.DONE)
            self.assertIsNone(next_task(self._tasks(), s))

    def test_load_backlog_reads_specs(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "tasks").mkdir()
            (root / "tasks" / "a.md").write_text("SPEC A")
            (root / "backlog.json").write_text(json.dumps(
                {"tasks": [{"id": "a", "title": "A", "spec": "tasks/a.md"}]}))
            from ankiauto.backlog import load_backlog
            tasks = load_backlog(root / "backlog.json", root)
            self.assertEqual(tasks[0].spec, "SPEC A")


if __name__ == "__main__":
    unittest.main()
