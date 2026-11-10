"""Check data deployment boundaries against the real Compose source; no daemon needed."""

import json
import unittest
from unittest.mock import patch
import render_ecs_data as ecs


ROLE = "arn:aws:iam::111122223333:role/ecs-data-execution"
PREFIX = "arn:aws:ssm:ap-northeast-2:111122223333:parameter/commerce-settle/data/"


class RenderEcsDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.services = ecs.compose_services()

    def task(self, service, **changes):
        arguments = dict(region="ap-northeast-2", role=ROLE, parameter_prefix=PREFIX, private_ip="172.31.54.44")
        return ecs.render(service, self.services, **(arguments | changes))

    def test_passwords_only_reference_parameters_even_when_local_env_has_values(self):
        with patch.dict("os.environ", {"POSTGRES_PASSWORD": "do-not-export-local-password"}):
            services = ecs.compose_services()
        for service, expected in (("postgres", 5), ("redis", 6), ("kafka", 0)):
            with self.subTest(service=service):
                task = ecs.render(service, services, "ap-northeast-2", ROLE, PREFIX, "172.31.54.44")
                container = task["containerDefinitions"][0]
                self.assertEqual(len(container["secrets"]), expected)
                self.assertTrue(all(item["valueFrom"] == PREFIX + item["name"] for item in container["secrets"]))
                self.assertFalse(any(item["name"].endswith("_PASSWORD") for item in container["environment"]))
                self.assertNotIn("do-not-export-local-password", json.dumps(task))

    def test_kafka_advertises_reachable_private_host_and_does_not_publish_controller(self):
        task = self.task("kafka")
        container = task["containerDefinitions"][0]
        environment = {item["name"]: item["value"] for item in container["environment"]}
        self.assertEqual(environment["KAFKA_ADVERTISED_LISTENERS"], "PLAINTEXT://172.31.54.44:9092")
        self.assertEqual(environment["KAFKA_CONTROLLER_QUORUM_VOTERS"], "1@127.0.0.1:9093")
        self.assertEqual([port["hostPort"] for port in container["portMappings"]], [9092])
        self.assertEqual(task["runtimePlatform"]["cpuArchitecture"], "ARM64")
        self.assertNotIn("memory", task)  # Existing Ubuntu hosts use the systemd cgroup driver.

    def test_postgres_uses_pg18_parent_mount_and_existing_read_only_initializer(self):
        task = self.task("postgres")
        mounts = task["containerDefinitions"][0]["mountPoints"]
        self.assertEqual(mounts[0]["containerPath"], "/var/lib/postgresql")
        self.assertEqual([item["readOnly"] for item in mounts], [False, True, True])
        self.assertEqual(task["volumes"][0]["host"]["sourcePath"], "/srv/commerce-settle/postgres/data")
        self.assertEqual(task["placementConstraints"][0]["expression"], "attribute:workload == data")

    def test_redis_preserves_acl_and_shell_expansion(self):
        command = self.task("redis")["containerDefinitions"][0]["command"]
        self.assertEqual(command, [part.replace("$$", "$") for part in self.services["redis"]["command"]])
        self.assertNotIn("$$", json.dumps(command))
        self.assertIn('"%R~commerce-settle:auth:session:*"', command[2])
        self.assertIn("--maxmemory-policy noeviction", command[2])

    def test_rejects_public_address_and_cross_account_or_region_secret_prefix(self):
        for changes in ({"private_ip": "13.125.2.200"}, {"private_ip": "::1"},
                        {"region": "us-east-1"}, {"role": ROLE.replace("111122223333", "444455556666")},
                        {"parameter_prefix": PREFIX.rstrip("/")}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.task("postgres", **changes)


if __name__ == "__main__":
    unittest.main()
