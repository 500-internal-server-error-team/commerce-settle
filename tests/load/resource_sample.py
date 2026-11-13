#!/usr/bin/env python3
"""부하 시험 중 호스트와 컨테이너 자원을 주기적으로 기록한다.

EC2 호스트에서 실행한다. 한 줄이 한 시점의 기록인 JSON Lines를 `--out-dir/resources.jsonl`에 추가한다.
  - docker: `docker stats --no-stream`의 컨테이너별 CPU·메모리
  - host: `vmstat`의 CPU·메모리·스왑 (마지막 1초 샘플)
  - disk: `iostat -dxk`의 장치별 await·%util (iostat이 없으면 생략)
명령이 실패해도 기록을 멈추지 않고 해당 항목에 error를 남긴다.

  python3 -B tests/load/resource_sample.py --out-dir tests/load/work/resources-<실행 ID> --interval 5 --seconds 600
"""

import argparse
import datetime as dt
import json
import hashlib
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

VMSTAT_KEYS = ("us", "sy", "id", "wa", "st", "free", "si", "so", "bi", "bo")
IOSTAT_KEYS = ("r/s", "w/s", "rkB/s", "wkB/s", "r_await", "w_await", "aqu-sz", "%util")
DEVICE_PREFIXES = ("nvme", "xvd", "sd", "vd")

POSTGRES_OBSERVATION = """
BEGIN READ ONLY;
SET LOCAL statement_timeout='2000ms';
WITH relevant AS MATERIALIZED (
 SELECT pid,datname,query_id,state,wait_event_type,wait_event,
   extract(epoch FROM clock_timestamp()-xact_start) xact_age_seconds,
   pg_blocking_pids(pid) blockers
 FROM pg_stat_activity
 WHERE datname IN ('commerce','payment','settlement','finance_mock')
   AND pid <> pg_backend_pid() AND state <> 'idle'
 ORDER BY (wait_event_type='Lock') DESC,pid LIMIT 200
), locks AS (
 SELECT pid,locktype,mode,granted,database,relation,transactionid
 FROM pg_locks WHERE pid IN (SELECT pid FROM relevant)
 ORDER BY granted,pid LIMIT 400
)
SELECT json_build_object('observedAt',clock_timestamp(),
 'computeQueryId',current_setting('compute_query_id'),
 'databases',(SELECT json_agg(datname ORDER BY datname) FROM pg_database
   WHERE datname IN ('commerce','payment','settlement','finance_mock')),
 'activity',coalesce((SELECT json_agg(relevant) FROM relevant),'[]'::json),
 'locks',coalesce((SELECT json_agg(locks) FROM locks),'[]'::json));
ROLLBACK;
"""

_stop = False


def _request_stop(*_):
    global _stop
    _stop = True


def parse_percent(value):
    """`12.34%` 같은 값을 12.34로 바꾼다. 빈 값이나 `--`는 None."""
    if value is None:
        return None
    text = str(value).strip().rstrip("%").strip()
    try:
        return float(text)
    except ValueError:
        return None


def parse_docker_stats(text):
    """`docker stats --no-stream --format '{{json .}}'`의 줄별 JSON을 컨테이너 목록으로 바꾼다."""
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        item = json.loads(line)
        rows.append({
            "name": item.get("Name"),
            "cpu_pct": parse_percent(item.get("CPUPerc")),
            "mem_usage": item.get("MemUsage"),
            "mem_pct": parse_percent(item.get("MemPerc")),
            "pids": item.get("PIDs"),
        })
    return rows


def parse_vmstat(text):
    """`vmstat -n 1 2`의 마지막 값 행(두 번째 샘플, 1초 평균)을 읽는다."""
    header = None
    last = None
    for line in text.splitlines():
        parts = line.split()
        if not parts:
            continue
        if "us" in parts and "id" in parts:
            header = parts
            continue
        if header is not None and parts[0].isdigit():
            last = parts
    if header is None or last is None or len(last) != len(header):
        return None
    row = dict(zip(header, last))
    result = {}
    for key in VMSTAT_KEYS:
        if key in row:
            result[key] = float(row[key])
    return result


def parse_iostat(text):
    """`iostat -dxk 1 2`의 마지막 블록에서 물리 장치별 지표를 읽는다."""
    blocks = []
    header = None
    for line in text.splitlines():
        parts = line.split()
        if not parts:
            continue
        if parts[0] == "Device":
            header = parts
            blocks.append([])
            continue
        if header is not None:
            blocks[-1].append(parts)
    if not blocks or header is None:
        return None
    devices = {}
    for parts in blocks[-1]:
        name = parts[0]
        if not name.startswith(DEVICE_PREFIXES) or len(parts) != len(header):
            continue
        row = dict(zip(header, parts))
        devices[name] = {key: float(row[key]) for key in IOSTAT_KEYS if key in row}
    return devices


def _run(command):
    completed = subprocess.run(command, capture_output=True, text=True, timeout=8, check=False)
    if completed.returncode != 0:
        raise RuntimeError(f"{command[0]} exit {completed.returncode}: {completed.stderr.strip()[:200]}")
    return completed.stdout


def _collect(name, command, parse):
    try:
        return parse(_run(command))
    except (OSError, RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
        return {"error": f"{name}: {error}"}


def sample():
    """한 시점의 기록을 만든다."""
    return {
        "ts": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "docker": _collect("docker", ["docker", "stats", "--no-stream", "--format", "{{json .}}"],
                           parse_docker_stats),
        "host": _collect("vmstat", ["vmstat", "-n", "1", "2"], parse_vmstat),
        "disk": _collect("iostat", ["iostat", "-dxk", "1", "2"], parse_iostat),
    }


def postgres_sample(container):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", container):
        raise ValueError("Select the exact experiment PostgreSQL container")
    return _collect("postgres", ["docker", "exec", "--user", "postgres", container,
        "psql", "-X", "-q", "-A", "-t", "-U", "postgres", "-d", "postgres",
        "-v", "ON_ERROR_STOP=1", "-c", POSTGRES_OBSERVATION], json.loads)


def output_file(path, run_id=None, role=None):
    if run_id:
        if any(parent.is_symlink() for parent in (path, *path.parents)):
            raise ValueError("Resource output cannot cross a symlink")
        if path.exists():
            if not path.is_file() or path.stat().st_nlink != 1:
                raise ValueError("Resource output must be an owned regular file")
            with path.open("rb") as previous:
                first = previous.readline(128 * 1024 + 1)
            if first:
                if len(first) > 128 * 1024 or not first.endswith(b"\n"):
                    raise ValueError("Existing resource evidence is incomplete")
                owner = json.loads(first)
                if owner.get("runId") != run_id or owner.get("hostRole") != role:
                    raise ValueError("Resource output belongs to another experiment")
    descriptor = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    if os.fstat(descriptor).st_nlink != 1:
        os.close(descriptor)
        raise ValueError("Resource output cannot have hardlinks")
    return os.fdopen(descriptor, "a", encoding="utf-8")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out-dir", required=True, help="기록을 추가할 디렉터리")
    parser.add_argument("--interval", type=float, default=5.0, help="기록 간격(초), 기본 5")
    parser.add_argument("--seconds", type=float, default=None, help="전체 기록 시간(초). 없으면 Ctrl+C까지")
    parser.add_argument("--run-id")
    parser.add_argument("--host-role", choices=("app", "data"))
    parser.add_argument("--postgres-container", help="data 호스트의 해당 시험 PostgreSQL 컨테이너")
    parser.add_argument("--stdout", action="store_true", help="동일 JSONL을 SSH stdout으로도 전달")
    parser.add_argument("--max-bytes", type=int, default=16 * 1024 * 1024)
    parser.add_argument("--reserve-bytes", type=int, default=512 * 1024 * 1024)
    args = parser.parse_args(argv)
    if args.interval <= 0:
        parser.error("--interval은 0보다 커야 합니다")
    if ((args.run_id or args.host_role or args.stdout) and
            (not re.fullmatch(r"[a-f0-9]{32}", args.run_id or "") or not args.host_role)):
        parser.error("스트리밍 수집에는 동일 시험 run-id와 host-role을 지정합니다.")
    if args.host_role == "data" and not args.postgres_container:
        parser.error("data 관측에는 정확한 PostgreSQL 컨테이너를 지정합니다.")
    if args.max_bytes < 128 * 1024 or args.reserve_bytes < 0:
        parser.error("저장량·최소 여유 공간 예산을 확인하세요.")

    out_dir = Path(args.out_dir).absolute()
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "resources.jsonl"

    signal.signal(signal.SIGINT, _request_stop)
    signal.signal(signal.SIGTERM, _request_stop)

    started = time.monotonic()
    count = 0
    with output_file(path, args.run_id, args.host_role) as handle:
        path.chmod(0o600)
        while not _stop:
            tick = time.monotonic()
            record = sample()
            if args.postgres_container:
                record["postgres"] = postgres_sample(args.postgres_container)
            if args.run_id:
                record.update(runId=args.run_id, hostRole=args.host_role, hostId=socket.gethostname(),
                    collectorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())
            record["endedAt"] = dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")
            raw = json.dumps(record, ensure_ascii=False) + "\n"
            if (path.stat().st_size + len(raw.encode()) > args.max_bytes
                    or shutil.disk_usage(out_dir).free < args.reserve_bytes + len(raw.encode())):
                print("resource evidence storage budget exhausted", file=sys.stderr)
                return 2
            handle.write(raw)
            handle.flush()
            if args.stdout:
                print(raw, end="", flush=True)
            count += 1
            if args.seconds is not None and time.monotonic() - started >= args.seconds:
                break
            remaining = args.interval - (time.monotonic() - tick)
            if remaining > 0 and not _stop:
                time.sleep(remaining)
    print(f"{count} samples -> {path}", file=sys.stderr if args.stdout else sys.stdout)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
