#!/usr/bin/env python3
"""Approve the running Redis run_id for Gateway and Commerce in the local .env.

Redis gets a new run_id whenever its process starts, so Gateway and Commerce close user
authentication after any Redis restart. This local and CI helper applies the recovery's
setting step: it approves the current run_id and rotates AUTH_SESSION_EPOCH together,
because keeping the epoch would make sessions restored from AOF valid again.
"""

import argparse
import os
from pathlib import Path
import re
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
RUN_ID = re.compile(r"[0-9a-f]{40}")
# Compose rejects the whole file while the run_id is empty, even for a Redis-only command.
PLACEHOLDER = "0" * 40


def approve(directory, run_id, *, recover=False):
    """Rotate the epoch for a new run_id or explicit recovery. Returns whether .env changed."""
    if not RUN_ID.fullmatch(run_id or ""):
        raise ValueError(f"Not a Redis run_id: {run_id!r}")
    target = Path(directory) / ".env"
    lines = target.read_text().splitlines(keepends=True)
    positions = {}
    for index, line in enumerate(lines):
        match = re.match(r"^([A-Z][A-Z0-9_]*)=(.*)$", line.rstrip("\r\n"))
        if match:
            positions[match[1]] = (index, match[2].strip())
    missing = [key for key in ("AUTH_REDIS_RUN_ID", "AUTH_SESSION_EPOCH") if key not in positions]
    if missing:
        raise ValueError(f"Run python3 scripts/init_env.py first; missing {', '.join(missing)}")
    if positions["AUTH_REDIS_RUN_ID"][1] == run_id and not recover:
        return False
    for key, value in (("AUTH_REDIS_RUN_ID", run_id), ("AUTH_SESSION_EPOCH", str(uuid.uuid4()))):
        lines[positions[key][0]] = f"{key}={value}\n"
    target.write_text("".join(lines))
    return True


def running_run_id():
    environment = {**os.environ, "AUTH_REDIS_RUN_ID": PLACEHOLDER}

    def compose(*args):
        return subprocess.run(
            ["docker", "compose", *args], cwd=ROOT, env=environment,
            text=True, stdout=subprocess.PIPE, timeout=180, check=True,
        ).stdout

    compose("up", "-d", "--wait", "redis")
    info = compose("exec", "-T", "redis", "sh", "-c",
                   'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli INFO server')
    match = re.search(r"^run_id:([0-9a-f]{40})\s*$", info, re.MULTILINE)
    if not match:
        raise RuntimeError("Redis INFO server has no run_id")
    return match[1]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--recover", action="store_true",
                        help="Rotate the session epoch even when run_id is unchanged after reconnect")
    args = parser.parse_args(argv)
    run_id = running_run_id()
    if approve(ROOT, run_id, recover=args.recover):
        print(f"Approved Redis run_id {run_id} and rotated AUTH_SESSION_EPOCH. "
              "Run docker compose up -d to recreate Gateway and Commerce; users log in again.")
    else:
        print(f"Redis run_id {run_id} is already approved.")


if __name__ == "__main__":
    main()
