"""Verify opt-in environment settings with the real Compose renderer; no daemon needed."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import smoke


ROOT = Path(__file__).resolve().parents[1]


class ComposeConfigurationTest(unittest.TestCase):
    def test_demo_ui_smoke_rejects_existing_demo_before_running_checks(self):
        with patch.object(smoke, "compose", return_value=SimpleNamespace(stdout='{"name":"commerce-settle-demo"}')) as cli, \
                patch.object(smoke, "internal_json") as health, \
                self.assertRaisesRegex(AssertionError, "dedicated UI Compose project"):
            smoke.main(["--demo-ui"])
        cli.assert_called_once_with("config", "--format", "json")
        health.assert_not_called()

    def test_demo_ui_ports_require_explicit_mode_and_loopback_binding(self):
        ports = [{"host_ip": "127.0.0.1", "published": "18081", "target": 8080, "protocol": "tcp"}]
        smoke.check_backend_ports("commerce", ports, True)
        for service, candidate, opted_in in [
            ("commerce", ports, False),
            ("commerce", [{**ports[0], "host_ip": "0.0.0.0"}], True),
            ("commerce", [{**ports[0], "published": "9091"}], True),
            ("commerce", ports * 2, True),
            ("payment-worker", ports, True),
        ]:
            with self.subTest(service=service, ports=candidate, opted_in=opted_in), self.assertRaises(AssertionError):
                smoke.check_backend_ports(service, candidate, opted_in)

    def resolve(self, outbox_enabled, *overrides):
        source = ROOT / "compose.yml"
        variables = set(re.findall(r"\$\{([A-Z][A-Z0-9_]*)", source.read_text()))
        required = set(re.findall(r"\$\{([A-Z][A-Z0-9_]*):\?", source.read_text()))
        environment = {key: value for key, value in os.environ.items() if key not in variables}
        with tempfile.TemporaryDirectory(prefix="commerce-compose-test-") as directory:
            settings = Path(directory) / ".env"
            content = "".join(f"{key}=compose-test-placeholder\n" for key in sorted(required))
            if outbox_enabled is not None:
                content += f"APP_OUTBOX_ENABLED={outbox_enabled}\n"
            settings.write_text(content)
            result = subprocess.run(
                ["docker", "compose", "--project-directory", str(ROOT),
                 "--env-file", str(settings), "-f", str(source),
                 *[option for path in overrides for option in ("-f", str(ROOT / path))], "config", "--format", "json"],
                env=environment, text=True, capture_output=True, check=True, timeout=30,
            )
        return json.loads(result.stdout)["services"]

    def test_payment_outbox_is_enabled_only_by_explicit_opt_in(self):
        for setting, expected in [(None, "false"), ("false", "false"), ("true", "true")]:
            with self.subTest(setting=setting):
                services = self.resolve(setting)
                worker = services["payment-worker"]["environment"]
                self.assertEqual(worker["APP_OUTBOX_ENABLED"], expected)
                self.assertEqual(worker["PAYMENT_ROLE"], "worker")
                for name in ["payment-api", "commerce", "settlement"]:
                    self.assertNotEqual(
                        services[name]["environment"].get("APP_OUTBOX_ENABLED"), "true",
                        f"Payment opt-in must not enable {name}'s outbox",
                    )

    def test_financial_flow_override_enables_only_the_round_trip_services(self):
        base = self.resolve(None)
        services = self.resolve(None, "compose.financial-flow.yml")
        expected = {
            "commerce": ["APP_OUTBOX_ENABLED", "APP_KAFKA_PAYMENT_ACCEPTED_ENABLED",
                         "APP_PAYMENT_ACCEPTED_PROCESSOR_ENABLED", "APP_ORDER_RESULT_PROCESSOR_ENABLED"],
            "payment-worker": ["APP_OUTBOX_ENABLED", "APP_KAFKA_ORDER_PAYMENT_REQUESTED_ENABLED",
                               "APP_PAYMENT_REQUEST_PROCESSOR_ENABLED", "APP_PAYMENT_APPROVAL_ENABLED",
                               "APP_REFUND_ENABLED", "APP_KAFKA_SETTLEMENT_REPLIES_ENABLED"],
            "settlement": ["APP_OUTBOX_ENABLED", "APP_KAFKA_SETTLEMENT_EVENTS_ENABLED",
                           "SETTLEMENT_INBOX_PROCESSOR_ENABLED"],
        }
        values = {"PAYMENT_APPROVAL_GATEWAY": "http", "PAYMENT_REFUND_GATEWAY": "http",
                  "PAYMENT_FUNDING_ACCOUNT_REF": "fixture-payout-funding",
                  "PAYMENT_SCENARIO_VERSION": "fixture-pg-normal-v1"}
        expected["payment-worker"] += list(values)
        expected["finance-mock"] = ["APP_DEV_FIXTURES_ENABLED"]
        for name, service in services.items():
            with self.subTest(service=name):
                environment = service.get("environment", {})
                switched = sorted(key for key, value in environment.items()
                                  if base[name].get("environment", {}).get(key) != value)
                self.assertEqual(switched, sorted(key for key in expected.get(name, [])
                                                  if base[name].get("environment", {}).get(key) != "true"))
                for key in expected.get(name, []):
                    self.assertEqual(environment[key], values.get(key, "true"))
        # Payment API serves HTTP only; the worker owns every financial consumer and publisher.
        self.assertNotIn("APP_REFUND_ENABLED", services["payment-api"]["environment"])
        self.assertEqual(services["payment-worker"]["environment"]["PAYMENT_ROLE"], "worker")


if __name__ == "__main__":
    unittest.main()
