"""Keep CI optimizations from weakening production rules or topic checks."""

from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import smoke
import smoke_retention
from test_observability import integration_rules


class CiHelpersTest(unittest.TestCase):
    def test_retention_fixture_refuses_non_ci_or_another_project(self):
        for environment in [{"CI": "false", "COMPOSE_PROJECT_NAME": "commerce-settle-ci"},
                            {"CI": "true", "COMPOSE_PROJECT_NAME": "commerce-settle"}]:
            with self.subTest(environment=environment), patch.dict("os.environ", environment), \
                    patch.object(smoke_retention, "compose") as cli, self.assertRaises(RuntimeError):
                smoke_retention.run()
            cli.assert_not_called()

    def description(self):
        lines = []
        for owner in ["commerce", "payment", "settlement", "commerce-notifications"]:
            topics = [(f"commerce-settle.{owner}.v1.dlt", 604800000)]
            if owner != "commerce-notifications":
                topics.append((f"commerce-settle.{owner}.v1", 86400000))
            for name, retention in topics:
                lines.append(
                    f"Topic: {name}\tTopicId: test\tPartitionCount: 3\tReplicationFactor: 1\t"
                    f"Configs: cleanup.policy=delete,retention.ms={retention},retention.bytes=-1"
                )
                lines.append(f"\tTopic: {name}\tPartition: 0\tLeader: 1")
        return "\n".join(lines)

    def test_queries_all_topic_settings_once(self):
        with patch.object(smoke, "compose", return_value=SimpleNamespace(stdout=self.description())) as cli:
            smoke.check_kafka_topics()
        cli.assert_called_once_with(
            "exec", "-T", "kafka", "/opt/kafka/bin/kafka-topics.sh",
            "--bootstrap-server", "kafka:9092", "--describe", "--exclude-internal",
        )

    def test_rejects_missing_duplicate_and_wrong_topic_settings(self):
        valid = self.description()
        invalid = [
            valid.replace("commerce-settle.commerce.v1.dlt", "unrelated-topic"),
            valid + "\n" + valid.splitlines()[0],
        ]
        for before, after in [
            ("PartitionCount: 3", "PartitionCount: 2"),
            ("ReplicationFactor: 1", "ReplicationFactor: 2"),
            ("cleanup.policy=delete", "cleanup.policy=compact"),
            ("retention.ms=604800000", "retention.ms=86400000"),
            ("retention.ms=86400000", "retention.ms=604800000"),
            ("retention.bytes=-1", "retention.bytes=1024"),
        ]:
            invalid.append(valid.replace(before, after, 1))
        for description in invalid:
            with self.subTest(description=description), patch.object(
                smoke, "compose", return_value=SimpleNamespace(stdout=description),
            ), self.assertRaises(AssertionError):
                smoke.check_kafka_topics()

    def test_only_accelerates_production_group_schedule(self):
        production = (Path(__file__).resolve().parents[1] / "infra/prometheus/quarantine.rules.yml").read_text()
        accelerated = integration_rules(production)
        self.assertIn("    interval: 1s\n", accelerated)
        self.assertEqual(accelerated.replace("    interval: 1s\n", "    interval: 15s\n"), production)

    def test_accelerates_other_group_intervals_without_changing_expressions(self):
        production = "groups:\n  - name: other\n    interval: 30s\n    rules:\n      - record: sample\n        expr: rate(sample_total[15s])\n"
        self.assertEqual(integration_rules(production), production.replace("interval: 30s", "interval: 1s"))


if __name__ == "__main__":
    unittest.main()
