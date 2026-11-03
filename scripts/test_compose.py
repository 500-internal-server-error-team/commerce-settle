"""Verify opt-in environment settings with the real Compose renderer; no daemon needed."""

import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ComposeConfigurationTest(unittest.TestCase):
    def resolve(self, outbox_enabled):
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
                 "--env-file", str(settings), "-f", str(source), "config", "--format", "json"],
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


if __name__ == "__main__":
    unittest.main()
