import unittest
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

from ankiauto import quota
from ankiauto.models import QuotaPhase, QuotaState, ResetHint


class TestDetect(unittest.TestCase):
    def test_session_limit(self):
        sig = quota.detect_quota("", "You've hit your session limit · resets 3:45pm", 1)
        self.assertIsNotNone(sig)
        self.assertEqual(sig.scope, "session")
        self.assertEqual(sig.reset_hint.raw, "3:45pm")

    def test_weekly_limit(self):
        sig = quota.detect_quota("You've hit your weekly limit · resets Mon 12:00am", "", 1)
        self.assertEqual(sig.scope, "weekly")
        self.assertEqual(sig.reset_hint.raw, "Mon 12:00am")

    def test_model_limit(self):
        sig = quota.detect_quota("", "You've hit your Opus limit · resets 9am", 1)
        self.assertEqual(sig.scope, "model")

    def test_no_false_positive_on_normal_failure(self):
        self.assertIsNone(quota.detect_quota("compile error: Unresolved reference", "", 1))

    def test_nonzero_exit_without_phrase_is_not_quota(self):
        self.assertIsNone(quota.detect_quota("", "build failed", 137))

    def test_json_defensive(self):
        sig = quota.detect_quota("", "", 1, {"error": {"type": "usage_limit", "resets": "x"}})
        self.assertIsNotNone(sig)


class TestParseReset(unittest.TestCase):
    def setUp(self):
        # Fix "now" to a known instant: 2026-06-13 10:00 London.
        self.tz = "Europe/London"
        self.now = datetime(2026, 6, 13, 10, 0, tzinfo=ZoneInfo(self.tz)).timestamp()

    def test_clock_today(self):
        ep = quota.parse_reset("3:45pm", self.now, self.tz)
        self.assertIsNotNone(ep)
        self.assertGreater(ep, self.now)
        self.assertLess(ep - self.now, 24 * 3600)

    def test_clock_rolls_to_tomorrow_if_past(self):
        ep = quota.parse_reset("9:00am", self.now, self.tz)  # 9am already passed (now 10am)
        self.assertGreater(ep - self.now, 12 * 3600)

    def test_weekday(self):
        ep = quota.parse_reset("Mon 12:00am", self.now, self.tz)  # 13 Jun 2026 is a Saturday
        self.assertIsNotNone(ep)
        self.assertGreater(ep, self.now)

    def test_unparseable(self):
        self.assertIsNone(quota.parse_reset("sometime soon", self.now, self.tz))
        self.assertIsNone(quota.parse_reset(None, self.now, self.tz))


class TestFSM(unittest.TestCase):
    def test_probe_uses_reset_epoch_first(self):
        hint = ResetHint(raw="3:45pm", scope="session", parsed_epoch=1000.0 + 600)
        d = quota.next_probe_delay(0, hint, 1000.0, jitter_s=0)
        self.assertEqual(d, 660.0)   # (parsed - now) + 60

    def test_backoff_when_unparsed(self):
        d0 = quota.next_probe_delay(0, None, 0.0, base_s=300, jitter_s=0)
        d1 = quota.next_probe_delay(1, None, 0.0, base_s=300, jitter_s=0)
        self.assertEqual(d0, 300)
        self.assertEqual(d1, 600)

    def test_weekly_floor(self):
        hint = ResetHint(raw="Mon", scope="weekly", parsed_epoch=None)
        d = quota.next_probe_delay(0, hint, 0.0, base_s=300, weekly_floor_s=3600, jitter_s=0)
        self.assertEqual(d, 3600)

    def test_pause_then_resume(self):
        sig = quota.detect_quota("", "You've hit your session limit · resets 3:45pm", 1)
        qs = quota.enter_paused(sig, now=1000.0, jitter_s=0)
        self.assertEqual(qs.phase, QuotaPhase.PAUSED)
        self.assertFalse(quota.due_for_probe(qs, 1000.0))
        self.assertTrue(quota.due_for_probe(qs, qs.next_probe_at + 1))
        # probe still limited -> stays paused, count++
        qs2 = quota.on_probe_result(qs, ok=False, now=qs.next_probe_at, jitter_s=0)
        self.assertEqual(qs2.phase, QuotaPhase.PAUSED)
        self.assertEqual(qs2.probe_count, 1)
        # probe ok -> active
        qs3 = quota.on_probe_result(qs2, ok=True, now=99999.0)
        self.assertEqual(qs3.phase, QuotaPhase.ACTIVE)


if __name__ == "__main__":
    unittest.main()
