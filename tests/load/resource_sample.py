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
import signal
import subprocess
import time
from pathlib import Path

VMSTAT_KEYS = ("us", "sy", "id", "wa", "st", "free", "si", "so", "bi", "bo")
IOSTAT_KEYS = ("r/s", "w/s", "rkB/s", "wkB/s", "r_await", "w_await", "aqu-sz", "%util")
DEVICE_PREFIXES = ("nvme", "xvd", "sd", "vd")

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
    completed = subprocess.run(command, capture_output=True, text=True, timeout=30, check=False)
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out-dir", required=True, help="기록을 추가할 디렉터리")
    parser.add_argument("--interval", type=float, default=5.0, help="기록 간격(초), 기본 5")
    parser.add_argument("--seconds", type=float, default=None, help="전체 기록 시간(초). 없으면 Ctrl+C까지")
    args = parser.parse_args(argv)
    if args.interval <= 0:
        parser.error("--interval은 0보다 커야 합니다")

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "resources.jsonl"

    signal.signal(signal.SIGINT, _request_stop)
    signal.signal(signal.SIGTERM, _request_stop)

    started = time.monotonic()
    count = 0
    with path.open("a", encoding="utf-8") as handle:
        while not _stop:
            tick = time.monotonic()
            handle.write(json.dumps(sample(), ensure_ascii=False) + "\n")
            handle.flush()
            count += 1
            if args.seconds is not None and time.monotonic() - started >= args.seconds:
                break
            remaining = args.interval - (time.monotonic() - tick)
            if remaining > 0 and not _stop:
                time.sleep(remaining)
    print(f"{count} samples -> {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
