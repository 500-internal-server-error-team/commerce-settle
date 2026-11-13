"""Keep CI optimizations from weakening production rules or topic checks."""

from copy import deepcopy
import json
import os
from pathlib import Path
import re
import subprocess
import textwrap
from types import SimpleNamespace
import unittest
from unittest.mock import call, patch

import smoke
import smoke_financial_flow
import smoke_retention
import smoke_restart
from test_observability import integration_rules


class CiHelpersTest(unittest.TestCase):
    def test_retention_fixture_refuses_non_ci_or_another_project(self):
        for environment in [{"CI": "false", "COMPOSE_PROJECT_NAME": "commerce-settle-ci"},
                            {"CI": "true", "COMPOSE_PROJECT_NAME": "commerce-settle"}]:
            with self.subTest(environment=environment), patch.dict("os.environ", environment), \
                    patch.object(smoke_retention, "sql", side_effect=AssertionError("CI guard not checked")) as sql, \
                    self.assertRaises(RuntimeError):
                smoke_retention.run()
            sql.assert_not_called()

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


class FinancialReplayWaitTest(unittest.TestCase):
    def targets(self, count=3):
        return {(f"commerce-settle.{owner}.v1", partition): 5
                for owner in ("commerce", "payment", "settlement") for partition in range(count)}

    def test_queries_each_group_once_per_poll_independent_of_partition_count(self):
        for count in (1, 3, 12):
            ends = self.targets(count)
            compose = object()
            with self.subTest(partitions=count), \
                    patch.object(smoke_financial_flow, "partitions", return_value=set(ends)) as partitions, \
                    patch.object(smoke_financial_flow, "bounds", return_value=ends) as bounds, \
                    patch.object(smoke_financial_flow, "group_offsets", return_value=ends) as offsets:
                smoke_financial_flow.wait_drained(compose)

                self.assertEqual(offsets.call_args_list, [call(compose, "commerce.v1"),
                                                         call(compose, "payment.v1")])
                partitions.assert_called_once_with(compose)
                bounds.assert_called_once_with(compose, set(ends), "latest")

    def test_waits_for_missing_records_and_accepts_newer_offsets_and_empty_partitions(self):
        topic = "commerce-settle.payment.v1"
        ends = {(topic, 0): 5, (topic, 1): 0, (topic, 2): 5}
        with patch.object(smoke_financial_flow, "partitions", return_value=set(ends)), \
                patch.object(smoke_financial_flow, "bounds", return_value=ends) as bounds, \
                patch.object(smoke_financial_flow, "group_offsets", side_effect=[
                    {(topic, 0): 5}, {(topic, 0): 6, (topic, 2): 7},
                ]) as offsets, patch("test_admission.time.sleep"):
            smoke_financial_flow.wait_drained(object(), groups=("commerce.v1",))

            self.assertEqual(offsets.call_count, 2)
            bounds.assert_called_once()

    def test_times_out_when_a_nonempty_partition_has_no_committed_offset(self):
        ends = self.targets()
        with patch.object(smoke_financial_flow, "partitions", return_value=set(ends)), \
                patch.object(smoke_financial_flow, "bounds", return_value=ends), \
                patch.object(smoke_financial_flow, "group_offsets", return_value={}) as offsets, \
                patch("test_admission.time.monotonic", side_effect=[0, 0, 2]), \
                patch("test_admission.time.sleep"), \
                self.assertRaisesRegex(AssertionError, "commerce.v1 did not consume"):
            smoke_financial_flow.wait_drained(object(), groups=("commerce.v1",), seconds=1)
        offsets.assert_called_once()

    def test_propagates_offset_query_failure(self):
        ends = self.targets()
        with patch.object(smoke_financial_flow, "partitions", return_value=set(ends)), \
                patch.object(smoke_financial_flow, "bounds", return_value=ends), \
                patch.object(smoke_financial_flow, "group_offsets", side_effect=RuntimeError("Kafka unavailable")), \
                self.assertRaisesRegex(RuntimeError, "Kafka unavailable"):
            smoke_financial_flow.wait_drained(object())


class RestartHelpersTest(unittest.TestCase):
    def setUp(self):
        self.before = {
            "seller_accounts": {"seller": {"id": "seller", "payout_account_ref": "original"}},
            "buyer_accounts": {"buyer": {"id": "buyer", "display_name": "original"}},
            "buyer_coupons": {"coupon": {"id": "coupon", "discount_amount": 3000}},
        }

    def test_refuses_non_ci_or_another_project_before_touching_the_stack(self):
        for environment in [{"CI": "false", "COMPOSE_PROJECT_NAME": "commerce-settle-ci"},
                            {"CI": "true", "COMPOSE_PROJECT_NAME": "commerce-settle"}]:
            with self.subTest(environment=environment), patch.dict("os.environ", environment), \
                    patch.object(smoke_restart, "read_business_data",
                                 side_effect=AssertionError("CI guard not checked")) as read, \
                    self.assertRaises(RuntimeError):
                smoke_restart.run()
            read.assert_not_called()

    def test_refuses_empty_business_data_before_stopping_the_stack(self):
        for table in self.before:
            before = deepcopy(self.before)
            before[table] = {}
            with self.subTest(table=table), patch.dict("os.environ", {
                "CI": "true", "COMPOSE_PROJECT_NAME": "commerce-settle-ci",
            }), patch.object(smoke_restart, "read_business_data", return_value=before), \
                    patch.object(smoke_restart.subprocess, "run") as cli, \
                    patch.object(smoke_restart.sync_redis_run_id, "main") as approve, \
                    self.assertRaises(AssertionError):
                smoke_restart.run()
            cli.assert_not_called()
            approve.assert_not_called()

    def test_preserves_existing_records_and_allows_additional_records(self):
        for additional in (False, True):
            actual = deepcopy(self.before)
            if additional:
                for records in actual.values():
                    records["new"] = {"id": "new"}
            with self.subTest(additional=additional), patch.object(
                smoke_restart, "read_business_data", return_value=actual,
            ):
                smoke_restart.verify_preserved(self.before)

    def test_rejects_missing_ids_and_changed_business_values(self):
        for table, field, value in [
            ("seller_accounts", "payout_account_ref", "changed"),
            ("buyer_accounts", "display_name", "changed"),
            ("buyer_coupons", "discount_amount", 4000),
        ]:
            for missing in (False, True):
                actual = deepcopy(self.before)
                record_id = next(iter(actual[table]))
                if missing:
                    del actual[table][record_id]
                else:
                    actual[table][record_id][field] = value
                with self.subTest(table=table, missing=missing), patch.object(
                    smoke_restart, "read_business_data", return_value=actual,
                ), self.assertRaisesRegex(AssertionError, table):
                    smoke_restart.verify_preserved(self.before)


class CiGateTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (Path(__file__).resolve().parents[1] / ".github/workflows/ci.yml").read_text()
        gate = self.workflow.split("\n  build-and-smoke:\n", 1)[1]
        self.script = textwrap.dedent(gate.split("        run: |\n", 1)[1])
        self.results = {name: {"result": "success"} for name in
                        ("checks", "package", "tests", "observability", "integration", "demo-ui")}

    def result(self, jobs):
        return subprocess.run(["bash", "-e", "-c", self.script],
                              env={**os.environ, "JOB_RESULTS": json.dumps(jobs)},
                              capture_output=True, text=True, timeout=10).returncode

    def test_requires_every_job_and_every_module(self):
        gate = self.workflow.split("\n  build-and-smoke:\n", 1)[1]
        self.assertIn("    if: always()\n", gate)
        names = re.search(r"^    needs: \[([^\]]+)\]$", gate, re.MULTILINE).group(1)
        self.assertEqual({name.strip() for name in names.split(",")}, set(self.results))
        tests = self.workflow.split("\n  tests:\n", 1)[1].split("\n  observability:\n", 1)[0]
        self.assertEqual(set(re.findall(r":([a-z-]+):test\b", tests)),
                         {"commerce", "payment", "settlement", "finance-mock", "gateway", "internal-auth", "observability", "demo-control"})
        self.assertIn("scenario: [smoke, redis, retention, admission, financial-flow, reconciliation, demo-ui]", self.workflow)
        self.assertRegex(self.workflow, r"Verify demo UI operator ports on startup and restart\n"
                         r"\s+if: matrix.scenario == 'demo-ui'")
        self.assertIn('python3 -B scripts/smoke.py --demo-ui', self.workflow)
        self.assertRegex(self.workflow, r"if: matrix.scenario == 'financial-flow'\n"
                         r"\s+run: python3 -B scripts/smoke_financial_flow.py")
        self.assertRegex(self.workflow, r"Verify source-backed notification retention\n"
                         r"\s+if: matrix.scenario == 'retention'")
        self.assertIn("run: python3 -B scripts/smoke_restart.py", self.workflow)
        self.assertEqual(self.result(self.results), 0)

    def test_rejects_failure_cancellation_skip_and_no_results(self):
        for name in self.results:
            for status in ("failure", "cancelled", "skipped"):
                with self.subTest(job=name, status=status):
                    jobs = {**self.results, name: {"result": status}}
                    self.assertNotEqual(self.result(jobs), 0)
        self.assertNotEqual(self.result({}), 0)


if __name__ == "__main__":
    unittest.main()
