"""Project-scoped Docker GET proxy and Prometheus observer; neither endpoint is published.

Run --proxy with the socket on a private network; the collector has no socket mount.
Inspect responses contain only State/HostConfig fields, never Env, mounts or labels.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import re
import socket
import threading
import time
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import urlopen


def read_json(response, limit=8 * 1024 * 1024):
    raw = response.read(limit + 1)
    if len(raw) > limit:
        raise ValueError("Docker observation exceeds the response bound")
    return json.loads(raw)


class UnixConnection(http.client.HTTPConnection):
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect("/var/run/docker.sock")


class DockerReadProxy:
    def __init__(self, project, services, engine=None, *, ecs_cluster=None):
        if project is not None and not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,100}", project):
            raise ValueError("An explicit Compose project is required")
        cluster = (re.fullmatch(r"arn:(aws(?:-cn|-us-gov)?):ecs:([a-z0-9-]+):(\d{12}):cluster/([A-Za-z0-9_-]{1,255})", ecs_cluster)
                   if isinstance(ecs_cluster, str) else None)
        if (ecs_cluster is not None and cluster is None) or (project is None and cluster is None):
            raise ValueError("Select a Compose project or an exact ECS cluster ARN")
        if not services or any(not re.fullmatch(r"[a-z][a-z0-9-]{0,60}", s) for s in services):
            raise ValueError("An explicit service allowlist is required")
        self.project, self.services = project, frozenset(services)
        self.cluster = cluster
        self.engine = engine or self._engine
        self.containers = {}
        self.lock = threading.Lock()
        self.cpu = None

    def label_filters(self):
        result = []
        if self.project is not None:
            result.append("com.docker.compose.project=" + self.project)
        if self.cluster is not None:
            result.append("com.amazonaws.ecs.cluster=" + self.cluster[4])
        return result

    def service(self, labels):
        if self.project is not None and labels.get("com.docker.compose.project") == self.project:
            service = labels.get("com.docker.compose.service")
            if service in self.services:
                return service
        if self.cluster is not None and labels.get("com.amazonaws.ecs.cluster") == self.cluster[4]:
            service = labels.get("com.amazonaws.ecs.container-name")
            prefix = "arn:" + self.cluster[1] + ":ecs:" + self.cluster[2] + ":" + self.cluster[3] + ":task/"
            task = labels.get("com.amazonaws.ecs.task-arn", "")
            family = "commerce-settle-" + ("zipkin" if service == "zipkin-store" else str(service))
            if (service in self.services
                    and labels.get("com.amazonaws.ecs.task-definition-family") == family
                    and re.fullmatch(re.escape(prefix) + "(?:" + re.escape(self.cluster[4]) + "/)?[a-f0-9]{32}", task)):
                return service
        return None

    def host(self):
        # These are Docker's Linux host/VM totals, never the macOS host's totals.
        ticks = [int(x) for x in Path("/proc/stat").read_text().splitlines()[0].split()[1:9]]
        current = (sum(ticks), ticks[3] + ticks[4])
        memory = dict((line.split(":")[0], int(line.split()[1]))
                      for line in Path("/proc/meminfo").read_text().splitlines() if ":" in line)
        with self.lock:
            previous, self.cpu = self.cpu, current
        busy = None
        if previous and current[0] > previous[0]:
            busy = 1 - (current[1] - previous[1]) / (current[0] - previous[0])
        return {"cpuUsageRatio": busy,
                "memoryAvailableRatio": memory["MemAvailable"] / memory["MemTotal"]}

    @staticmethod
    def _engine(path):
        connection = UnixConnection("localhost", timeout=5)
        try:
            connection.request("GET", path)
            response = connection.getresponse()
            if response.status != 200:
                raise ValueError("Docker GET failed")
            if path.startswith("/events?"):
                raw = response.read(8 * 1024 * 1024 + 1)
                if len(raw) > 8 * 1024 * 1024:
                    raise ValueError("Event observation exceeds the response bound")
                return [json.loads(line) for line in raw.splitlines() if line.strip()]
            return read_json(response)
        finally:
            connection.close()

    def get(self, request):
        parsed = urlsplit(request)
        if parsed.scheme or parsed.netloc or parsed.fragment:
            raise PermissionError("Only relative allowlisted paths are accepted")
        path, query = parsed.path, parse_qs(parsed.query, keep_blank_values=True)
        if path == "/containers/json" and not query:
            rows = {}
            for label in self.label_filters():
                filters = {"label": [label]}
                for row in self.engine("/containers/json?" + urlencode({"all": "true", "filters": json.dumps(filters)})):
                    rows[row.get("Id", "")] = row
            services = {}
            for identity, row in rows.items():
                service = self.service(row.get("Labels", {}))
                if service is not None and re.fullmatch(r"[a-f0-9]{64}", identity):
                    services.setdefault(service, []).append(row)
            selected = {}
            for service, candidates in services.items():
                running = [row for row in candidates if row.get("State") == "running"]
                current = running or [max(candidates, key=lambda value: (int(value.get("Created", 0)), value["Id"]))]
                for row in current:
                    selected[row["Id"]] = service
            with self.lock:
                self.containers = selected
            return [{"Id": identity, "service": service} for identity, service in selected.items()]
        if path == "/info" and not query:
            info = self.engine("/info")
            return {"NCPU": int(info["NCPU"]), **self.host()}
        if path == "/events" and set(query) == {"since", "until"} and all(len(v) == 1 for v in query.values()):
            since, until = int(query["since"][0]), int(query["until"][0])
            now = time.time()
            if not now - 90 <= since <= until <= now:
                raise PermissionError("Only bounded, historical event windows are accepted")
            events = []
            for label in self.label_filters():
                filters = {"label": [label], "type": ["container"], "event": ["oom", "die"]}
                events.extend(self.engine("/events?" + urlencode({"since": since, "until": until, "filters": json.dumps(filters)})))
            result = []
            seen = set()
            for event in events:
                attributes = event.get("Actor", {}).get("Attributes", {})
                service = self.service(attributes)
                identity = event.get("Actor", {}).get("ID", "")
                key = (identity, event.get("Action"), event.get("timeNano", 0))
                if (service is not None and re.fullmatch(r"[a-f0-9]{64}", identity)
                        and event.get("Action") in ("oom", "die") and key not in seen):
                    seen.add(key)
                    result.append({"id": event.get("Actor", {}).get("ID"),
                                   "service": service,
                                   "action": event["Action"], "timeNano": event.get("timeNano", 0),
                                   "exitCode": attributes.get("exitCode", "0")})
            return result
        match = re.fullmatch(r"/containers/([a-f0-9]{64})/(json|stats)", path)
        if match:
            identity, kind = match.groups()
            with self.lock:
                permitted = identity in self.containers
            if not permitted:
                raise PermissionError("Container is outside the selected observation")
            if kind == "json" and not query:
                row = self.engine(path)
                return {"State": {k: row["State"].get(k) for k in
                                  ("Running", "OOMKilled", "ExitCode", "StartedAt", "FinishedAt")},
                        "HostConfig": {k: row["HostConfig"].get(k, 0) for k in
                                       ("Memory", "NanoCpus", "CpuQuota", "CpuPeriod")}}
            if kind == "stats" and query == {"stream": ["false"]}:
                row = self.engine(path + "?stream=false")
                return {key: row.get(key, {}) for key in ("cpu_stats", "precpu_stats", "memory_stats")}
        raise PermissionError("Docker endpoint is not allowlisted")


class RuntimeMetrics:
    def __init__(self, fetch, required_services=()):
        self.fetch = fetch
        self.required_services = frozenset(required_services)
        self.snapshot = b""
        self.events = {}
        self.cursor = int(time.time()) - 30
        self.coverage_start = self.cursor
        # A new process cannot prove coverage before its initial historical query.
        self.event_gap = time.time()
        self.lock = threading.Lock()

    def refresh(self):
        # Failure retains the old observation timestamp; it never publishes a successful zero.
        now = time.time()
        rows = self.fetch("/containers/json")
        present = {row["service"] for row in rows}
        if not rows or not self.required_services <= present:
            raise ValueError("Required container observation coverage is incomplete")
        host = self.fetch("/info")
        cores = host["NCPU"]
        if cores <= 0:
            raise ValueError("Invalid Docker host CPU capacity")
        points = []

        def container(row):
            identity, service = row["Id"], row["service"]
            info = self.fetch("/containers/" + identity + "/json")
            state, config = info["State"], info["HostConfig"]
            values = [("running", int(bool(state["Running"])), {}),
                      ("oom_killed", int(bool(state["OOMKilled"])), {}),
                      ("last_exit_code", int(state["ExitCode"]), {})]
            if state["Running"]:
                stats = self.fetch("/containers/" + identity + "/stats?stream=false")
                memory = stats["memory_stats"]
                capacity = config["Memory"] or memory["limit"]
                memory_basis = "limit" if config["Memory"] else "host_shared"
                if capacity <= 0:
                    raise ValueError("Invalid memory capacity")
                values.extend([("memory_usage_bytes", memory["usage"], {}),
                               ("memory_capacity_bytes", capacity, {"basis": memory_basis}),
                               ("memory_usage_ratio", memory["usage"] / capacity, {"basis": memory_basis})])
                current, previous = stats["cpu_stats"], stats["precpu_stats"]
                delta = current["cpu_usage"]["total_usage"] - previous.get("cpu_usage", {}).get("total_usage", 0)
                system = current["system_cpu_usage"] - previous.get("system_cpu_usage", 0)
                quota = config["NanoCpus"] / 1e9
                if not quota and config["CpuQuota"] > 0 and config["CpuPeriod"] > 0:
                    quota = config["CpuQuota"] / config["CpuPeriod"]
                if system > 0 and delta >= 0:
                    used = delta / system * current.get("online_cpus", cores)
                    values.append(("cpu_usage_ratio", used / (quota or cores),
                                   {"basis": "quota" if quota else "host_shared"}))
            return [(name, value, {"service": service, **labels}) for name, value, labels in values]

        with ThreadPoolExecutor(max_workers=4) as pool:
            for values in pool.map(container, rows):
                points.extend(values)
        until = int(time.time()) - 1
        since = max(self.cursor - 1, until - 60)
        if since > self.cursor:
            self.coverage_start = since
            self.event_gap = now
        if until >= since:
            events = self.fetch("/events?" + urlencode({"since": since, "until": until}))
            for event in events:
                if event["action"] == "oom" or int(event["exitCode"]) != 0:
                    kind = "oom" if event["action"] == "oom" else "abnormal_exit"
                    key = (event["service"], kind)
                    self.events[key] = max(self.events.get(key, 0), event["timeNano"] / 1e9)
            self.cursor = until
        # Aggregate replicas with max: a healthy replica must not hide a failing/pressured one.
        grouped = {}
        for name, value, labels in points:
            key = (name, tuple(sorted(labels.items())))
            grouped[key] = max(grouped.get(key, -math.inf), value)
        for (service, kind), observed in self.events.items():
            grouped[("last_" + kind + "_timestamp_seconds", (("service", service),))] = observed
        lines = []
        for (name, labels), value in sorted(grouped.items()):
            label_text = ",".join(k + "=" + json.dumps(v) for k, v in labels)
            lines.append("commerce_settle_container_" + name + "{" + label_text + "} " + str(value))
        if host.get("cpuUsageRatio") is not None:
            lines.append("commerce_settle_docker_host_cpu_usage_ratio " + str(host["cpuUsageRatio"]))
        if host.get("memoryAvailableRatio") is not None:
            lines.append("commerce_settle_docker_host_memory_available_ratio " + str(host["memoryAvailableRatio"]))
        lines.append("commerce_settle_runtime_observed_timestamp_seconds " + str(now))
        lines.append("commerce_settle_runtime_event_coverage_start_timestamp_seconds " + str(self.coverage_start))
        lines.append("commerce_settle_runtime_event_gap_timestamp_seconds " + str(self.event_gap))
        with self.lock:
            self.snapshot = ("\n".join(lines) + "\n").encode()

    def render(self):
        with self.lock:
            return self.snapshot or b"commerce_settle_runtime_observed_timestamp_seconds 0\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--proxy", action="store_true")
    args = parser.parse_args()
    if args.proxy:
        proxy = DockerReadProxy(os.environ.get("OBSERVER_PROJECT") or None, os.environ["OBSERVER_SERVICES"].split(","),
                                ecs_cluster=os.environ.get("OBSERVER_ECS_CLUSTER") or None)
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                try:
                    body = json.dumps(proxy.get(self.path)).encode()
                except (PermissionError, ValueError):
                    self.send_error(403)
                    return
                except (OSError, KeyError, http.client.HTTPException):
                    self.send_error(503)
                    return
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_):
                pass
        ThreadingHTTPServer(("0.0.0.0", 2375), Handler).serve_forever()
    else:
        base = os.environ.get("DOCKER_METRICS_URL", "http://docker-metrics-proxy:2375")
        def fetch(path):
            with urlopen(base + path, timeout=8) as response:
                return read_json(response)
        metrics = RuntimeMetrics(fetch, os.environ["OBSERVER_REQUIRED_SERVICES"].split(","))
        def observe():
            while True:
                try:
                    metrics.refresh()
                except (OSError, ValueError, KeyError, http.client.HTTPException):
                    pass
                time.sleep(15)
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != "/metrics":
                    self.send_error(404)
                    return
                body = metrics.render()
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; version=0.0.4")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_):
                pass
        threading.Thread(target=observe, daemon=True, name="runtime-observer").start()
        ThreadingHTTPServer(("0.0.0.0", 9109), Handler).serve_forever()


if __name__ == "__main__":
    main()
