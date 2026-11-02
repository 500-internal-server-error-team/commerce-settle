"""Keep isolated admission verification independent of the caller and recoverable."""

import contextlib
import io
import json
import os
from pathlib import Path
from subprocess import CompletedProcess
import tempfile
import unittest
from unittest.mock import patch

import test_admission


class AdmissionRunnerTest(unittest.TestCase):
    def test_pins_real_db_scenario_and_removes_only_owned_runtime(self):
        with tempfile.TemporaryDirectory() as parent:
            owned = Path(parent) / "owned"
            owned.mkdir()
            unrelated = Path(parent) / "unrelated"
            unrelated.write_text("preserve")
            calls = []

            def run(command, **kwargs):
                if command[-1] == "300":
                    override = json.loads((owned / "override.json").read_text())
                    calls.append((command, kwargs["env"].copy(), override))
                else:
                    calls.append((command, kwargs.get("env"), None))
                output = "run_id:" + "a" * 40 + "\n" if any("redis-cli INFO server" in value for value in command) else ""
                return CompletedProcess(command, 0, output, "")

            with patch.object(test_admission.tempfile, "mkdtemp", return_value=str(owned)), patch.object(
                test_admission.subprocess, "run", side_effect=run
            ), patch.object(test_admission.subprocess, "check_output", return_value=""), patch.dict(
                os.environ, {"COMPOSE_PROFILES": "observability,tools", "APP_ORDER_STUB_ENABLED": "true", "ALERTMANAGER_PORT": "9093"}
            ), test_admission.stack():
                pass
            start = next(call for call in calls if call[2] is not None)
            environment = start[1]
            self.assertEqual(environment["COMPOSE_PROFILES"], "")
            self.assertEqual(environment["APP_ORDER_STUB_ENABLED"], "false")
            for name in ["GATEWAY_PORT", "ZIPKIN_PORT", "PROMETHEUS_PORT", "ALERTMANAGER_PORT"]:
                self.assertEqual(environment[name], "0")
            self.assertEqual(start[2]["services"]["commerce"]["environment"]["APP_ORDER_STUB_ENABLED"], "false")
            self.assertTrue(any("--rmi" in command and "local" in command for command, *_args in calls))
            self.assertFalse(owned.exists())
            self.assertEqual(unrelated.read_text(), "preserve")

    def test_reports_and_retains_settings_when_cleanup_fails(self):
        with tempfile.TemporaryDirectory() as parent:
            owned = Path(parent) / "owned"
            owned.mkdir()
            output = io.StringIO()

            def run(command, **_kwargs):
                output = "run_id:" + "a" * 40 + "\n" if any("redis-cli INFO server" in value for value in command) else ""
                return CompletedProcess(command, int("down" in command), output, "daemon unavailable")

            with patch.object(test_admission.tempfile, "mkdtemp", return_value=str(owned)), patch.object(
                test_admission.subprocess, "run", side_effect=run
            ), contextlib.redirect_stdout(output), self.assertRaisesRegex(RuntimeError, "daemon unavailable"):
                with test_admission.stack():
                    pass
            self.assertTrue((owned / ".env").is_file())
            self.assertIn(str(owned), output.getvalue())
            self.assertIn("cs-admission-", output.getvalue())

    def test_failed_initialization_leaves_no_unreferenced_settings(self):
        with tempfile.TemporaryDirectory() as parent:
            owned = Path(parent) / "owned"
            owned.mkdir()
            with patch.object(test_admission.tempfile, "mkdtemp", return_value=str(owned)), patch.object(
                test_admission, "initialize", side_effect=ValueError("invalid input")
            ), patch.object(test_admission.subprocess, "run") as run, self.assertRaisesRegex(ValueError, "invalid input"):
                with test_admission.stack():
                    pass
            run.assert_not_called()
            self.assertFalse(owned.exists())


if __name__ == "__main__":
    unittest.main()
