import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import Mock, patch


spec = importlib.util.spec_from_file_location(
    "runtime_metrics", Path(__file__).resolve().parents[1] / "infra/observability/runtime_metrics.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
IDENTITY = "a" * 64
FOREIGN = "b" * 64


class DockerBoundaryTest(unittest.TestCase):
    def proxy(self):
        def engine(path):
            if path.startswith("/containers/json?"):
                return [{"Id": identity, "Labels": {"com.docker.compose.project": project,
                        "com.docker.compose.service": service}}
                        for identity, project, service in [(IDENTITY, "owned", "payment-worker"),
                            (FOREIGN, "foreign", "payment-worker"), ("c"*64, "owned", "unselected")]]
            return {"State": {"Running": True, "OOMKilled": False, "ExitCode": 0},
                    "HostConfig": {"Memory": 100}, "Config": {"Env": ["SECRET=never-export"]},
                    "Mounts": [{"Source": "private"}]}
        backend = Mock(side_effect=engine)
        return runtime.DockerReadProxy("owned", ["payment-worker"], backend), backend

    def test_filters_before_inspect_and_does_not_export_secrets(self):
        proxy, engine = self.proxy()
        self.assertEqual(proxy.get("/containers/json"), [{"Id": IDENTITY, "service": "payment-worker"}])
        with self.assertRaises(PermissionError):
            proxy.get("/containers/" + FOREIGN + "/json")
        self.assertEqual(engine.call_count, 1)
        exported = proxy.get("/containers/" + IDENTITY + "/json")
        self.assertEqual(set(exported), {"State", "HostConfig"})
        self.assertNotIn("SECRET", json.dumps(exported))
        self.assertNotIn("private", json.dumps(exported))

    def test_rejects_other_get_endpoints_and_unbounded_queries_before_dispatch(self):
        proxy, engine = self.proxy()
        proxy.get("/containers/json")
        for path in ("/version", "/images/json", "/containers/"+IDENTITY+"/archive?path=/run",
                     "/containers/json?all=true", "/containers/"+IDENTITY+"/stats?stream=true",
                     "/events?since=0&until=1", "http://foreign/containers/json"):
            with self.subTest(path=path), self.assertRaises((PermissionError, ValueError)):
                proxy.get(path)
        self.assertEqual(engine.call_count, 1)

    def test_events_are_project_and_service_scoped_even_if_engine_returns_other_rows(self):
        def event(project, service, action):
            return {"Action": action, "Actor": {"ID": IDENTITY, "Attributes": {
                "com.docker.compose.project": project, "com.docker.compose.service": service,
                "exitCode": "137", "secret": "omitted"}}, "timeNano": 995_000_000_000}
        engine = Mock(return_value=[event("owned", "payment-worker", "die"),
                                    event("foreign", "payment-worker", "oom"),
                                    event("owned", "unselected", "oom")])
        proxy = runtime.DockerReadProxy("owned", ["payment-worker"], engine)
        with patch.object(runtime.time, "time", return_value=1000):
            result = proxy.get("/events?since=990&until=999")
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["exitCode"], "137")
        self.assertNotIn("secret", json.dumps(result))
        request = engine.call_args.args[0]
        self.assertIn("filters=", request)


class RuntimeMetricsTest(unittest.TestCase):
    def fetch(self, path):
        if path == "/containers/json":
            return [{"Id": IDENTITY, "service": "payment-worker"}]
        if path == "/info":
            return {"NCPU": 4, "cpuUsageRatio": .6, "memoryAvailableRatio": .2}
        if path.endswith("/json"):
            return {"State": {"Running": True, "OOMKilled": False, "ExitCode": 0},
                    "HostConfig": {"Memory": 100, "NanoCpus": 500_000_000,
                                   "CpuQuota": 0, "CpuPeriod": 0}}
        if "/stats?" in path:
            return {"memory_stats": {"usage": 90, "limit": 1000},
                    "cpu_stats": {"cpu_usage": {"total_usage": 200}, "system_cpu_usage": 2000, "online_cpus": 4},
                    "precpu_stats": {"cpu_usage": {"total_usage": 100}, "system_cpu_usage": 1000}}
        if path.startswith("/events?"):
            return [{"service": "payment-worker", "action": "die", "exitCode": "137",
                     "timeNano": 995_000_000_000, "id": IDENTITY}]
        self.fail(path)

    def test_empty_or_partial_required_scope_cannot_publish_fresh_observation(self):
        with patch.object(runtime.time, "time", return_value=1000):
            metrics = runtime.RuntimeMetrics(self.fetch, ["payment-worker", "settlement"])
            with self.assertRaises(ValueError):
                metrics.refresh()
            self.assertEqual(metrics.render(), b"commerce_settle_runtime_observed_timestamp_seconds 0\n")
            empty = runtime.RuntimeMetrics(lambda _: [])
            with self.assertRaises(ValueError):
                empty.refresh()

    def test_real_limits_and_events_are_reported_without_container_id_labels(self):
        with patch.object(runtime.time, "time", return_value=1000):
            metrics = runtime.RuntimeMetrics(self.fetch)
            metrics.refresh()
        result = metrics.render().decode()
        self.assertIn('memory_usage_ratio{basis="limit",service="payment-worker"} 0.9', result)
        self.assertIn('cpu_usage_ratio{basis="quota",service="payment-worker"} 0.8', result)
        self.assertIn('last_abnormal_exit_timestamp_seconds{service="payment-worker"} 995.0', result)
        self.assertNotIn(IDENTITY, result)
        self.assertIn('runtime_observed_timestamp_seconds 1000', result)

    def test_observation_failure_keeps_old_timestamp_and_is_not_a_zero_sample(self):
        with patch.object(runtime.time, "time", return_value=1000):
            metrics = runtime.RuntimeMetrics(self.fetch)
            metrics.refresh()
        before = metrics.render()
        metrics.fetch = Mock(side_effect=OSError("unavailable"))
        with self.assertRaises(OSError):
            metrics.refresh()
        self.assertEqual(metrics.render(), before)

    def test_unlimited_cpu_uses_host_share_and_does_not_claim_a_quota(self):
        def fetch(path):
            value = self.fetch(path)
            if path.endswith("/json") and path != "/containers/json":
                value["HostConfig"]["NanoCpus"] = 0
            return value
        with patch.object(runtime.time, "time", return_value=1000):
            metrics = runtime.RuntimeMetrics(fetch)
            metrics.refresh()
        self.assertIn('cpu_usage_ratio{basis="host_shared",service="payment-worker"} 0.1', metrics.render().decode())

    def test_restart_marks_the_unobserved_event_window_even_when_current_state_is_healthy(self):
        requests = []
        def fetch(path):
            if path.startswith('/events?'):
                requests.append(path)
                return []  # The exit at 1050 is outside the restarted collector's query.
            return self.fetch(path)
        with patch.object(runtime.time, 'time', return_value=1120):
            restarted = runtime.RuntimeMetrics(fetch)
            restarted.refresh()
        self.assertEqual(['/events?since=1089&until=1119'], requests)
        self.assertIn('runtime_event_gap_timestamp_seconds 1120', restarted.render().decode())
        for now in range(1135, 1436, 15):
            with patch.object(runtime.time, 'time', return_value=now):
                restarted.refresh()
        self.assertIn('runtime_event_gap_timestamp_seconds 1120', restarted.render().decode())


if __name__ == "__main__":
    unittest.main()
