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
CLUSTER = "arn:aws:ecs:ap-northeast-2:123456789012:cluster/owned"


def ecs_labels(service="payment-worker", cluster="owned", account="123456789012"):
    return {"com.amazonaws.ecs.cluster": cluster,
            "com.amazonaws.ecs.container-name": service,
            "com.amazonaws.ecs.task-definition-family": "commerce-settle-" + service,
            "com.amazonaws.ecs.task-arn": f"arn:aws:ecs:ap-northeast-2:{account}:task/{cluster}/" + "1" * 32}


class DockerBoundaryTest(unittest.TestCase):
    def test_compose_service_and_its_live_oom_probe_are_both_observed(self):
        labels = {"com.docker.compose.project": "owned", "com.docker.compose.service": "finance-mock"}
        rows = [{"Id": identity, "Labels": labels, "State": "running", "Created": created}
                for identity, created in ((IDENTITY, 1), (FOREIGN, 2))]
        proxy = runtime.DockerReadProxy("owned", ["finance-mock"], Mock(return_value=rows))
        self.assertEqual({row["Id"] for row in proxy.get("/containers/json")}, {IDENTITY, FOREIGN})

    def test_ecs_selection_checks_cluster_account_family_and_container_before_inspect(self):
        labels = [ecs_labels(), ecs_labels(cluster="foreign"), ecs_labels(service="commerce"),
                  ecs_labels(account="999999999999"),
                  {**ecs_labels(), "com.amazonaws.ecs.task-definition-family": "foreign"},
                  {**ecs_labels(), "com.amazonaws.ecs.task-arn": "missing"}]
        backend = Mock(return_value=[{"Id": str(i + 1) * 64, "Labels": value}
                                     for i, value in enumerate(labels)])
        proxy = runtime.DockerReadProxy(None, ["payment-worker"], backend, ecs_cluster=CLUSTER)
        self.assertEqual(proxy.get("/containers/json"), [{"Id": "1" * 64, "service": "payment-worker"}])
        self.assertIn("com.amazonaws.ecs.cluster", backend.call_args.args[0])
        with self.assertRaises(PermissionError):
            proxy.get("/containers/" + "2" * 64 + "/json")
        self.assertEqual(backend.call_count, 1)

    def test_ecs_events_preserve_service_boundary_and_never_export_other_attributes(self):
        def event(labels):
            return {"Action": "die", "Actor": {"ID": IDENTITY, "Attributes": {
                **labels, "exitCode": "137", "private": "never-export"}}, "timeNano": 995_000_000_000}
        backend = Mock(return_value=[event(ecs_labels()), event(ecs_labels(cluster="foreign")),
                                     event(ecs_labels(service="commerce"))])
        proxy = runtime.DockerReadProxy(None, ["payment-worker"], backend, ecs_cluster=CLUSTER)
        with patch.object(runtime.time, "time", return_value=1000):
            result = proxy.get("/events?since=990&until=999")
        self.assertEqual(len(result), 1)
        self.assertEqual(result[0]["service"], "payment-worker")
        self.assertEqual(result[0]["exitCode"], "137")
        self.assertNotIn("private", json.dumps(result))

    def test_replacement_ignores_stopped_history_and_preserves_running_replicas(self):
        old = {"Id": FOREIGN, "Labels": ecs_labels(), "State": "exited", "Created": 1}
        new = {"Id": IDENTITY, "Labels": ecs_labels(), "State": "running", "Created": 2}
        backend = Mock(return_value=[old, new])
        proxy = runtime.DockerReadProxy(None, ["payment-worker"], backend, ecs_cluster=CLUSTER)
        self.assertEqual(proxy.get("/containers/json"), [{"Id": IDENTITY, "service": "payment-worker"}])
        backend.return_value = [{**old, "State": "running"}, new]
        self.assertEqual({row["Id"] for row in proxy.get("/containers/json")}, {IDENTITY, FOREIGN})

    def test_missing_or_invalid_scope_is_rejected(self):
        for project, services, cluster in [(None, ["commerce"], None),
                (None, ["commerce"], "owned"), (None, [], CLUSTER),
                ("unsafe/other", ["commerce"], CLUSTER), (None, ["~internal~ecs~pause"], CLUSTER)]:
            with self.subTest(project=project, services=services, cluster=cluster), self.assertRaises(ValueError):
                runtime.DockerReadProxy(project, services, ecs_cluster=cluster)

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
