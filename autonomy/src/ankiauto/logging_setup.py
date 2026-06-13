"""Structured JSON logging to a rotating file + human text to stderr (journald).
Installs a redaction filter so token-like strings never reach the logs."""
from __future__ import annotations

import json
import logging
import re
from logging.handlers import RotatingFileHandler
from pathlib import Path

_TOKEN_RE = re.compile(r"(gh[pous]_[A-Za-z0-9_]{10,}|github_pat_[A-Za-z0-9_]{20,})")


class _Redact(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            record.msg = _TOKEN_RE.sub("***REDACTED***", record.msg)
        return True


class _JsonFmt(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        base = {"ts": self.formatTime(record), "level": record.levelname,
                "event": record.getMessage()}
        for k in ("task_id", "phase", "ci_run_id", "fix_attempts", "quota_phase"):
            v = getattr(record, k, None)
            if v is not None:
                base[k] = v
        return json.dumps(base)


def setup(log_file: str) -> logging.Logger:
    log = logging.getLogger("ankiauto")
    if log.handlers:
        return log
    log.setLevel(logging.INFO)
    log.addFilter(_Redact())

    p = Path(log_file).expanduser()
    p.parent.mkdir(parents=True, exist_ok=True)
    fh = RotatingFileHandler(p, maxBytes=2_000_000, backupCount=3)
    fh.setFormatter(_JsonFmt())
    log.addHandler(fh)

    sh = logging.StreamHandler()
    sh.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    log.addHandler(sh)
    return log
