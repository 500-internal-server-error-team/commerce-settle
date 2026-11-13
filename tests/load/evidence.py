"""Bounded, run-owned diagnostic evidence; never a business-performance verdict."""
import argparse
import base64
from contextlib import contextmanager
from datetime import datetime
import fcntl
import gzip
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener
import uuid

import performance


FORMAT = "commerce-settle-diagnostics-v1"
TRACE = re.compile(r"(?:[a-f0-9]{16}|[a-f0-9]{32})")
PAYLOAD = re.compile(r"(?:trace-[a-f0-9]{16}(?:[a-f0-9]{16})?|sample-[0-9]{8})\.json\.gz")
MINIMUM_BUDGET = 128 * 1024
DEFAULT_BUDGET = 16 * 1024 * 1024
DEFAULT_TOTAL = 256 * 1024 * 1024
DEFAULT_RESERVE = 512 * 1024 * 1024
DEFAULT_TRACES = 256


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                      allow_nan=False).encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def unpack(raw):
    with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
        value = stream.read(8 * 1024 * 1024 + 1)
    if len(value) > 8 * 1024 * 1024:
        raise ValueError("Uncompressed diagnostic object exceeded its bound")
    return value


def regular(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1:
        raise ValueError("Evidence must be a private regular file")
    return path


def safe_directory(path):
    path = Path(path).absolute()
    if any(parent.is_symlink() for parent in (path, *path.parents)):
        raise ValueError("Evidence paths cannot cross a symlink")
    return path


def resource_record(path, run_id, at):
    path = regular(safe_directory(path))
    with path.open("rb") as stream:
        stream.seek(max(0, path.stat().st_size - 128 * 1024))
        raw = stream.read(128 * 1024)
    lines = raw.split(b"\n")[:-1]  # An in-flight partial line is never accepted as a fresh sample.
    if not lines or not lines[-1]:
        raise ValueError("No complete resource sample is available")
    row = json.loads(lines[-1])
    if (row.get("runId") != run_id or row.get("hostRole") not in {"app", "data"}
            or not isinstance(row.get("hostId"), str) or not row["hostId"].strip()):
        raise ValueError("Resource evidence belongs to another run")
    start = datetime.fromisoformat(row["ts"].replace("Z", "+00:00")).timestamp()
    end = datetime.fromisoformat(row["endedAt"].replace("Z", "+00:00")).timestamp()
    read_at = time.time()
    if not 0 <= read_at - end <= 45 or not 0 <= end - start <= 15:
        raise ValueError("Resource evidence is stale, future or too expensive to collect")
    source = Path(__file__).with_name("resource_sample.py")
    if row.get("collectorSha256") != digest(source.read_bytes()):
        raise ValueError("Resource collector revision does not match this runner")
    if (not isinstance(row.get("docker"), list) or not row.get("host") or not row.get("disk")
            or any(isinstance(row.get(key), dict) and "error" in row[key] for key in ("host", "disk"))):
        raise ValueError("Required host/CPU/disk observation failed")
    if row["hostRole"] == "data":
        pg = row.get("postgres", {})
        if (pg.get("computeQueryId") != "on"
                or set(pg.get("databases", [])) != {"commerce", "payment", "settlement", "finance_mock"}
                or not isinstance(pg.get("activity"), list) or not isinstance(pg.get("locks"), list)):
            raise ValueError("DB query/lock observation is incomplete")
        observed = datetime.fromisoformat(pg["observedAt"].replace("Z", "+00:00")).timestamp()
        if not start - 1 <= observed <= end + 1:
            raise ValueError("DB and host observation clocks do not match")
    return {key: row[key] for key in ("runId", "hostRole", "hostId", "ts", "endedAt", "collectorSha256",
                                     "docker", "host", "disk", "postgres") if key in row}


def trace_spans(trace_id, spans):
    if not TRACE.fullmatch(trace_id) or int(trace_id, 16) == 0 or not isinstance(spans, list):
        raise ValueError("Invalid diagnostic trace")
    result = {}
    for span in spans:
        if (not isinstance(span, dict) or span.get("traceId") != trace_id
                or not re.fullmatch(r"[a-f0-9]{16}", str(span.get("id", "")))):
            raise ValueError("Trace response changed identity")
        row = {key: span[key] for key in ("traceId", "id", "parentId", "name", "kind",
                                         "timestamp", "duration", "shared", "debug") if key in span}
        for key in ("timestamp", "duration"):
            if key in row and (type(row[key]) is not int or row[key] < 0):
                raise ValueError("Invalid span timing")
        if "parentId" in row and not re.fullmatch(r"[a-f0-9]{16}", str(row["parentId"])):
            raise ValueError("Invalid span parent")
        endpoint = span.get("localEndpoint", {})
        if not isinstance(endpoint, dict):
            raise ValueError("Invalid span endpoint")
        service = endpoint.get("serviceName", "")
        if not isinstance(service, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{0,100}", service):
            raise ValueError("Invalid span service")
        row["localEndpoint"] = {"serviceName": service}
        # Do not archive URLs, headers, SQL text, bindings or arbitrary exception messages.
        tags = {}
        for key, value in span.get("tags", {}).items():
            if key.endswith(".id") and re.fullmatch(r"[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}", str(value)):
                tags[key] = value
            elif key in {"http.method", "http.route", "http.status_code", "operation", "stage", "kind"}:
                if isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_./{}: -]{1,160}", value):
                    tags[key] = value
            elif key == "error" and value:
                tags["error"] = "true"
        row["tags"] = tags
        result[(row["id"], row.get("kind", ""), service)] = row
    return result


class Archive:
    def __init__(self, run, *, maximum=DEFAULT_BUDGET, total=DEFAULT_TOTAL,
                 reserve=DEFAULT_RESERVE, max_traces=DEFAULT_TRACES, resume=False, reading=False):
        self.run = safe_directory(run)
        self.path = self.run / "diagnostics"
        plan_raw = regular(self.run / "plan.json").read_bytes()
        plan = json.loads(plan_raw)
        self.run_id = plan.get("runId")
        if not isinstance(self.run_id, str) or not re.fullmatch(r"[a-f0-9]{32}", self.run_id):
            raise ValueError("An existing load-run plan is required")
        if (type(maximum) is not int or maximum < MINIMUM_BUDGET
                or type(total) is not int or total < maximum or type(reserve) is not int or reserve < 0
                or type(max_traces) is not int or not 1 <= max_traces <= 4096):
            raise ValueError("Invalid evidence budget")
        created = not self.path.exists()
        if created:
            if reading:
                raise ValueError("No evidence archive exists")
            self.path.mkdir(mode=0o700)
        safe_directory(self.path)
        lock_path = self.path / ".lock"
        if lock_path.exists():
            regular(lock_path)
        fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        self.lock = os.fdopen(fd, "r+b")
        self.quota_fd = os.open(self.run.parent, os.O_RDONLY | os.O_DIRECTORY)
        self.quota_mutex = threading.RLock()
        self.quota_depth = 0
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if created:
                self.meta = {"format": FORMAT, "runId": self.run_id, "state": "collecting",
                             "planSha256": digest(plan_raw),
                             "createdAt": time.time(), "maximumBytes": maximum, "totalBytes": total,
                             "reserveBytes": reserve, "maxTraces": max_traces,
                             "expectedTraces": [], "files": {}, "failures": [], "pending": None}
                self._save()
            else:
                self.meta = json.loads(regular(self.path / "owner.json").read_text())
                if self.meta.get("format") != FORMAT or self.meta.get("runId") != self.run_id:
                    raise ValueError("Evidence ownership does not match the selected run")
                if self.meta.get("planSha256") != digest(plan_raw):
                    raise ValueError("The load plan changed after evidence capture began")
                if not reading and (not resume or self.meta["state"] in {"reviewed", "deleting"}):
                    raise ValueError("Use explicit resume; reviewed evidence is immutable")
                if not reading and (self.meta["maximumBytes"], self.meta["totalBytes"],
                                    self.meta["reserveBytes"], self.meta["maxTraces"]) != (maximum, total, reserve, max_traces):
                    raise ValueError("Resume cannot silently change the capture budget")
            self._recover(reading)
        except BaseException:
            self.lock.close()
            os.close(self.quota_fd)
            raise

    @contextmanager
    def quota(self):
        # Only the actual shared experiment-result root is locked, never the host or Docker daemon.
        with self.quota_mutex:
            if not self.quota_depth:
                fcntl.flock(self.quota_fd, fcntl.LOCK_EX)
            self.quota_depth += 1
            try:
                yield
            finally:
                self.quota_depth -= 1
                if not self.quota_depth:
                    fcntl.flock(self.quota_fd, fcntl.LOCK_UN)

    def _atomic(self, name, raw):
        target = self.path / name
        if target.exists() or target.is_symlink():
            regular(target)
        temporary = self.path / (".owner-" + uuid.uuid4().hex + ".tmp")
        try:
            with temporary.open("xb") as output:
                temporary.chmod(0o600)
                output.write(raw)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, target)
        finally:
            temporary.unlink(missing_ok=True)

    def _save(self):
        raw = encoded(self.meta)
        with self.quota():
            current = sum(regular(p).stat().st_size for p in self.path.iterdir())
            total = self._owned_total()
            if not (self.path / "owner.json").exists():
                total += current
            if (len(raw) > 65536 or current + len(raw) > self.meta["maximumBytes"]
                    or total + len(raw) > self.meta["totalBytes"]
                    or shutil.disk_usage(self.path).free < self.meta["reserveBytes"] + len(raw)):
                raise OSError("Evidence manifest publication exceeded the storage budget")
            self._atomic("owner.json", raw)

    def _recover(self, reading):
        pending = self.meta.get("pending")
        if pending:
            if reading:
                raise ValueError("Interrupted publication needs an explicit resume")
            name = pending["name"]
            if not PAYLOAD.fullmatch(name):
                raise ValueError("Invalid pending evidence path")
            destination = self.path / name
            if destination.exists() and digest(regular(destination).read_bytes()) == pending["sha256"]:
                self.meta["files"][name] = {k: pending[k] for k in ("sha256", "bytes")}
            self.meta["pending"] = None
            self.meta["failures"].append({"kind": "interrupted-publication", "at": time.time()})
            self.meta["state"] = "collecting"
            self._save()
        allowed = {"owner.json", ".lock", *self.meta["files"]}
        for path in self.path.iterdir():
            if path.name not in allowed:
                raise ValueError("Unowned or interrupted temporary file is preserved for review")
            regular(path)
        for name, fact in self.meta["files"].items():
            if not PAYLOAD.fullmatch(name):
                raise ValueError("Invalid archived filename")
            if self.meta["state"] == "deleting" and not (self.path / name).exists():
                continue
            raw = regular(self.path / name).read_bytes()
            if len(raw) != fact["bytes"] or digest(raw) != fact["sha256"]:
                raise ValueError("Archived evidence no longer matches its manifest")
            json.loads(unpack(raw))

    def _owned_total(self):
        result = 0
        for parent in self.run.parent.iterdir():
            if parent.is_symlink() or not parent.is_dir():
                continue
            owner = parent / "diagnostics" / "owner.json"
            if owner.exists() and not owner.is_symlink():
                meta = json.loads(regular(owner).read_text())
                if meta.get("format") == FORMAT:
                    directory = safe_directory(owner.parent)
                    result += sum(regular(p).stat().st_size for p in directory.iterdir())
        return result

    def publish(self, name, value):
        with self.quota():
            return self._publish(name, value)

    def _publish(self, name, value):
        if not PAYLOAD.fullmatch(name) or self.meta["state"] == "reviewed":
            raise ValueError("Invalid evidence publication")
        raw = gzip.compress(encoded(value), mtime=0)
        old = self.meta["files"].get(name, {}).get("bytes", 0)
        current = sum(regular(p).stat().st_size for p in self.path.iterdir())
        # Keep room for the atomic replacement and its failure record, not just final compressed bytes.
        if (current + len(raw) + 65536 > self.meta["maximumBytes"]
                or self._owned_total() + len(raw) + 65536 > self.meta["totalBytes"]
                or shutil.disk_usage(self.path).free < self.meta["reserveBytes"] + len(raw) + 65536):
            self.fail("storage-budget")
            raise OSError("Evidence storage budget exhausted")
        self.meta["pending"] = {"name": name, "sha256": digest(raw), "bytes": len(raw)}
        self._save()
        try:
            self._atomic(name, raw)
            self.meta["files"][name] = {"sha256": digest(raw), "bytes": len(raw)}
            self.meta["pending"] = None
            self._save()
        except OSError:
            # The pending manifest makes a fully published object recoverable after a restart.
            self.meta["state"] = "failed"
            raise
        return len(raw) - old

    def expect(self, trace_id):
        if not TRACE.fullmatch(trace_id) or int(trace_id, 16) == 0:
            raise ValueError("Invalid trace ID")
        if trace_id not in self.meta["expectedTraces"]:
            if len(self.meta["expectedTraces"]) >= self.meta["maxTraces"]:
                self.fail("trace-count-budget")
                raise ValueError("Diagnostic trace budget exhausted")
            self.meta["expectedTraces"].append(trace_id)
            self._save()

    def put_trace(self, trace_id, spans):
        self.expect(trace_id)
        name = "trace-" + trace_id + ".json.gz"
        merged = {}
        if name in self.meta["files"]:
            merged.update(trace_spans(trace_id, json.loads(unpack((self.path / name).read_bytes()))))
        incoming = trace_spans(trace_id, spans)
        if not incoming:
            return False
        for key, row in incoming.items():
            previous = merged.get(key, {})
            if previous.get("parentId", row.get("parentId")) != row.get("parentId", previous.get("parentId")):
                raise ValueError("A span changed its parent")
            merged[key] = {**previous, **row, "tags": {**previous.get("tags", {}), **row.get("tags", {})}}
        value = sorted(merged.values(), key=lambda row: (row.get("timestamp", 0), row["id"], row.get("kind", "")))
        if name not in self.meta["files"] or unpack((self.path / name).read_bytes()) != encoded(value):
            self.publish(name, value)
        return True

    def sample(self, value):
        count = sum(name.startswith("sample-") for name in self.meta["files"])
        self.publish(f"sample-{count:08d}.json.gz", value)

    def fail(self, kind):
        self.meta["state"] = "failed"
        self.meta["failures"].append({"kind": kind, "at": time.time()})
        self._save()

    def finish(self, complete):
        missing = [value for value in self.meta["expectedTraces"]
                   if "trace-" + value + ".json.gz" not in self.meta["files"]]
        self.meta.update(state="complete" if complete and self.meta["expectedTraces"] and not missing else "failed",
                         missingTraces=missing, observationGaps=bool(self.meta["failures"]), endedAt=time.time())
        self._save()
        return self.meta["state"] == "complete"

    def close(self):
        if not self.lock.closed:
            self.lock.close()
            os.close(self.quota_fd)

    def review(self, summary=None):
        if self.meta["state"] != "complete":
            raise ValueError("Failed or unresolved evidence cannot be pruned")
        summary = safe_directory(summary or self.run / "summary.json")
        summary.relative_to(self.run)
        value = json.loads(regular(summary).read_text())
        if (value.get("runId") != self.run_id or value.get("recoveryRequired") is not False
                or value.get("recoveryComplete") is not True or value.get("drainComplete") is not True):
            raise ValueError("Resolve the selected run's outstanding transactions before reviewing evidence")
        self.meta.update(state="reviewed", reviewedAt=time.time())
        self.meta["reviewBasis"] = {"path": str(summary.relative_to(self.run)), "sha256": digest(summary.read_bytes())}
        self._save()

    def cleanup(self, expected_sha256):
        with self.quota():
            self._cleanup(expected_sha256)

    def _cleanup(self, expected_sha256):
        if not ((self.meta["state"] == "reviewed" and digest((self.path / "owner.json").read_bytes()) == expected_sha256)
                or (self.meta["state"] == "deleting" and self.meta.get("cleanupManifestSha") == expected_sha256)):
            raise ValueError("Explicit reviewed-manifest SHA is required for cleanup")
        basis = self.meta["reviewBasis"]
        resolution = safe_directory(self.run / basis["path"])
        resolution.relative_to(self.run)
        if digest(regular(resolution).read_bytes()) != basis["sha256"]:
            raise ValueError("Transaction-resolution evidence changed after review")
        self._recover(True)
        self.meta.update(state="deleting", cleanupManifestSha=expected_sha256)
        self._save()
        for name in list(self.meta["files"]):
            if (self.path / name).exists():
                regular(self.path / name).unlink()
            del self.meta["files"][name]
            self._save()
        self._recover(True)
        (self.path / "owner.json").unlink()
        (self.path / ".lock").unlink()
        self.path.rmdir()


def get_trace(base, trace_id, *, timeout=5):
    parsed = urlsplit(base)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment or not TRACE.fullmatch(trace_id)):
        raise ValueError("Use an explicit Zipkin base URL without credentials")
    request = Request(base.rstrip("/") + "/api/v2/trace/" + trace_id)
    user, password = os.environ.get("PERFORMANCE_AUTH_USER"), os.environ.get("PERFORMANCE_AUTH_PASSWORD")
    if user or password:
        if not user or not password or (parsed.scheme != "https" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}):
            raise ValueError("Use complete monitoring credentials over HTTPS")
        request.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode())
    try:
        with build_opener(performance.NoRedirect()).open(request, timeout=timeout) as response:
            raw = performance.bounded_body(response, 2 * 1024 * 1024, timeout)
    except HTTPError as error:
        try:
            if error.code == 404:
                return []
            raise
        finally:
            error.close()
    return json.loads(raw)


class EvidenceSession:
    """One bounded watcher tied to the current runner's process, never another task's process."""
    def __init__(self, archive, prometheus, zipkin, *, runtime_targets, resource_files,
                 bundle_maximum=256 * 1024 * 1024, metadata=None, interval=15):
        if (not runtime_targets or len(set(runtime_targets)) != len(runtime_targets)
                or any(not re.fullmatch(r"[A-Za-z0-9.-]+:[0-9]{1,5}", value) for value in runtime_targets)
                or not 0 < interval <= 15):
            raise ValueError("Select the exact runtime scrape instances and a bounded interval")
        if len(resource_files) != 2 or bundle_maximum < archive.meta["maximumBytes"]:
            raise ValueError("Both hosts' resource evidence and a complete run budget are required")
        for base in (prometheus, zipkin):
            parsed = urlsplit(base)
            if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username
                    or parsed.password or parsed.query or parsed.fragment):
                raise ValueError("Monitoring URLs cannot contain credentials or query strings")
        self.archive, self.prometheus, self.zipkin = archive, prometheus, zipkin
        self.runtime_targets = frozenset(runtime_targets)
        self.resource_files = [safe_directory(path) for path in resource_files]
        self.bundle_maximum = bundle_maximum
        self.interval = interval
        self.metadata = metadata
        if metadata is not None:
            performance.validate_metadata(metadata)
        self.failure = None
        self.started = False
        self.process = None
        self.stop = threading.Event()
        self.mutex = threading.RLock()
        self.next_trace = 0
        scope = {"prometheus": prometheus.rstrip("/"), "zipkin": zipkin.rstrip("/"),
                 "runtimeTargets": sorted(self.runtime_targets), "resourceFiles": [str(p) for p in self.resource_files],
                 "bundleMaximumBytes": bundle_maximum,
                 "metadataSha256": digest(encoded(metadata)) if metadata is not None else None}
        if archive.meta.get("scope", scope) != scope:
            raise ValueError("Resume cannot switch the monitored environment")
        archive.meta["scope"] = scope
        archive._save()
        self.thread = threading.Thread(target=self._run, name="load-evidence", daemon=True)

    def start(self):
        try:
            if shutil.disk_usage(self.archive.path).free < self.bundle_maximum + self.archive.meta["reserveBytes"]:
                raise OSError("Insufficient free space for the bounded run and reserve")
            deadline = time.monotonic() + 45
            while True:
                try:
                    self.capture(deadline=deadline)  # New HTTP counters need at least two scrapes before increase().
                    break
                except performance.ObservationNotReady:
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise
                    time.sleep(min(5, remaining))
            self.wait_smoke(time.monotonic() + 30)
            self.thread.start()
            self.started = True
        except Exception as error:
            self.failure = type(error).__name__
            self.archive.fail(self.failure)
            raise

    def wait_smoke(self, deadline):
        required = self.archive.meta.get("requiredTrace")
        while required:
            name = "trace-" + required["id"] + ".json.gz"
            if name in self.archive.meta["files"]:
                spans = json.loads(unpack((self.archive.path / name).read_bytes()))
                if set(required["names"]) <= {span.get("name") for span in spans}:
                    return
            if time.monotonic() >= deadline:
                self.failure = "MissingSmokeTraceBoundaries"
                self.archive.fail(self.failure)
                raise ValueError("The actual smoke trade's trace boundaries were not captured")
            self.archive.put_trace(required["id"], get_trace(self.zipkin, required["id"],
                timeout=min(5, max(.1, deadline - time.monotonic()))))
            time.sleep(.2)

    def bind(self, process):
        with self.mutex:
            self.process = process
            if self.failure and process.poll() is None:
                process.send_signal(signal.SIGINT)

    def unbind(self):
        with self.mutex:
            self.process = None

    def observe(self, row):
        if row.get("traceSampled") and row.get("traceId"):
            with self.mutex:
                self.archive.expect(row["traceId"])

    def require_smoke(self, trace_id):
        self.archive.expect(trace_id)
        self.archive.meta["requiredTrace"] = {"id": trace_id, "names": ["commerce.outbox.send",
            "payment.inbox.process", "payment.approval.process", "payment.outbox.send", "settlement.inbox.process"]}
        self.archive._save()

    def capture(self, deadline=None):
        at = time.time()
        started = time.monotonic()
        deadline = min(deadline, started + max(5, self.interval)) if deadline is not None else started + max(5, self.interval)
        cache = {}
        def remaining():
            budget = deadline - time.monotonic()
            if budget <= 0:
                raise TimeoutError("Diagnostic collection exceeded its cycle budget")
            return min(5, budget)
        def query(expression):
            if expression not in cache:
                cache[expression] = performance.query(self.prometheus, expression, at, timeout=remaining())
            return cache[expression]
        resources = [resource_record(path, self.archive.run_id, at) for path in self.resource_files]
        if {row["hostRole"] for row in resources} != {"app", "data"}:
            raise ValueError("Both resource host roles must be observed")
        if len({row["hostId"] for row in resources}) != 2:
            raise ValueError("App and data resource evidence must identify two distinct hosts")
        owned_size = sum(regular(path).stat().st_size for path in self.archive.run.rglob("*") if path.is_file())
        if owned_size + sum(path.stat().st_size for path in self.resource_files) >= self.bundle_maximum:
            raise OSError("Complete experiment evidence exceeded its storage budget")
        source = query('business:source{window="900"}')
        operations = [row["metric"].get("operation") for row in source]
        if len(operations) != len(set(operations)) or set(operations) != set(performance.OPERATIONS):
            raise ValueError("Business source coverage is incomplete")
        if any(not 0 <= at - float(row["value"][1]) <= 45 for row in source):
            raise ValueError("Business source observation is stale or future")
        values = {"businessSources": source, "resources": resources, "runBytes": owned_size}
        if self.metadata is not None:
            values["businessSnapshot"] = performance.capture(self.metadata, 900, at, query)
        for name, expression in [("queues", "financial:queue_value"), ("due", "financial:due_queue_value"),
                                  ("lag", "financial:group_value"), ("store", "financial:store_value"),
                                  ("runtime", "runtime:current")]:
            rows = query(expression)
            expected = {"queues": 8, "due": 8, "lag": 3, "store": 2}
            if (name in expected and len(rows) != expected[name]) or (name == "runtime" and not rows):
                raise ValueError("Required diagnostic observation is unavailable: " + name)
            if any(not math.isfinite(float(row["value"][1])) or float(row["value"][1]) < 0 for row in rows):
                raise ValueError("Invalid diagnostic observation: " + name)
            if name == "runtime" and ({row["metric"].get("instance") for row in rows} != self.runtime_targets
                    or any(not 0 <= at - float(row["value"][1]) <= 45 for row in rows)):
                raise ValueError("Runtime coverage does not match both selected hosts")
            values[name] = rows
        for name, expression in [("businessCompletions", 'business:completions{window="900"}'),
                ("businessBuckets", 'business:duration_bucket{window="900"}'),
                ("businessMissing", 'business:duration_missing{window="900"}'),
                ("cohorts", "business:cohort_records"), ("cohortCoverage", "business:cohort_capture_complete"),
                ("memory", "commerce_settle_container_memory_usage_ratio"),
                ("cpu", "commerce_settle_container_cpu_usage_ratio"),
                ("hostMemory", "commerce_settle_docker_host_memory_available_ratio"),
                ("hostCpu", "commerce_settle_docker_host_cpu_usage_ratio"),
                ("jdbc", "jdbc_query_seconds_sum"), ("pool", "hikaricp_connections_active")]:
            rows = query(expression)
            if name in {"memory", "cpu", "hostMemory", "hostCpu", "jdbc", "pool"} and not rows:
                raise ValueError("Diagnostic source is missing: " + name)
            if name in {"memory", "cpu", "hostMemory", "hostCpu", "jdbc", "pool"} and any(
                    not math.isfinite(float(row["value"][1])) or float(row["value"][1]) < 0 for row in rows):
                raise ValueError("Invalid diagnostic source: " + name)
            if name == "memory" and not {"gateway", "commerce", "payment-api", "payment-worker", "settlement",
                                          "finance-mock", "postgres", "kafka", "redis"} <= {row["metric"].get("service") for row in rows}:
                raise ValueError("Runtime service coverage is incomplete")
            if name in {"hostMemory", "hostCpu"} and (
                    {row["metric"].get("instance") for row in rows} != self.runtime_targets
                    or any(float(row["value"][1]) > 1 for row in rows)):
                raise ValueError("Both hosts' capacity must be observed")
            values[name] = rows
        values["criticalAlerts"] = query('ALERTS{alertstate="firing",severity="critical"}')
        rejected = []
        if any(row["metric"].get("basis") == "limit" and float(row["value"][1]) > .95
               for row in values["memory"]):
            rejected.append("ContainerMemoryCritical")
        if any(float(row["value"][1]) < .05 for row in values["hostMemory"]):
            rejected.append("DockerHostMemoryCritical")
        if any(float(row["value"][1]) >= (.8 if self.started else .7) for row in values["store"]):
            rejected.append("DataStoragePressure")
        if values["criticalAlerts"]:
            rejected.append("FiringCriticalAlert")
        values["rejectionReasons"] = rejected
        with self.mutex:
            self.archive.sample({"runId": self.archive.run_id, "at": at,
                                 "collectionSeconds": time.monotonic() - started,
                                 "runtimeTargets": sorted(self.runtime_targets),
                                 "population": "environment snapshot; reconcile with request ledger", **values})
            ids = list(self.archive.meta["expectedTraces"])
        if rejected:
            raise ValueError("Experiment resource/alert preconditions failed: " + ",".join(rejected))
        for index in range(min(8, len(ids))):
            trace_id = ids[(self.next_trace + index) % len(ids)]
            spans = get_trace(self.zipkin, trace_id, timeout=remaining())
            with self.mutex:
                self.archive.put_trace(trace_id, spans)
        self.next_trace += min(8, len(ids))

    def _run(self):
        while not self.stop.wait(self.interval):
            try:
                self.capture()
            except Exception as error:
                with self.mutex:
                    self.failure = type(error).__name__
                    try:
                        self.archive.fail(self.failure)
                    finally:
                        if self.process is not None and self.process.poll() is None:
                            self.process.send_signal(signal.SIGINT)
                return

    def finish(self, complete):
        self.stop.set()
        if self.thread.ident is not None:
            self.thread.join(30)
        if self.thread.is_alive():
            raise RuntimeError("Evidence worker did not stop")
        deadline = time.monotonic() + 30
        try:
            if not self.failure:
                for trace_id in self.archive.meta["expectedTraces"]:
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Final trace capture budget exhausted")
                    self.archive.put_trace(trace_id, get_trace(self.zipkin, trace_id,
                                                               timeout=min(5, deadline - time.monotonic())))
                self.wait_smoke(deadline)
            return self.archive.finish(complete and self.started and not self.failure and not self.archive.meta["failures"])
        finally:
            self.archive.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("review", "cleanup"))
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--manifest-sha256")
    parser.add_argument("--resolution-summary", type=Path)
    args = parser.parse_args(argv)
    archive = Archive(args.run, reading=True)
    try:
        if args.action == "review":
            archive.review(args.resolution_summary)
            print(digest((archive.path / "owner.json").read_bytes()))
        else:
            archive.cleanup(args.manifest_sha256)
    finally:
        archive.close()


if __name__ == "__main__":
    main()
