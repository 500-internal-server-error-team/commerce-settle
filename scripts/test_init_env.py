import os
from pathlib import Path
import tempfile
import unittest

from init_env import initialize


class InitEnvTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        (self.directory / ".env.example").write_text(
            "GATEWAY_PORT=8080\nPOSTGRES_PASSWORD=\nAUTH_SESSION_EPOCH=\n"
        )

    def test_creates_private_settings_and_preserves_them_on_rerun(self):
        self.assertTrue(initialize(self.directory))
        target = self.directory / ".env"
        first = target.read_text()
        self.assertRegex(first, r"POSTGRES_PASSWORD=[A-Za-z0-9_-]{40,}")
        self.assertRegex(first, r"AUTH_SESSION_EPOCH=[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-")
        if os.name == "posix":
            self.assertEqual(target.stat().st_mode & 0o777, 0o600)
        self.assertFalse(initialize(self.directory))
        self.assertEqual(target.read_text(), first)

    def test_preserves_custom_values_comments_and_missing_final_newline(self):
        target = self.directory / ".env"
        target.write_text("# keep me\nCUSTOM=value\nGATEWAY_PORT=18080")
        initialize(self.directory)
        self.assertTrue(target.read_text().startswith(
            "# keep me\nCUSTOM=value\nGATEWAY_PORT=18080\n"
        ))

    def test_populates_an_empty_template_password(self):
        target = self.directory / ".env"
        target.write_text("GATEWAY_PORT=8080\nPOSTGRES_PASSWORD=\n")
        initialize(self.directory)
        self.assertNotIn("POSTGRES_PASSWORD=\n", target.read_text())

    def test_rejects_duplicate_keys_without_modifying_the_file(self):
        target = self.directory / ".env"
        original = "GATEWAY_PORT=8080\nGATEWAY_PORT=18080\n"
        target.write_text(original)
        with self.assertRaisesRegex(ValueError, "Duplicate setting"):
            initialize(self.directory)
        self.assertEqual(target.read_text(), original)


if __name__ == "__main__":
    unittest.main()
