from contextlib import ExitStack
import io
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest
from unittest.mock import Mock, patch

import experiment as e
from test_performance import metadata


class ExperimentTest(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"RECONCILE_SSH": "", "LOAD_APP_SSH": ""})
        environment.start()
        self.addCleanup(environment.stop)
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.out = self.root / "experiment"
        self.conditions = self.root / "conditions.json"
        self.record = self.root / "deployment.json"
        self.conditions.write_text(json.dumps(metadata()["conditions"]))
        self.record.write_text(json.dumps({"region": "test"}))
        self.argv = ["--base-url", "http://localhost:8080", "--deployment-record", str(self.record),
                     "--conditions", str(self.conditions), "--prometheus", "http://localhost:9090",
                     "--output-dir", str(self.out)]

    def flow(self, *, capture_error=False, load_code=0):
        stack = ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(e, "preflight", return_value={"reachable": True}))
        stack.enter_context(patch.object(e, "observe_deployment", return_value=metadata()))
        stack.enter_context(patch.object(e.order_load, "now", return_value="2020-01-01T00:00:00+00:00"))
        self.commands = []
        def load(command, args, directory):
            self.commands.append(command)
            if "--resume" in command:
                parent = Path(command[command.index("--resume") + 1])
                target = parent / "recovery-test"
            else:
                target = directory / "load"
            target.mkdir()
            e.order_load.write_json(target / "summary.json", {
                "measurementWindow": {"start": 940, "end": 1000}, "injectionPassed": load_code == 0,
                "drainComplete": True, "confirmedOrders": 120})
            e.order_load.write_json(target / "performance-metadata.json", metadata())
            return load_code, None
        self.loader = stack.enter_context(patch.object(e, "run_load", side_effect=load))
        self.capture = stack.enter_context(patch.object(e.performance, "capture",
            side_effect=ValueError("no metrics") if capture_error else None,
            return_value={"business": {"payment.confirmed": {"dbWindowStart": 939, "dbWindowEnd": 999}}, "http": {}}))
        def reconcile(args, on_run, on_result):
            on_run({"id": "fixed-run", "cutoffAt": args.cutoff_at})
            on_result({"run": {"id": "fixed-run"}, "financeOwner": {"checks": {"defects": 0}}})
            return 0
        self.reconcile = stack.enter_context(patch.object(e.reconcile, "verify", side_effect=reconcile))

    def test_one_command_binds_capture_to_original_window_and_saves_scoped_results(self):
        self.flow()
        self.assertEqual(e.main(self.argv), 0)
        self.assertEqual(self.capture.call_args.args[1:3], (60, 1000))
        result = e.read(self.out / "result.json")
        self.assertTrue(result["completed"])
        self.assertEqual(result["verdicts"]["sustained200Tps"], "UNVERIFIED")
        self.assertEqual(result["verdicts"]["payout"], "NOT_TESTED")
        self.assertIn("reconciliation.json", result["evidence"])

    def test_capture_failure_still_reconciles_and_preserves_failed_result(self):
        self.flow(capture_error=True, load_code=1)
        self.assertEqual(e.main(self.argv), 1)
        self.reconcile.assert_called_once()
        result = e.read(self.out / "result.json")
        self.assertEqual(result["stages"], {"preflight": "PASS", "load": "FAIL", "capture": "FAIL", "reconciliation": "PASS"})
        self.assertFalse(result["completed"])

    def test_resume_drains_original_ledger_without_new_injection_and_keeps_cutoff(self):
        self.flow(load_code=1)
        self.assertEqual(e.main(self.argv), 1)
        original = (self.out / "result.json").read_bytes()
        cutoff = e.read(self.out / "execution.json")["cutoffAt"]
        self.assertEqual(e.main(["--resume", str(self.out)]), 1)
        command = self.commands[-1]
        self.assertIn("--resume", command)
        self.assertNotIn("--base-url", command)
        self.assertNotIn("--tps", command)
        self.assertEqual(self.reconcile.call_args.args[0].cutoff_at, cutoff)
        self.assertEqual((self.out / "result.json").read_bytes(), original)

    def test_unavailable_service_exits_before_load_with_target_guidance(self):
        with patch.object(e, "preflight", side_effect=RuntimeError("Payment (http://localhost:8082): HTTP 401")), \
                patch.object(e, "run_load") as load, patch("sys.stderr", new_callable=io.StringIO) as stderr:
            self.assertEqual(e.main(self.argv), 1)
        load.assert_not_called()
        self.assertIn("HTTP 401", stderr.getvalue())
        self.assertIn("manually", stderr.getvalue())
        self.assertEqual(e.read(self.out / "result.json")["failure"]["phase"], "preflight")

    def test_legacy_resume_ignores_new_environment_cohort_host(self):
        self.flow(load_code=1)
        self.assertEqual(e.main(self.argv), 1)
        path = self.out / "execution.json"
        saved = e.read(path)
        saved["arguments"].pop("cohort_data_ssh", None)
        e.order_load.write_json(path, saved)
        with patch.dict(os.environ, {"LOAD_COHORT_DATA_SSH": "other@unapproved.test"}):
            self.assertEqual(e.main(["--resume", str(self.out)]), 1)
        self.assertNotIn("--cohort-data-ssh", self.commands[-1])
        self.assertIsNone(self.loader.call_args.args[1].cohort_data_ssh)

    def test_new_resume_restores_original_cohort_host(self):
        self.flow(load_code=1)
        self.assertEqual(e.main(self.argv + ["--cohort-data-ssh", "owned@data.test"]), 1)
        with patch.dict(os.environ, {"LOAD_COHORT_DATA_SSH": "other@unapproved.test"}):
            self.assertEqual(e.main(["--resume", str(self.out)]), 1)
        command = self.commands[-1]
        self.assertEqual(command[command.index("--cohort-data-ssh") + 1], "owned@data.test")

    def test_invalid_credential_never_reaches_stderr_or_saved_failure(self):
        marker = "synthetic-credential-" + "A" * 43
        def preflight(args):
            return e.reconcile.request(args.settlement_url, "/check", marker + "\n")
        with patch.object(e, "preflight", side_effect=preflight), \
                patch.object(e, "run_load") as load, \
                patch("sys.stderr", new_callable=io.StringIO) as stderr:
            self.assertEqual(e.main(self.argv), 1)
        load.assert_not_called()
        self.assertNotIn(marker, stderr.getvalue())
        self.assertNotIn(marker, (self.out / "result.json").read_text())
        self.assertIn("credential format", stderr.getvalue())

    def test_resume_cannot_claim_connection_only_success(self):
        with patch("sys.stderr", new_callable=io.StringIO), self.assertRaises(SystemExit) as error:
            e.main(["--resume", str(self.out), "--check-only"])
        self.assertEqual(error.exception.code, 2)
        self.assertFalse(self.out.exists())

    def test_resume_keeps_unverified_comparison_and_original_baseline(self):
        self.flow()
        baseline = self.root / "before.json"
        baseline.write_text('{}')
        with patch.object(e.performance, "compare", return_value={"complete": False}) as compare:
            self.assertEqual(e.main(self.argv + ["--compare", str(baseline)]), 1)
            self.assertEqual(e.main(["--resume", str(self.out)]), 1)
        self.assertEqual(compare.call_count, 2)
        self.assertEqual(e.read(self.out / "execution.json")["comparison"]["path"], str(baseline.resolve()))

    def test_observation_failure_interrupts_owned_load_once(self):
        process = Mock()
        process.wait.side_effect = [subprocess.TimeoutExpired("load", 15), 130]
        with patch.object(e.subprocess, "Popen", return_value=process) as child, \
                patch.dict(os.environ, {"LOAD_COHORT_DATA_SSH": "other@unapproved.test"}), \
                patch.object(e, "monitor", side_effect=RuntimeError("critical")):
            self.assertEqual(e.run_load(["load"], Mock(), self.root)[0], 130)
        self.assertIsNone(child.call_args.kwargs["env"].get("LOAD_COHORT_DATA_SSH"))
        process.send_signal.assert_called_once_with(e.signal.SIGINT)

    def test_up_services_do_not_hide_missing_business_observations(self):
        up = [{"value": [1000, "1"]}] * 6
        with patch.object(e.performance, "query", side_effect=[up, [], []]), \
                self.assertRaisesRegex(RuntimeError, "business observations"):
            e.monitor("http://localhost:9090", 60)

    def test_healthy_business_sources_do_not_hide_disabled_financial_reconciliation(self):
        up = [{"value": [1000, "1"]}] * 6
        sources = [{"metric": {"operation": operation}, "value": [1000, "1000"]} for operation in e.OPERATIONS]
        with patch.object(e.time, "time", return_value=1000), \
                patch.object(e.performance, "query", side_effect=[up, [], sources, []]), \
                self.assertRaisesRegex(RuntimeError, "SETTLEMENT_RECONCILIATION_ENABLED"):
            e.monitor("http://localhost:9090", 60)

    def test_deployment_image_mismatch_is_rejected(self):
        record = {"status": "success", "cluster": "test", "commit": "a"*40,
                  "registered": {name: "arn:" + name for name in e.SERVICES},
                  "images": {name: "image@sha256:" + "a"*64 for name in e.performance.IMAGES}}
        aws = Mock(return_value={"taskDefinition": {"containerDefinitions": [{"name": "gateway", "image": "other"}]}})
        with patch.object(e.deployment, "describe", return_value={name: {} for name in e.SERVICES}), \
                patch.object(e.deployment, "require_stable"), self.assertRaisesRegex(ValueError, "image differs"):
            e.observe_deployment(record, metadata()["conditions"], 2, 0, aws)

    def test_existing_app_ssh_observes_running_release_without_local_aws_credentials(self):
        self.flow()
        with patch.object(e, "observe_host_deployment", return_value=metadata()) as observe, \
                patch.object(e.deployment, "Aws", side_effect=AssertionError("local AWS must not be required")):
            self.assertEqual(e.main(self.argv + ["--app-ssh", "app@host"]), 0)
        self.assertEqual(observe.call_args.args[-1], "app@host")

    def test_empty_reconcile_ssh_keeps_existing_load_app_ssh_without_aws(self):
        self.flow()
        with patch.dict(os.environ, {"RECONCILE_SSH": "", "LOAD_APP_SSH": "app@host"}), \
                patch.object(e, "observe_host_deployment", return_value=metadata()) as observe, \
                patch.object(e.deployment, "Aws", side_effect=AssertionError("existing SSH must be used")):
            self.assertEqual(e.main(self.argv), 0)
        self.assertEqual(observe.call_args.args[-1], "app@host")

    def test_host_observation_verifies_identity_image_health_and_excludes_secrets(self):
        record = {"status": "success", "cluster": "cluster", "commit": "a" * 40,
                  "registered": {name: "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/commerce-settle-" + name + ":7"
                                 for name in e.SERVICES},
                  "images": {name: "registry/" + name + "@sha256:" + "a" * 64 for name in e.performance.IMAGES}}
        rows = {}
        for name in e.SERVICES:
            rows[name] = {"Image": name, "State": {"Running": True, "Health": {"Status": "healthy"}},
                "HostConfig": {"Memory": 536870912, "CpuShares": 64},
                "Config": {"Env": ["DB_PASSWORD=must-stay-on-host", "PAYMENT_DB_POOL_SIZE=8"], "Labels": {
                    "com.amazonaws.ecs.cluster": "cluster", "com.amazonaws.ecs.task-definition-family": "commerce-settle-" + name,
                    "com.amazonaws.ecs.task-definition-version": "7", "com.amazonaws.ecs.task-arn":
                    "arn:aws:ecs:ap-northeast-2:123456789012:task/cluster/" + "b" * 32}}}
        images = dict(record["images"])
        duplicate = False
        def docker(command, **kwargs):
            self.assertEqual(command[:2], ["sudo", "docker"])
            command = command[1:]
            if command[1] == "ps":
                output = command[-1].rsplit("=", 1)[-1]
                if duplicate:
                    output += " " + output
            elif command[1] == "inspect":
                output = json.dumps([rows[command[-1]]])
            else:
                output = json.dumps([{"RepoDigests": [images[e.module_for(command[-1])]]}])
            return subprocess.CompletedProcess(command, 0, output)
        # Execute the exact function sent to SSH with a Docker response fixture.
        def ssh(command, **kwargs):
            namespace = {}
            output = io.StringIO()
            with patch.object(subprocess, "run", side_effect=docker), patch("sys.stdout", output):
                exec(kwargs["input"], namespace)
            self.assertNotIn("must-stay-on-host", output.getvalue())
            return subprocess.CompletedProcess(command, 0, output.getvalue())
        with patch.dict(os.environ, {"LOAD_REMOTE_DOCKER": "sudo docker"}), patch.object(e.subprocess, "run", side_effect=ssh):
            actual = e.observe_host_deployment(record, metadata()["conditions"], 2, 0, "app@host")
            self.assertEqual(actual["conditions"]["runtime"]["gateway"]["settings"], {"PAYMENT_DB_POOL_SIZE": "8"})
            self.assertEqual(actual["deploymentObservation"]["ecsServiceRollout"], "NOT_QUERIED")
            for field, value in (("com.amazonaws.ecs.cluster", "other"),
                                 ("com.amazonaws.ecs.task-definition-version", "8"),
                                 ("com.amazonaws.ecs.task-arn", "arn:aws:ecs:ap-northeast-2:999999999999:task/" + "b" * 32)):
                with self.subTest(field=field):
                    labels = rows["gateway"]["Config"]["Labels"]
                    before = labels[field]
                    labels[field] = value
                    with self.assertRaisesRegex(ValueError, "differs from deployment record"):
                        e.observe_host_deployment(record, metadata()["conditions"], 2, 0, "app@host")
                    labels[field] = before
            rows["gateway"]["State"]["Health"]["Status"] = "unhealthy"
            with self.assertRaisesRegex(ValueError, "differs from deployment record"):
                e.observe_host_deployment(record, metadata()["conditions"], 2, 0, "app@host")
            rows["gateway"]["State"]["Health"]["Status"] = "healthy"
            images["gateway"] = "registry/gateway@sha256:" + "f" * 64
            with self.assertRaisesRegex(ValueError, "differs from deployment record"):
                e.observe_host_deployment(record, metadata()["conditions"], 2, 0, "app@host")
            duplicate = True
            with self.assertRaisesRegex(RuntimeError, "gateway at container-count"):
                e.observe_host_deployment(record, metadata()["conditions"], 2, 0, "app@host")

    def test_pending_reconciliation_preserves_run_before_snapshot_failure(self):
        checkpoints = []
        args = e.argparse.Namespace(run_id=None, cutoff_at="2020-01-01T00:00:00+00:00",
                                   settlement_url="http://localhost", timeout=1)
        pending = {"id": "durable-original", "status": "WAITING", "snapshotAt": None}
        with patch.object(e.reconcile, "request", side_effect=[pending, {"items": []}]), \
                self.assertRaisesRegex(RuntimeError, "remains unverified"):
            e.reconcile.verify(args, on_run=checkpoints.append)
        self.assertEqual(checkpoints, [pending])

    def test_task_revision_provenance_does_not_change_comparable_conditions_or_leak_environment(self):
        def observe(revision):
            record = {"status": "success", "cluster": "test", "commit": "a"*40,
                      "registered": {name: "arn:" + name + revision for name in e.SERVICES},
                      "images": {name: "image@sha256:" + "a"*64 for name in e.performance.IMAGES}}
            def aws(service, operation, *args):
                if operation == "describe-task-definition":
                    name = next(name for name in e.SERVICES if record["registered"][name] == args[1])
                    return {"taskDefinition": {"containerDefinitions": [{"name": name,
                        "image": record["images"][e.module_for(name)], "memory": 512,
                        "environment": [{"name": "DB_PASSWORD", "value": "secret"},
                                        {"name": "PAYMENT_DB_POOL_SIZE", "value": "8"}]}]}}
                if operation == "list-tasks":
                    return {"taskArns": [args[-1].removeprefix("commerce-settle-")]}
                name = args[-1]
                return {"tasks": [{"taskDefinitionArn": record["registered"][name], "lastStatus": "RUNNING",
                                   "containers": [{"name": name, "imageDigest": "sha256:" + "a"*64}]}]}
            with patch.object(e.deployment, "describe", return_value={name: {} for name in e.SERVICES}), \
                    patch.object(e.deployment, "require_stable"):
                return e.observe_deployment(record, metadata()["conditions"], 2, 0, aws)
        before, after = observe("1"), observe("2")
        self.assertEqual(before["conditions"], after["conditions"])
        self.assertNotEqual(before["deploymentTasks"], after["deploymentTasks"])
        self.assertNotIn("secret", json.dumps(after))


class ReconciliationTransportTest(unittest.TestCase):
    def test_invalid_header_credentials_are_redacted_for_direct_and_transferred_ssh_requests(self):
        marker = "synthetic-credential-" + "A" * 43
        real_run = subprocess.run
        def ssh(command, **kwargs):
            result = real_run(shlex.split(command[-1]), **kwargs)
            self.assertNotIn(marker, result.stdout)
            self.assertNotIn(marker, result.stderr)
            return result
        for remote in (False, True):
            for suffix in ("\n", "\r\ninvalid", "\u2028"):
                with self.subTest(remote=remote, suffix=repr(suffix)), \
                        patch.dict(os.environ, {"RECONCILE_SSH": "test@host" if remote else ""}), \
                        patch.object(e.reconcile.subprocess, "run", side_effect=ssh), \
                        self.assertRaises(ValueError) as error:
                    e.reconcile.request("http://127.0.0.1:1", "/check", marker + suffix)
                self.assertNotIn(marker, str(error.exception))
                self.assertIn("credential format", str(error.exception))

    def test_authenticated_direct_and_ssh_http_preserve_cutoff_and_checkpoint(self):
        calls = []
        cutoff = "2020-01-01T00:00:00+00:00"
        run = {"id": "fixed", "status": "COMPLETED", "cutoffAt": cutoff, "snapshotAt": cutoff}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                self.do_GET()
            def do_GET(self):
                calls.append((self.command, self.path, self.headers.get("Authorization")))
                if self.path == "/unauthorized":
                    self.send_response(401)
                    self.end_headers()
                    return
                if self.path.endswith("results?size=100"):
                    value = {"items": []}
                elif "payment-reconciliation-checks" in self.path:
                    value = {**run, "items": [{"status": "MATCH", "difference": 0}],
                             "nextCursor": None, "checkedAt": cutoff, "currentChecks": {"pending": 0}}
                elif self.path == "/mock/reconciliation-integrity":
                    value = {"checks": {"defects": 0}}
                else:
                    value = run
                self.send_response(200)
                self.end_headers()
                self.wfile.write(json.dumps(value).encode())
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = "http://127.0.0.1:" + str(server.server_port)
        args = e.argparse.Namespace(settlement_url=base, payment_url=base, finance_url=base,
                                   cutoff_at=cutoff, run_id=None, timeout=3)
        real_run = subprocess.run
        def ssh(command, **kwargs):
            self.assertNotIn("secret-token", " ".join(command))
            remote = shlex.split(command[-1])
            return real_run(remote, **kwargs)
        try:
            with tempfile.TemporaryDirectory() as temp:
                docker = Path(temp) / "docker.py"
                senders = {"[operator:experiment]": {"current": "secret-token"}}
                environment = ["SPRING_APPLICATION_JSON=" + json.dumps({"app": {"internal-auth": {"senders": senders}}})]
                environment += [key + "=secret-token" for key in e.CREDENTIALS[1:]]
                docker.write_text("import json,sys\nprint('settlement' if sys.argv[1]=='ps' else " +
                                  repr(json.dumps([{"Config": {"Env": environment}}])) + ")\n")
                cases = [(False, False), (True, False), (True, True)]
                docker_command = shlex.join([sys.executable, str(docker)])
                for remote, automatic in cases:
                    env = {} if automatic else {key: "secret-token" for key in e.CREDENTIALS}
                    if remote:
                        env.update(RECONCILE_SSH="test@host", LOAD_REMOTE_DOCKER=docker_command)
                    checkpoints, results = [], []
                    with patch.dict(os.environ, env, clear=True), patch.object(e.reconcile.subprocess, "run", side_effect=ssh):
                        self.assertEqual(e.reconcile.verify(args, on_run=checkpoints.append, on_result=results.append), 0)
                        with self.assertRaises(e.HTTPError) as error:
                            e.reconcile.request(base, "/unauthorized", None if automatic else "secret-token",
                                                credential_name=e.CREDENTIALS[0])
                        self.assertEqual(error.exception.code, 401)
                        error.exception.close()
                    self.assertEqual(checkpoints[0]["id"], "fixed")
                    self.assertEqual(results[0]["paymentOwner"]["PAYMENT"]["counts"], {"MATCH": 1})
            self.assertTrue(all(token == "Bearer secret-token" for _, _, token in calls))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_automatic_host_credential_is_not_forwarded_to_another_host(self):
        with patch.dict(os.environ, {"RECONCILE_SSH": "app@host"}), \
                patch.object(e.reconcile.subprocess, "run") as ssh, \
                self.assertRaisesRegex(ValueError, "loopback"):
            e.reconcile.request("https://other.example", "/internal/settlement-reconciliations", None,
                                credential_name=e.CREDENTIALS[0])
        ssh.assert_not_called()

    def test_loopback_credentials_do_not_use_an_environment_proxy(self):
        intercepted = []
        class Proxy(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_GET(self):
                intercepted.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"{}")
        server = ThreadingHTTPServer(("127.0.0.1", 0), Proxy)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        proxy = "http://127.0.0.1:" + str(server.server_port)
        try:
            with patch.dict(os.environ, {"http_proxy": proxy, "HTTP_PROXY": proxy,
                    "https_proxy": proxy, "HTTPS_PROXY": proxy, "no_proxy": "", "NO_PROXY": ""}), \
                    self.assertRaises(e.URLError):
                e.reconcile.local_request("http://127.0.0.1:1", "/check", "synthetic-operator")
            self.assertEqual(intercepted, [])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
