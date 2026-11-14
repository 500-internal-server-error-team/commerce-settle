import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import render_ecs_observability as renderer

CLUSTER = "arn:aws:ecs:ap-northeast-2:123456789012:cluster/commerce-cluster"
VALUES = (CLUSTER, "10.0.1.10", "10.0.2.10", "commerce-observability")


class EcsObservationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = renderer.compose_services()

    def test_both_roles_render_real_compose_with_private_metrics_and_no_proxy_port(self):
        for role, address in (("app", VALUES[1]), ("data", VALUES[2])):
            with self.subTest(role=role), tempfile.TemporaryDirectory() as directory:
                value = renderer.runtime(role, *VALUES, self.source)
                path = Path(directory) / "compose.json"
                path.write_text(json.dumps(value))
                result = subprocess.run(["docker", "compose", "--env-file", "/dev/null", "-f", str(path),
                    "config", "--no-interpolate", "--format", "json"], capture_output=True, text=True, check=True, timeout=15)
                actual = json.loads(result.stdout)["services"]
                proxy, collector = actual["docker-metrics-proxy"], actual["container-metrics"]
                self.assertNotIn("ports", proxy)
                self.assertEqual(collector["ports"][0]["host_ip"], address)
                self.assertEqual(proxy["environment"]["OBSERVER_ECS_CLUSTER"], CLUSTER)
                self.assertEqual(collector["environment"]["OBSERVER_REQUIRED_SERVICES"], proxy["environment"]["OBSERVER_SERVICES"])
                self.assertEqual(proxy["environment"]["OBSERVER_PROJECT"], VALUES[3])
                for item in actual.values():
                    self.assertIn(item["mem_limit"], ("64m", 64 * 1024 * 1024))
                    self.assertTrue(item["read_only"])

    def test_prometheus_preserves_rules_and_routes_and_maps_all_six_actual_ports(self):
        value = renderer.prometheus(*VALUES)
        source = (renderer.ROOT / "infra/prometheus/prometheus.yml").read_text()
        self.assertEqual(value.split("scrape_configs:")[0], source.split("scrape_configs:")[0].replace("alertmanager:9093", VALUES[2] + ":9093"))
        blocks = re.findall(r"(?m)^    static_configs: (\[.*\])$", value)
        app, storage, hosts, zipkin = map(json.loads, blocks)
        self.assertEqual({row["labels"]["service"] for row in app}, set(renderer.SERVICES))
        for row in app:
            service = row["labels"]["service"]
            self.assertEqual(row["targets"], [VALUES[1] + ":" + str(renderer.PORTS[service][1])])
            self.assertEqual(row["labels"]["instance"], service + ":9091")
            self.assertEqual(row["labels"]["target_address"], row["targets"][0])
        self.assertEqual(hosts, [{"targets": [VALUES[1] + ":9109", VALUES[2] + ":9109"]}])
        self.assertEqual(storage, [{"targets": [VALUES[2] + ":9108"]}])
        self.assertEqual(zipkin, [{"targets": [VALUES[2] + ":9411"]}])

    def test_persistent_zipkin_reuses_native_config_and_private_ecs_sidecar(self):
        role = "arn:aws:iam::123456789012:role/ecsTaskExecutionRole"
        prefix = "arn:aws:ssm:ap-northeast-2:123456789012:parameter/commerce-settle/"
        task = renderer.zipkin_task(CLUSTER, role, prefix)
        self.assertEqual(task["family"], "commerce-settle-zipkin")
        self.assertEqual(task["networkMode"], "bridge")
        self.assertNotIn("memory", task)
        zipkin, store, initializer = task["containerDefinitions"]
        self.assertEqual((zipkin["memory"], store["memory"]), (384, 1024))
        self.assertNotIn("portMappings", store)
        self.assertEqual(zipkin["links"], ["zipkin-store:zipkin-store"])
        self.assertEqual(zipkin["dependsOn"], [{"containerName": "zipkin-storage-init", "condition": "SUCCESS"}])
        self.assertFalse(initializer["essential"])
        self.assertEqual(initializer["dependsOn"], [{"containerName": "zipkin-store", "condition": "HEALTHY"}])
        for container in (zipkin, store, initializer):
            self.assertEqual(container["secrets"][0]["valueFrom"], prefix + "ZIPKIN_STORAGE_PASSWORD")
            self.assertNotIn("$${", json.dumps(container))
            self.assertFalse(any("PASSWORD" in row["name"] for row in container["environment"]))
        required = renderer.runtime("data", *VALUES, self.source, persistent=True)["services"]["docker-metrics-proxy"]["environment"]["OBSERVER_SERVICES"]
        self.assertIn("zipkin-store", required.split(","))
        for wrong_role, wrong_prefix in ((role.replace("123456789012", "999999999999"), prefix),
                                          (role, prefix.replace("ap-northeast-2", "us-east-1"))):
            with self.assertRaises(ValueError):
                renderer.zipkin_task(CLUSTER, wrong_role, wrong_prefix)

    def test_rejects_public_addresses_shared_host_and_ambiguous_cluster_or_project(self):
        for index, invalid in ((0, "commerce-cluster"), (1, "8.8.8.8"), (2, VALUES[1]), (3, "../other")):
            values = list(VALUES); values[index] = invalid
            with self.subTest(index=index), self.assertRaises(ValueError):
                renderer.prometheus(*values)


if __name__ == "__main__":
    unittest.main()
