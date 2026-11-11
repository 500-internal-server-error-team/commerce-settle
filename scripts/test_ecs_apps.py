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
from render_ecs_data import compose_services


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
        for name in renderer.SERVICES:
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

    def stable(self, name, arn):
        self.services[name].update(taskDefinition=arn, deployments=[
            {"status": "PRIMARY", "taskDefinition": arn, "rolloutState": "COMPLETED"}
        ])

    def __call__(self, service, operation, *arguments, payload=None):
        self.calls.append((operation, arguments, copy.deepcopy(payload)))
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
            if self.fail == "ambiguous-update" and name == "payment-worker" and not self.failed:
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
            self.assertEqual(container["image"], IMAGES[renderer.module_for(name)])
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
        self.assertEqual(records[-1]["rolled_back"], ["commerce", "finance-mock"])
        self.assertTrue(all(service["taskDefinition"].endswith(":1")
                            for service in aws.services.values()))

    def test_detected_foreign_revision_is_not_overwritten_during_rollback(self):
        aws = FakeAws("foreign-revision")
        with self.assertRaises(RuntimeError):
            self.run_deploy(aws)
        self.assertEqual(self.records[-1]["rollback_errors"], ["finance-mock"])
        self.assertTrue(aws.services["finance-mock"]["taskDefinition"].endswith(":99"))

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

    def test_aws_adapter_uses_stdin_and_never_exposes_failed_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            cli = Path(directory) / "aws"
            cli.write_text(f"#!{sys.executable}\n" + (
                "import json, os, sys\n"
                "if os.environ.get('FAIL_CLI') == '1':\n"
                "    print('synthetic-secret', file=sys.stderr)\n"
                "    sys.exit(1)\n"
                "print(json.dumps({'arguments':sys.argv[1:], 'received':json.load(sys.stdin)}))\n"
            ))
            cli.chmod(0o700)
            with patch.dict(os.environ, {"PATH": directory + os.pathsep + os.environ["PATH"],
                                         "FAIL_CLI": "0"}):
                response = deployment.Aws(REGION)(
                    "ecs", "register-task-definition", payload={"value": "synthetic-secret"}
                )
                self.assertNotIn("synthetic-secret", " ".join(response["arguments"]))
                self.assertEqual(response["received"], {"value": "synthetic-secret"})
            with patch.dict(os.environ, {"PATH": directory + os.pathsep + os.environ["PATH"],
                                         "FAIL_CLI": "1"}), \
                    self.assertRaisesRegex(RuntimeError, "^AWS ecs register-task-definition failed$"):
                deployment.Aws(REGION)("ecs", "register-task-definition", payload={})

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


if __name__ == "__main__":
    unittest.main()
