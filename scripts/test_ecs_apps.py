"""Exercise real Compose rendering and synthetic AWS deployment/rollback boundaries."""

import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import deploy_ecs_apps as deployment
import render_ecs_apps as renderer
from render_ecs_data import apply_capacity, capacity_profile, compose_services, render as render_data


REGION = "ap-northeast-2"
ACCOUNT = "123456789012"
ROLE = f"arn:aws:iam::{ACCOUNT}:role/test-execution"
PREFIX = f"arn:aws:ssm:{REGION}:{ACCOUNT}:parameter/commerce-settle/app/"
REGISTRY = f"{ACCOUNT}.dkr.ecr.{REGION}.amazonaws.com/commerce-settle"
IMAGES = {name: REGISTRY + "@sha256:" + str(index + 1) * 64
          for index, name in enumerate(renderer.MODULES)}
COMMIT = "a" * 40


def write_images(root):
    for name, image in IMAGES.items():
        (root / (name + ".json")).write_text(json.dumps(
            {"module": name, "image": image, "release": "v0.1.0", "commit": COMMIT}
        ))


def task(name):
    if name == "postgres":
        return render_data(name, compose_services_cached(), REGION, ROLE, PREFIX, "10.0.2.10")
    return renderer.render(name, compose_services_cached(), IMAGES[renderer.module_for(name)],
                           REGION, ROLE, PREFIX, "10.0.1.10", "10.0.2.10")


_services = None


def compose_services_cached():
    global _services
    if _services is None:
        _services = compose_services()
    return _services


class FakeAws:
    def __init__(self, fail=None):
        self.services, self.tasks, self.calls = {}, {}, []
        self.fail = fail
        self.failed = False
        for name in deployment.ORDER:
            arn = f"arn:aws:ecs:{REGION}:{ACCOUNT}:task-definition/{deployment.service_name(name)}:1"
            self.tasks[arn] = task(name)
            self.tasks[arn]["containerDefinitions"][0]["environment"].append(
                {"name": "OPERATOR_SETTING", "value": "preserve-me"}
            )
            self.services[name] = {
                "serviceName": deployment.service_name(name),
                "clusterArn": f"arn:aws:ecs:{REGION}:{ACCOUNT}:cluster/commerce-cluster",
                "status": "ACTIVE", "desiredCount": 1, "runningCount": 1, "pendingCount": 0,
                "deploymentController": {"type": "ECS"}, "schedulingStrategy": "REPLICA",
                "deploymentConfiguration": {
                    "minimumHealthyPercent": 0, "maximumPercent": 100,
                    "deploymentCircuitBreaker": {"enable": True, "rollback": True},
                },
            }
            self.stable(name, arn)
        self.services["postgres"]["deploymentConfiguration"].pop("deploymentCircuitBreaker")
        database = self.tasks[self.services["postgres"]["taskDefinition"]]["containerDefinitions"][0]
        database["memory"] = 512
        database["command"] = ["postgres", "-c", "timezone=Asia/Seoul", "-c", "max_connections=100"]

    def stable(self, name, arn):
        self.services[name].update(taskDefinition=arn, deployments=[
            {"status": "PRIMARY", "taskDefinition": arn, "rolloutState": "COMPLETED"}
        ])

    def __call__(self, service, operation, *arguments, payload=None, timeout=None):
        self.calls.append((operation, arguments, copy.deepcopy(payload)))
        if (service, operation) == ("ecr", "get-lifecycle-policy"):
            return {}
        if service == "sts":
            return {"Account": ACCOUNT}
        if operation == "describe-services":
            names = arguments[arguments.index("--services") + 1:]
            return {"services": [copy.deepcopy(self.services[name.removeprefix("commerce-settle-")])
                                 for name in names]}
        if operation == "describe-task-definition":
            definition = copy.deepcopy(self.tasks[arguments[1]])
            definition.update(taskDefinitionArn=arguments[1], revision=1)
            definition.setdefault("status", "ACTIVE")
            return {"taskDefinition": definition, "tags": [
                {"key": "owner", "value": "apps"}, {"key": "AWS:managed", "value": "system"},
            ]}
        if operation == "register-task-definition":
            arn = f"arn:aws:ecs:{REGION}:{ACCOUNT}:task-definition/{payload['family']}:2"
            self.tasks[arn] = copy.deepcopy(payload)
            return {"taskDefinition": {"taskDefinitionArn": arn}}
        if operation == "update-service":
            name = arguments[arguments.index("--service") + 1].removeprefix("commerce-settle-")
            arn = arguments[arguments.index("--task-definition") + 1]
            self.stable(name, arn)
            if (self.fail in ("ambiguous-update", "ambiguous-postgres")
                    and name == ("postgres" if self.fail == "ambiguous-postgres" else "payment-worker")
                    and not self.failed):
                self.failed = True
                raise RuntimeError("request failed after AWS applied it")
            return {}
        if operation == "wait":
            name = arguments[-1].removeprefix("commerce-settle-")
            if self.fail in ("circuit-breaker", "foreign-revision") and not self.failed:
                self.failed = True
                revision = "1" if self.fail == "circuit-breaker" else "99"
                current = self.services[name]["taskDefinition"]
                self.stable(name, current.rsplit(":", 1)[0] + ":" + revision)
            return {}
        raise AssertionError((service, operation))


class WaitTest(unittest.TestCase):
    TASK = f"arn:aws:ecs:{REGION}:{ACCOUNT}:task-definition/commerce-settle-finance-mock:2"

    def state(self, rollout="COMPLETED", task=None):
        arn = task or self.TASK
        return {"serviceName": "commerce-settle-finance-mock", "status": "ACTIVE",
                "desiredCount": 1, "runningCount": 1, "pendingCount": 0,
                "taskDefinition": arn, "deployments": [
                    {"status": "PRIMARY", "taskDefinition": arn, "rolloutState": rollout},
                ]}

    def observe(self, states, *, waiter_seconds=90, expected=None, describe_seconds=0):
        elapsed, polls = [0], []

        def aws(service, operation, *args, timeout=None):
            self.assertEqual(service, "ecs")
            if operation == "wait":
                self.assertEqual(timeout, 600)
                elapsed[0] += waiter_seconds
                return {}
            self.assertEqual(operation, "describe-services")
            self.assertEqual(timeout, min(60, 600 - elapsed[0]))
            elapsed[0] += describe_seconds
            current = states[min(len(polls), len(states) - 1)]
            polls.append(copy.deepcopy(current))
            return {"services": [copy.deepcopy(current)]}

        def sleep(seconds):
            elapsed[0] += seconds

        error = None
        with patch.object(deployment.time, "monotonic", side_effect=lambda: elapsed[0]), \
                patch.object(deployment.time, "sleep", side_effect=sleep):
            try:
                deployment.wait_for(aws, "commerce-cluster", "finance-mock", expected or self.TASK)
            except RuntimeError as caught:
                error = caught
        return error, elapsed[0], len(polls)

    def test_waiter_success_waits_for_expected_rollout_completion(self):
        error, elapsed, polls = self.observe([self.state("IN_PROGRESS"), self.state()])
        self.assertIsNone(error)
        self.assertEqual((elapsed, polls), (105, 2))

    def test_rollback_waits_for_previous_revision_completion(self):
        previous = self.TASK.rsplit(":", 1)[0] + ":1"
        error, _, polls = self.observe([
            self.state("IN_PROGRESS", previous), self.state(task=previous),
        ], expected=previous)
        self.assertIsNone(error)
        self.assertEqual(polls, 2)

    def test_other_revision_and_failed_or_inactive_service_are_rejected(self):
        inactive = self.state()
        inactive["status"] = "INACTIVE"
        for state in (self.state(task=self.TASK + "9"), self.state("FAILED"), inactive):
            with self.subTest(state=state):
                error, elapsed, polls = self.observe([state])
                self.assertIsNotNone(error)
                self.assertEqual((elapsed, polls), (90, 1))

    def test_waiter_and_rollout_share_the_existing_ten_minute_budget(self):
        error, elapsed, polls = self.observe([self.state("IN_PROGRESS")], waiter_seconds=599)
        self.assertIn("timed out", str(error))
        self.assertEqual((elapsed, polls), (600, 2))

    def test_running_and_pending_counts_must_also_settle(self):
        pending = self.state("IN_PROGRESS")
        pending.update(runningCount=0, pendingCount=1)
        error, _, polls = self.observe([pending, self.state()])
        self.assertIsNone(error)
        self.assertEqual(polls, 2)

    def test_late_waiter_cannot_approve_an_already_completed_service(self):
        error, elapsed, polls = self.observe([self.state()], waiter_seconds=601)
        self.assertIn("timed out", str(error))
        self.assertEqual((elapsed, polls), (601, 0))

    def test_completion_response_after_remaining_budget_is_not_approved(self):
        error, elapsed, polls = self.observe([self.state()], waiter_seconds=599, describe_seconds=2)
        self.assertIn("timed out", str(error))
        self.assertEqual((elapsed, polls), (601, 1))


class RenderTest(unittest.TestCase):
    def test_real_compose_preserves_roles_health_and_secret_references(self):
        ports = []
        for name in renderer.SERVICES:
            definition = task(name)
            container = definition["containerDefinitions"][0]
            environment = {item["name"]: item["value"] for item in container["environment"]}
            secrets = {item["name"]: item["valueFrom"] for item in container["secrets"]}
            self.assertEqual(definition["networkMode"], "bridge")
            self.assertEqual(definition["runtimePlatform"]["cpuArchitecture"], "ARM64")
            self.assertEqual(container["memory"], capacity_profile()["services"].get(name, {}).get(
                "memory", 2048 if name == "commerce" else 512))
            self.assertNotIn("memory", definition)
            self.assertEqual(container["healthCheck"]["command"],
                             ["CMD", "curl", "--fail", "--silent",
                              "http://localhost:9091/actuator/health"])
            self.assertEqual(environment["SPRING_DATA_REDIS_HOST"], "10.0.2.10")
            self.assertEqual(environment["COMMERCE_URL"], "http://10.0.1.10:8081")
            self.assertNotIn("DB_PASSWORD", environment)
            self.assertNotIn("$" + "{", json.dumps(definition))
            self.assertTrue(all(value.startswith(PREFIX) for value in secrets.values()))
            ports += [item["hostPort"] for item in container["portMappings"]]
            if name.startswith("payment-"):
                self.assertEqual(environment["PAYMENT_ROLE"], name.removeprefix("payment-"))
                self.assertEqual(secrets["DB_PASSWORD"], PREFIX + "PAYMENT_DB_PASSWORD")
            if name == "payment-api":
                self.assertEqual(secrets["SPRING_APPLICATION_JSON"],
                                 PREFIX + "payment-api/SPRING_APPLICATION_JSON")
            if name == "commerce":
                self.assertEqual(environment["APP_ORDER_WORKFLOW_ENABLED"], "false")
                self.assertEqual(environment["APP_DEMO_AUTO_SHIPMENT_ENABLED"], "false")
            if name == "finance-mock":
                self.assertEqual(environment["APP_DEV_FIXTURES_ENABLED"], "true")
                self.assertEqual(environment["SPRING_THREADS_VIRTUAL_ENABLED"], "true")
        self.assertEqual(len(ports), len(set(ports)))

    def test_refuses_wrong_account_region_public_or_same_hosts_and_mutable_image(self):
        valid = ["gateway", compose_services_cached(), IMAGES["gateway"], REGION, ROLE,
                 PREFIX, "10.0.1.10", "10.0.2.10"]
        for index, value in [(2, REGISTRY + ":latest"), (3, "us-east-1"),
                             (4, ROLE.replace(ACCOUNT, "000000000000")),
                             (5, PREFIX.rstrip("/")), (6, "8.8.8.8"), (7, "10.0.1.10")]:
            arguments = valid.copy()
            arguments[index] = value
            with self.subTest(index=index), self.assertRaises(ValueError):
                renderer.render(*arguments)

    def test_cli_renders_without_env_file_or_aws(self):
        result = subprocess.run(
            [sys.executable, "-B", str(Path(renderer.__file__)), "gateway",
             "--image", IMAGES["gateway"], "--region", REGION, "--execution-role", ROLE,
             "--parameter-prefix", PREFIX, "--app-private-ip", "10.0.1.10",
             "--data-private-ip", "10.0.2.10"],
            capture_output=True, text=True, check=True,
        )
        self.assertEqual(json.loads(result.stdout)["family"], "commerce-settle-gateway")


class DeployTest(unittest.TestCase):
    def test_already_applied_database_capacity_is_not_restarted_on_app_release(self):
        aws = FakeAws()
        previous = aws.services["postgres"]["taskDefinition"]
        apply_capacity(aws.tasks[previous]["containerDefinitions"][0], "postgres")
        result = self.run_deploy(aws)
        self.assertEqual(result["unchanged"], ["postgres"])
        self.assertEqual(result["registered"]["postgres"], previous)
        self.assertEqual(aws.services["postgres"]["taskDefinition"], previous)
        self.assertNotIn("postgres", result["attempted"])
        self.assertFalse(any(operation == "register-task-definition" and payload["family"] == "commerce-settle-postgres"
                             for operation, _, payload in aws.calls))

    def test_capacity_replaces_stale_settings_preserving_postgres_data_and_other_options(self):
        aws = FakeAws()
        previous = aws.tasks[aws.services["postgres"]["taskDefinition"]]
        database = previous["containerDefinitions"][0]
        database["memory"] = 512
        database["command"] = ["postgres", "-c", "timezone=Asia/Seoul", "-c", "max_connections=100"]
        worker = aws.tasks[aws.services["payment-worker"]["taskDefinition"]]["containerDefinitions"][0]
        worker["environment"] = [row for row in worker["environment"] if row["name"] != "PAYMENT_APPROVAL_CONCURRENCY"]
        worker["environment"].append({"name": "PAYMENT_APPROVAL_CONCURRENCY", "value": "6"})
        _, tasks = deployment.prepare(aws, "commerce-cluster", IMAGES, REGION)
        updated = tasks["postgres"]["containerDefinitions"][0]
        self.assertEqual(updated["memory"], 4096)
        self.assertEqual(updated["command"], ["postgres", "-c", "timezone=Asia/Seoul", "-c", "max_connections=256"])
        for key in ("image", "mountPoints", "secrets"):
            self.assertEqual(updated[key], database[key])
        self.assertEqual(database["memory"], 512)
        env = {row["name"]: row["value"] for row in tasks["payment-worker"]["containerDefinitions"][0]["environment"]}
        self.assertEqual(env["PAYMENT_APPROVAL_CONCURRENCY"], "4096")
        self.assertEqual(env["OPERATOR_SETTING"], "preserve-me")
        once = copy.deepcopy(updated)
        apply_capacity(updated, "postgres")
        self.assertEqual(updated, once)

    def test_capacity_profile_is_bound_to_rollback_baseline_before_writes(self):
        aws = FakeAws()
        baseline = self.baseline(aws)
        changed = copy.deepcopy(capacity_profile())
        changed["services"]["payment-worker"]["environment"]["PAYMENT_APPROVAL_CONCURRENCY"] = "2048"
        with patch.object(deployment, "capacity_profile", return_value=changed), self.assertRaisesRegex(RuntimeError, "baseline differs"):
            deployment.deploy(aws, "commerce-cluster", IMAGES, REGION, lambda: None, lambda _: None, baseline)
        self.assertFalse(any(call[0] in ("register-task-definition", "update-service") for call in aws.calls))

    def test_capacity_has_twofold_maximum_pg_delay_budget_without_overbooking_db_connections(self):
        profile = capacity_profile()
        services = profile["services"]
        worker = services["payment-worker"]["environment"]
        fresh = int(worker["PAYMENT_APPROVAL_CONCURRENCY"]) - int(worker["PAYMENT_APPROVAL_RETRY_CONCURRENCY"])
        for delay, success in ((0.150, 0.995), (0.700, 0.97), (1.500, 0.92)):
            self.assertGreaterEqual(fresh, 1000 / success * delay * 2)
        pools = sum(int(value) for settings in services.values() for key, value in settings.get("environment", {}).items() if key.endswith("DB_POOL_SIZE"))
        self.assertLessEqual(pools + 32, int(services["postgres"]["postgresSettings"]["max_connections"]))
        self.assertLessEqual(sum(settings.get("memory", 512) for name, settings in services.items() if name != "postgres") + 512 + 512 + 128, 7168)

    def test_capacity_never_replaces_a_secret_reference(self):
        aws = FakeAws()
        container = aws.tasks[aws.services["payment-worker"]["taskDefinition"]]["containerDefinitions"][0]
        container["secrets"].append({"name": "PAYMENT_DB_POOL_SIZE", "valueFrom": PREFIX + "existing"})
        with self.assertRaisesRegex(ValueError, "conflicts with a secret"):
            self.run_deploy(aws)
        self.assertFalse(any(call[0] in ("register-task-definition", "update-service") for call in aws.calls))

    def test_demo_shipment_switch_changes_only_commerce_and_preserves_other_settings(self):
        aws = FakeAws()
        for enabled in (True, False, None):
            with self.subTest(enabled=enabled):
                _, tasks = deployment.prepare(aws, "commerce-cluster", IMAGES, REGION, enabled)
                for name, definition in tasks.items():
                    container = definition["containerDefinitions"][0]
                    environment = {row["name"]: row["value"] for row in container["environment"]}
                    self.assertEqual(environment["OPERATOR_SETTING"], "preserve-me")
                    if name == "commerce":
                        self.assertEqual(environment["APP_DEMO_AUTO_SHIPMENT_ENABLED"],
                                         "true" if enabled else "false")
                        self.assertEqual(environment["APP_ORDER_WORKFLOW_ENABLED"],
                                         "true" if enabled else "false")
                    else:
                        self.assertNotIn("APP_DEMO_AUTO_SHIPMENT_ENABLED", environment)

    def test_changed_demo_shipment_intent_rejects_baseline_before_writes(self):
        aws = FakeAws()
        baseline = deployment.rollback_baseline(aws.services, "commerce-cluster", IMAGES, REGION, True)
        with self.assertRaisesRegex(RuntimeError, "baseline differs"):
            deployment.deploy(aws, "commerce-cluster", IMAGES, REGION, lambda: None,
                              lambda _: None, baseline, False)
        self.assertFalse(any(call[0] in ("register-task-definition", "update-service")
                             for call in aws.calls))

    def baseline(self, aws):
        return deployment.rollback_baseline(aws.services, "commerce-cluster", IMAGES, REGION)

    def run_deploy(self, aws, verify=lambda: None):
        self.records = []
        return deployment.deploy(aws, "commerce-cluster", IMAGES, REGION, verify,
                                 lambda value: self.records.append(copy.deepcopy(value)),
                                 self.baseline(aws))

    def test_complete_release_preserves_runtime_settings_and_uses_same_payment_image(self):
        aws = FakeAws()
        result = self.run_deploy(aws)
        self.assertEqual(result["status"], "success")
        self.assertEqual(result["attempted"], list(deployment.ORDER))
        for name, arn in result["registered"].items():
            definition = aws.tasks[arn]
            self.assertNotIn("revision", definition)
            container = definition["containerDefinitions"][0]
            self.assertIn({"name": "OPERATOR_SETTING", "value": "preserve-me"},
                          container["environment"])
            self.assertEqual(container["image"], "postgres:18.3" if name == "postgres"
                             else IMAGES[renderer.module_for(name)])
            self.assertEqual(definition["tags"], [{"key": "owner", "value": "apps"}])

    def test_rejects_unhealthy_previous_service_before_any_write(self):
        aws = FakeAws()
        aws.services["settlement"]["runningCount"] = 0
        with self.assertRaises(RuntimeError):
            self.run_deploy(aws)
        self.assertFalse(any(call[0] in ("register-task-definition", "update-service")
                             for call in aws.calls))

    def test_running_service_with_inactive_rollback_definition_stops_before_any_write(self):
        aws = FakeAws()
        arn = aws.services["settlement"]["taskDefinition"]
        aws.tasks[arn]["status"] = "INACTIVE"
        with self.assertRaisesRegex(RuntimeError, "must be ACTIVE for rollback"):
            self.run_deploy(aws)
        self.assertFalse(any(call[0] in ("register-task-definition", "update-service")
                             for call in aws.calls))

    def test_post_apply_cli_failure_restores_every_attempted_service(self):
        aws = FakeAws("ambiguous-update")
        with self.assertRaises(RuntimeError):
            self.run_deploy(aws)
        record = self.records[-1]
        self.assertEqual(record["status"], "failed")
        self.assertEqual(record["rolled_back"], list(reversed(record["attempted"])))
        self.assertTrue(all(service["taskDefinition"].endswith(":1")
                            for service in aws.services.values()))

    def test_postgres_apply_failure_restores_its_original_definition(self):
        aws = FakeAws("ambiguous-postgres")
        with self.assertRaises(RuntimeError):
            self.run_deploy(aws)
        self.assertEqual(self.records[-1]["attempted"], ["postgres"])
        self.assertEqual(self.records[-1]["rolled_back"], ["postgres"])
        self.assertTrue(all(service["taskDefinition"].endswith(":1") for service in aws.services.values()))

    def test_waiter_success_after_circuit_breaker_rollback_is_not_deploy_success(self):
        with self.assertRaises(RuntimeError):
            self.run_deploy(FakeAws("circuit-breaker"))
        self.assertEqual(self.records[-1]["status"], "failed")

    def test_https_failure_rolls_back_all_six_services(self):
        def fail():
            raise RuntimeError("HTTPS unavailable")
        with self.assertRaises(RuntimeError):
            self.run_deploy(FakeAws(), fail)
        self.assertEqual(self.records[-1]["rolled_back"], list(reversed(deployment.ORDER)))

    def test_record_write_failure_does_not_interrupt_remaining_rollbacks(self):
        aws = FakeAws()
        records = []

        def save(value):
            records.append(copy.deepcopy(value))
            if len(value["attempted"]) >= 2:
                raise OSError("synthetic full disk")

        with self.assertRaises(OSError):
            deployment.deploy(aws, "commerce-cluster", IMAGES, REGION, lambda: None, save,
                              self.baseline(aws))
        self.assertEqual(records[-1]["rolled_back"], ["finance-mock", "postgres"])
        self.assertTrue(all(service["taskDefinition"].endswith(":1")
                            for service in aws.services.values()))

    def test_detected_foreign_revision_is_not_overwritten_during_rollback(self):
        aws = FakeAws("foreign-revision")
        with self.assertRaises(RuntimeError):
            self.run_deploy(aws)
        self.assertEqual(self.records[-1]["rollback_errors"], ["postgres"])
        self.assertTrue(aws.services["postgres"]["taskDefinition"].endswith(":99"))

    def test_changed_service_after_baseline_stops_before_registration(self):
        aws = FakeAws()
        baseline = self.baseline(aws)
        old = aws.services["commerce"]["taskDefinition"]
        changed = old.rsplit(":", 1)[0] + ":99"
        aws.tasks[changed] = copy.deepcopy(aws.tasks[old])
        aws.stable("commerce", changed)
        with self.assertRaisesRegex(RuntimeError, "saved rollback baseline differs"):
            deployment.deploy(aws, "commerce-cluster", IMAGES, REGION, lambda: None,
                              lambda record: None, baseline)
        self.assertFalse(any(call[0] in ("register-task-definition", "update-service")
                             for call in aws.calls))

    def test_prepare_cli_saves_only_references_without_writes_or_https(self):
        aws = FakeAws()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_images(root)
            record = root / "baseline.txt"
            arguments = [
                "deploy_ecs_apps.py", "--cluster", "commerce-cluster", "--region", REGION,
                "--images-directory", directory, "--release", "v0.1.0", "--commit", COMMIT,
                "--public-url", "https://example.com", "--record", str(record), "--prepare-only",
            ]
            with patch.object(sys, "argv", arguments), \
                    patch.object(deployment, "Aws", return_value=aws), \
                    patch.object(deployment, "verify_https", side_effect=AssertionError("not readonly")), \
                    patch.object(sys, "stdout", io.StringIO()):
                self.assertEqual(deployment.main(), 0)
            baseline = json.loads(record.read_text())
            self.assertEqual(baseline["previous"], {
                name: service["taskDefinition"] for name, service in aws.services.items()
            })
            self.assertNotIn("containerDefinitions", record.read_text())
            self.assertFalse(any(call[0] in ("register-task-definition", "update-service")
                                 for call in aws.calls))

    def test_changed_baseline_file_is_rejected_before_aws_access(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_images(root)
            baseline = root / "baseline.txt"
            baseline.write_text("{}")
            expected = hashlib.sha256(baseline.read_bytes()).hexdigest()
            baseline.write_text('{"modified":true}')
            arguments = [
                "deploy_ecs_apps.py", "--cluster", "commerce-cluster", "--region", REGION,
                "--images-directory", directory, "--release", "v0.1.0", "--commit", COMMIT,
                "--public-url", "https://example.com", "--record", str(root / "result.txt"),
                "--baseline", str(baseline), "--baseline-sha256", expected,
            ]
            with patch.object(sys, "argv", arguments), patch.object(deployment, "Aws") as aws, \
                    patch.object(sys, "stdout", io.StringIO()):
                self.assertEqual(deployment.main(), 1)
            aws.assert_not_called()

    def test_rejects_partial_duplicate_and_mixed_release_manifests(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_images(root)
            self.assertEqual(deployment.read_images(root, "v0.1.0", COMMIT, REGION), IMAGES)
            gateway = root / "gateway.json"
            original = gateway.read_text()
            gateway.unlink()
            with self.assertRaises(ValueError):
                deployment.read_images(root, "v0.1.0", COMMIT, REGION)
            gateway.write_text(original.replace("v0.1.0", "v0.2.0"))
            with self.assertRaises(ValueError):
                deployment.read_images(root, "v0.1.0", COMMIT, REGION)
            gateway.write_text(original)
            (root / "duplicate.json").write_text(original)
            with self.assertRaises(ValueError):
                deployment.read_images(root, "v0.1.0", COMMIT, REGION)

    def test_aws_adapter_uses_private_reopenable_file_and_never_exposes_failed_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            cli = Path(directory) / "aws"
            cli.write_text(f"#!{sys.executable}\n" + (
                "import json, os, pathlib, stat, sys\n"
                "path = pathlib.Path(sys.argv[sys.argv.index('--cli-input-json') + 1][7:])\n"
                "assert stat.S_IMODE(path.stat().st_mode) == 0o600\n"
                "first = json.loads(path.read_text())\n"
                "assert json.loads(path.read_text()) == first\n"
                "if os.environ.get('FAIL_CLI') == '1':\n"
                "    print('synthetic-secret', file=sys.stderr)\n"
                "    sys.exit(1)\n"
                "print(json.dumps({'arguments':sys.argv[1:], 'received':first, 'path':str(path)}))\n"
            ))
            cli.chmod(0o700)
            with patch.dict(os.environ, {"PATH": directory + os.pathsep + os.environ["PATH"],
                                         "FAIL_CLI": "0"}):
                response = deployment.Aws(REGION)(
                    "ecs", "register-task-definition", payload={"value": "synthetic-secret"}
                )
                self.assertNotIn("synthetic-secret", " ".join(response["arguments"]))
                self.assertEqual(response["received"], {"value": "synthetic-secret"})
                self.assertFalse(Path(response["path"]).exists())
            with patch.dict(os.environ, {"PATH": directory + os.pathsep + os.environ["PATH"],
                                         "FAIL_CLI": "1"}), \
                    self.assertRaisesRegex(RuntimeError, "^AWS ecs register-task-definition failed$"):
                deployment.Aws(REGION)("ecs", "register-task-definition", payload={})

    def test_aws_adapter_enforces_the_callers_remaining_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            cli = Path(directory) / "aws"
            cli.write_text(f"#!{sys.executable}\nimport time\ntime.sleep(1)\nprint('{{}}')\n")
            cli.chmod(0o700)
            with patch.dict(os.environ, {"PATH": directory + os.pathsep + os.environ["PATH"]}), \
                    self.assertRaisesRegex(RuntimeError, "^AWS ecs describe-services failed$"):
                deployment.Aws(REGION)("ecs", "describe-services", timeout=0.02)

    def test_public_url_and_backend_docs_checks(self):
        for value in ["http://example.com", "https://user:secret@example.com",
                      "https://example.com/path", "https://example.com?secret=value"]:
            with self.assertRaises(ValueError):
                deployment.public_origin(value)
        self.assertEqual(deployment.public_origin("https://example.com/"), "https://example.com")

        def response(url, timeout):
            result = io.BytesIO(b'{"openapi":"3.0.1","paths":{"/api/test":{}}}')
            result.geturl = lambda: url
            return result
        with patch.object(deployment.urllib.request, "urlopen", side_effect=response) as request:
            deployment.verify_https("https://example.com")
        self.assertEqual([call.args[0] for call in request.call_args_list], [
            "https://example.com/v3/api-docs/" + owner
            for owner in ("commerce", "payment", "settlement")
        ])


class CleanupTest(unittest.TestCase):
    OLD = "sha256:" + "a" * 64
    CHILD = "sha256:" + "b" * 64
    FOREIGN = "sha256:" + "c" * 64

    def setup_release(self):
        aws = FakeAws()
        record = deployment.deploy(aws, "commerce-cluster", IMAGES, REGION, lambda: None,
                                   lambda value: None,
                                   deployment.rollback_baseline(aws.services, "commerce-cluster", IMAGES, REGION))
        details = {image.split("@")[1]: {"imageDigest": image.split("@")[1],
                   "imageTags": [module + "-v2"], "imagePushedAt": 20}
                   for module, image in IMAGES.items()}
        details[self.OLD] = {"imageDigest": self.OLD, "imageTags": ["gateway-v1"], "imagePushedAt": 10}
        details[self.CHILD] = {"imageDigest": self.CHILD, "imagePushedAt": 10}
        details[self.FOREIGN] = {"imageDigest": self.FOREIGN, "imageTags": ["another-product"], "imagePushedAt": 10}
        manifests = {digest: {} for digest in details}
        self.deletions, self.cleanup_records = [], []
        self.live = [{"taskArn": name, "taskDefinitionArn": record["registered"][name],
                      "group": "service:" + deployment.service_name(name), "lastStatus": "RUNNING",
                      "containers": [{"name": name, "image": IMAGES[renderer.module_for(name)],
                                      "imageDigest": IMAGES[renderer.module_for(name)].split("@")[1],
                                      "lastStatus": "RUNNING", "healthStatus": "HEALTHY"}]}
                     for name in renderer.SERVICES]
        self.failure = None

        def api(service, operation, *args, payload=None, timeout=None):
            if service != "ecr" and operation not in ("list-tasks", "describe-tasks"):
                return aws(service, operation, *args, payload=payload, timeout=timeout)
            aws.calls.append((operation, args, payload))
            if operation == "get-lifecycle-policy":
                return {"lifecyclePolicyText": "existing"} if self.failure == "policy" else {}
            if operation == "list-tasks":
                return {"taskArns": [t["taskArn"] for t in self.live] if args[-1] == "RUNNING" else []}
            if operation == "describe-tasks":
                return {"tasks": copy.deepcopy(self.live)}
            if operation == "describe-images":
                return {"imageDetails": copy.deepcopy(list(details.values()))}
            if operation == "batch-get-image":
                return {"images": [{"imageId": row, "imageManifest": json.dumps(manifests[row["imageDigest"]])}
                                   for row in payload["imageIds"]]}
            if operation == "batch-delete-image":
                batch = [row["imageDigest"] for row in payload["imageIds"]]
                self.deletions += batch
                removed = batch[:-1] if self.failure == "partial" else batch
                for digest in removed:
                    del details[digest]
                return {"imageIds": [{"imageDigest": digest} for digest in removed],
                        "failures": [{"failureCode": "ImageReferencedByManifestList"}]
                        if self.failure == "partial" else []}
            raise AssertionError(operation)
        self.aws, self.api, self.record = aws, api, record
        self.details, self.manifests = details, manifests

    def cleanup(self):
        deployment.finish_cleanup(self.api, self.record,
                                  lambda value: self.cleanup_records.append(copy.deepcopy(value)))

    def test_success_keeps_five_deployed_images_and_foreign_tags(self):
        self.setup_release()
        self.cleanup()
        self.assertEqual(set(self.deletions), {self.OLD, self.CHILD})
        self.assertEqual(self.record["cleanup"]["status"], "success")
        self.assertEqual(len(self.details), 6)
        self.assertEqual(self.record["status"], "success")

    def test_manifest_children_and_reference_artifacts_of_current_or_foreign_images_survive(self):
        for parent in (IMAGES["gateway"].split("@")[1], self.FOREIGN):
            with self.subTest(parent=parent):
                self.setup_release()
                self.manifests[parent] = {"manifests": [{"digest": self.CHILD}]}
                self.manifests[self.OLD] = {"subject": {"digest": parent}}
                self.cleanup()
                self.assertEqual(self.deletions, [])

    def test_old_manifest_list_is_deleted_before_its_children(self):
        self.setup_release()
        self.manifests[self.OLD] = {"manifests": [{"digest": self.CHILD}]}
        self.cleanup()
        self.assertEqual(self.deletions, [self.OLD, self.CHILD])

    def test_image_published_after_snapshot_is_not_added_to_deletion_plan(self):
        self.setup_release()
        original = self.api
        future = "sha256:" + "d" * 64

        def api(service, operation, *args, **kwargs):
            response = original(service, operation, *args, **kwargs)
            if operation == "batch-delete-image":
                self.details[future] = {"imageDigest": future, "imageTags": ["gateway-v3"], "imagePushedAt": 30}
            return response
        self.api = api
        self.cleanup()
        self.assertNotIn(future, self.deletions)
        self.assertIn(future, self.details)

    def test_failed_deployment_and_incomplete_record_never_delete(self):
        for change in ({"status": "failed"}, {"registered": {}}):
            with self.subTest(change=change):
                self.setup_release()
                self.record.update(change)
                with self.assertRaises(ValueError):
                    self.cleanup()
                self.assertEqual(self.deletions, [])

    def test_drift_unknown_digest_draining_or_unhealthy_task_blocks_all_deletion(self):
        changes = [
            {"imageDigest": self.OLD}, {"healthStatus": "UNKNOWN"}, {"lastStatus": "PENDING"},
        ]
        for change in changes:
            with self.subTest(change=change):
                self.setup_release()
                self.live[0]["containers"][0].update(change)
                with self.assertRaises(RuntimeError):
                    self.cleanup()
                self.assertEqual(self.deletions, [])
        self.setup_release()
        self.live.append({**self.live[0], "taskArn": "draining", "lastStatus": "DEACTIVATING"})
        with self.assertRaises(RuntimeError):
            self.cleanup()
        self.assertEqual(self.deletions, [])

    def test_conflicting_lifecycle_and_missing_manifest_or_current_image_block_cleanup(self):
        for problem in ("policy", "missing-image", "missing-child"):
            with self.subTest(problem=problem):
                self.setup_release()
                if problem == "policy":
                    self.failure = "policy"
                elif problem == "missing-image":
                    del self.details[IMAGES["gateway"].split("@")[1]]
                else:
                    self.manifests[self.FOREIGN] = {"manifests": [{"digest": "sha256:" + "d" * 64}]}
                with self.assertRaises(RuntimeError):
                    self.cleanup()
                self.assertEqual(self.deletions, [])

    def test_partial_deletion_preserves_deployment_and_can_retry_without_rollback(self):
        self.setup_release()
        self.failure = "partial"
        before = copy.deepcopy(self.aws.services)
        with self.assertRaises(RuntimeError):
            self.cleanup()
        self.assertEqual(self.record["status"], "success")
        self.assertEqual(self.record["cleanup"]["status"], "failed")
        self.assertEqual(self.aws.services, before)
        self.failure = None
        self.cleanup()
        self.assertEqual(self.record["cleanup"]["status"], "success")
        self.assertEqual(self.aws.services, before)

    def test_batches_large_repository_at_aws_limit_and_checks_each_deletion(self):
        self.setup_release()
        for number in range(101):
            digest = "sha256:" + f"{number + 100:064x}"
            self.details[digest] = {"imageDigest": digest, "imageTags": [f"gateway-v0.{number}"], "imagePushedAt": 1}
            self.manifests[digest] = {}
        self.cleanup()
        self.assertEqual(len(self.deletions), 103)
        calls = [payload for operation, _, payload in self.aws.calls
                 if operation in ("batch-get-image", "batch-delete-image")]
        self.assertTrue(all(1 <= len(payload["imageIds"]) <= 100 for payload in calls))
        self.assertEqual(len(self.details), 6)

    def test_task_drift_after_planning_blocks_first_deletion(self):
        self.setup_release()
        original = self.api

        def api(service, operation, *args, **kwargs):
            response = original(service, operation, *args, **kwargs)
            if operation == "batch-get-image":
                self.live[0]["containers"][0]["imageDigest"] = self.OLD
            return response
        self.api = api
        with self.assertRaises(RuntimeError):
            self.cleanup()
        self.assertEqual(self.deletions, [])

    def test_real_cli_recognizes_only_missing_lifecycle_and_keeps_other_errors_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            cli = Path(directory) / "aws"
            cli.write_text(f"#!{sys.executable}\nimport sys\n"
                           "print('An error occurred (' + sys.argv[-1] + ')', file=sys.stderr)\n"
                           "sys.exit(254)\n")
            cli.chmod(0o700)
            with patch.dict(os.environ, {"PATH": directory + os.pathsep + os.environ["PATH"]}):
                self.assertEqual(deployment.Aws(REGION)("ecr", "get-lifecycle-policy",
                                                       "LifecyclePolicyNotFoundException"), {})
                with self.assertRaises(RuntimeError):
                    deployment.Aws(REGION)("ecr", "get-lifecycle-policy", "AccessDeniedException")

    def test_cleanup_cli_rechecks_target_and_never_deploys(self):
        self.setup_release()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_images(root)
            record = root / "deployment.txt"
            self.record.update(release="v0.1.0", commit=COMMIT)
            record.write_text(json.dumps(self.record))
            args = ["deploy_ecs_apps.py", "--cluster", "commerce-cluster", "--region", REGION,
                    "--images-directory", directory, "--release", "v0.1.0", "--commit", COMMIT,
                    "--public-url", "https://example.com", "--record", str(record),
                    "--cleanup-only", str(record)]
            with patch.object(sys, "argv", args), patch.object(deployment, "Aws", return_value=self.api), \
                    patch.object(deployment, "deploy", side_effect=AssertionError("redeployment")), \
                    patch.object(deployment, "verify_https", side_effect=AssertionError("network")), \
                    patch.object(sys, "stdout", io.StringIO()):
                self.assertEqual(deployment.main(), 0)
            self.assertEqual(json.loads(record.read_text())["cleanup"]["status"], "success")


if __name__ == "__main__":
    unittest.main()
