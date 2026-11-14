"""Verify native trace persistence, ILM and disk-pressure recovery in an owned fixture."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "commerce-settle-zipkin"


def verify():
    project = "cs-zipkin-native-" + uuid.uuid4().hex[:12]
    print(json.dumps({"project": project, "pid": os.getpid()}), flush=True)
    with tempfile.TemporaryDirectory(prefix=project + "-") as temporary:
        directory = Path(temporary)
        override = directory / "test.json"
        override.write_text(json.dumps({"services": {"zipkin-store": {"volumes": [
            {"type": "volume", "source": "trace-data", "target": "/usr/share/elasticsearch/data"}]}},
            "volumes": {"trace-data": {}}}))
        # Only synthetic fixture credentials, never the user's .env or AWS settings.
        environment = {**os.environ, "ZIPKIN_PORT": "0", "ZIPKIN_STORAGE_PASSWORD": uuid.uuid4().hex,
                       "ZIPKIN_STORAGE_DIR": str(directory / "unused-bind")}
        command = ["docker", "compose", "-p", project, "--env-file", "/dev/null",
                   "-f", str(ROOT / "compose.zipkin-storage.yml"), "-f", str(override)]

        def compose(*args, check=True, input=None, timeout=120):
            result = subprocess.run(command + list(args), cwd=ROOT, env=environment, input=input,
                                    text=True, capture_output=True, check=False, timeout=timeout)
            if check and result.returncode:
                raise RuntimeError(result.stderr[-1500:])
            return result

        def es(path, value=None, method=None):
            args = ["exec", "-T", "zipkin-store", "sh", "-c",
                    'curl -fsS --max-time 5 --user "elastic:$ELASTIC_PASSWORD" '
                    '-H "Content-Type: application/json" -X "$1" "http://localhost:9200$2" --data-binary @-',
                    "curl", method or ("PUT" if value is not None else "GET"), path]
            return json.loads(compose(*args, input=json.dumps(value) if value is not None else "", timeout=15).stdout)

        def until(predicate, label, seconds=120):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                try:
                    value = predicate()
                    if value:
                        return value
                except (OSError, ValueError, RuntimeError, subprocess.SubprocessError):
                    pass
                time.sleep(1)
            raise AssertionError(label)

        def trace(identity):
            return f"0000000000000001{identity:016x}"

        def http(path, value=None):
            request = Request(base + path, json.dumps(value).encode() if value is not None else None,
                              {"Content-Type": "application/json"})
            with urlopen(request, timeout=10) as response:
                return response.read(4 * 1024 * 1024).decode()

        def spans(identity):
            try:
                return json.loads(http("/api/v2/trace/" + trace(identity)))
            except HTTPError as error:
                if error.code != 404:
                    raise
                error.close()
                return []

        def span(identity):
            return {"traceId": trace(identity), "id": f"{identity:016x}",
                    "name": "storage-fixture", "timestamp": int(time.time() * 1e6), "duration": 1000,
                    "localEndpoint": {"serviceName": "storage-fixture"},
                    "tags": {"operation": "synthetic-storage-fixture", "fixture": "x" * 256}}

        def dropped():
            metrics = http("/prometheus")
            values = re.findall(r"(?m)^zipkin_collector_spans_dropped(?:_total)?(?:\{[^}]*\})?\s+([0-9.eE+-]+)", metrics)
            assert values, "Native dropped-span metric is unavailable"
            return sum(map(float, values))

        try:
            compose("up", "-d", "zipkin", timeout=360)
            base = "http://" + compose("port", "zipkin", "9411").stdout.strip()
            until(lambda: http("/health"), "Zipkin did not become ready", 180)
            assert es("/")["version"]["number"] == "8.19.25"
            assert es("/_ilm/policy/commerce-settle-zipkin-retention")["commerce-settle-zipkin-retention"]["policy"]["phases"]["delete"]["min_age"] == "2d"
            http("/api/v2/spans", [span(1)])
            until(lambda: spans(1), "native trace was not stored")
            compose("restart", "zipkin-store", "zipkin")
            base = "http://" + compose("port", "zipkin", "9411").stdout.strip()
            until(lambda: spans(1), "trace did not survive both container restarts", 180)
            print(json.dumps({"phase": "restart-persistence", "verified": True}), flush=True)

            before = dropped()
            start = time.monotonic()
            for second in range(30):
                http("/api/v2/spans", [span(10000 + second * 1280 + offset) for offset in range(1280)])
                time.sleep(max(0, start + second + 1 - time.monotonic()))
            elapsed = time.monotonic() - start
            until(lambda: spans(10000 + 30 * 1280 - 1), "storage fixture did not drain")
            assert dropped() == before, "Native store dropped fixture spans"
            def complete_count():
                es("/" + PREFIX + "-span-*/_refresh", method="POST")
                return es("/" + PREFIX + "-span-*/_count")["count"] == 38401
            until(complete_count, "Native store did not retain every offered fixture span")
            size = es("/" + PREFIX + "-*/_stats/store")["_all"]["primaries"]["store"]["size_in_bytes"]
            print(json.dumps({"phase": "storage-ingestion", "offeredSpans": 38400,
                "elapsedSeconds": round(elapsed, 2), "offeredSpansPerSecond": round(38400 / elapsed, 2),
                "indexBytes": size, "scope": "synthetic storage only, not application TPS"}), flush=True)

            old = PREFIX + "-span-2000.01.01"
            es("/" + old, {})
            es("/" + old + "/_doc/fixture?refresh=true", {"fixture": "expired"}, "POST")
            es("/unrelated-fixture", {})
            es("/unrelated-fixture/_doc/keep?refresh=true", {"fixture": "keep"}, "POST")
            assert es("/" + old + "/_count")["count"] == 1
            until(lambda: old not in {row["index"] for row in es("/_cat/indices?format=json&h=index")},
                  "Native 2d ILM did not remove the old dated index", 210)
            assert es("/unrelated-fixture/_count")["count"] == 1 and spans(1)
            print(json.dumps({"phase": "native-retention", "expiredIndexRemoved": True,
                "freshTraceAndUnrelatedIndexPreserved": True}), flush=True)

            # Exercise native watermarks without filling the developer's shared Docker disk.
            available = es("/_nodes/stats/fs")["nodes"]
            available = next(iter(available.values()))["fs"]["total"]["available_in_bytes"]
            settings = {"cluster.info.update.interval": "10s"}
            for name, extra in (("low", 3), ("high", 2), ("flood_stage", 1)):
                settings["cluster.routing.allocation.disk.watermark." + name] = str(available + extra * 1024**3) + "b"
            es("/_cluster/settings", {"transient": settings})

            def blocked():
                return any(row["settings"]["index"].get("blocks", {}).get("read_only_allow_delete") == "true"
                           for row in es("/" + PREFIX + "-*/_settings").values())

            until(blocked, "Native flood-stage block was not enforced", 90)
            before = dropped()
            http("/api/v2/spans", [span(2)])
            until(lambda: dropped() > before, "Disk pressure was not visible in Zipkin's native metric", 60)
            assert not spans(2), "Blocked storage accepted a trace"
            es("/_cluster/settings", {"transient": {key: None for key in settings}})
            until(lambda: not blocked(), "Native disk block did not clear after capacity recovery", 90)
            http("/api/v2/spans", [span(3)])
            until(lambda: spans(3), "Native store did not recover")
            memory = {}
            for service in ("zipkin-store", "zipkin"):
                identity = compose("ps", "-q", service).stdout.strip()
                fact = json.loads(subprocess.check_output(["docker", "inspect", identity], text=True))[0]
                assert not fact["State"]["OOMKilled"] and fact["RestartCount"] == 0
                peak = int(subprocess.check_output(["docker", "exec", identity, "cat", "/sys/fs/cgroup/memory.peak"], text=True))
                memory[service] = {"peakBytes": peak, "limitBytes": fact["HostConfig"]["Memory"]}
                assert peak <= fact["HostConfig"]["Memory"]
            print(json.dumps({"restartPersistenceVerified": True, "retentionExpiryVerified": True,
                "capacityLimitAndRecoveryVerified": True, "scopedDeletionVerified": True,
                "retention": "UTC daily indices deleted at age 2d; poll interval 1m",
                "capacityTest": "simulated native free-space watermark, no physical disk fill",
                "memory": memory, "scope": "isolated native lifecycle, not live ECS or 200TPS"}), flush=True)
        except Exception:
            print(compose("ps", "--all", check=False).stdout, flush=True)
            raise
        finally:
            compose("down", "-v", "--remove-orphans")
            for kind in (["ps", "-aq"], ["volume", "ls", "-q"], ["network", "ls", "-q"]):
                assert not subprocess.check_output(["docker", *kind, "--filter",
                    "label=com.docker.compose.project=" + project], text=True).strip()


if __name__ == "__main__":
    verify()
