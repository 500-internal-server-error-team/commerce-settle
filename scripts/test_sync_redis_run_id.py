from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import sync_redis_run_id
from sync_redis_run_id import approve

RUN_ID = "0123456789abcdef0123456789abcdef01234567"
EPOCH = "00000000-0000-4000-8000-000000000001"


class ApproveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.target = self.directory / ".env"

    def test_approves_run_id_and_rotates_epoch_together(self):
        self.target.write_text(f"# keep\nAUTH_SESSION_EPOCH={EPOCH}\nAUTH_REDIS_RUN_ID=\nOTHER=x\n")
        self.assertTrue(approve(self.directory, RUN_ID))
        content = self.target.read_text()
        self.assertIn(f"AUTH_REDIS_RUN_ID={RUN_ID}\n", content)
        self.assertNotIn(EPOCH, content)
        self.assertRegex(content, r"AUTH_SESSION_EPOCH=[0-9a-f]{8}-[0-9a-f]{4}-4")
        self.assertTrue(content.startswith("# keep\n") and content.endswith("OTHER=x\n"))

    def test_keeps_epoch_when_run_id_is_unchanged(self):
        original = f"AUTH_SESSION_EPOCH={EPOCH}\nAUTH_REDIS_RUN_ID={RUN_ID}\n"
        self.target.write_text(original)
        self.assertFalse(approve(self.directory, RUN_ID))
        self.assertEqual(self.target.read_text(), original)

    def test_rotates_epoch_after_reconnect_with_unchanged_run_id(self):
        self.target.write_text(f"AUTH_SESSION_EPOCH={EPOCH}\nAUTH_REDIS_RUN_ID={RUN_ID}\nOTHER=x\n")

        self.assertTrue(approve(self.directory, RUN_ID, recover=True))

        content = self.target.read_text()
        self.assertNotIn(EPOCH, content)
        self.assertIn(f"AUTH_REDIS_RUN_ID={RUN_ID}\nOTHER=x\n", content)

    def test_recovery_option_rotates_epoch_through_cli_entrypoint(self):
        self.target.write_text(f"AUTH_SESSION_EPOCH={EPOCH}\nAUTH_REDIS_RUN_ID={RUN_ID}\n")

        with patch.object(sync_redis_run_id, "ROOT", self.directory), \
                patch.object(sync_redis_run_id, "running_run_id", return_value=RUN_ID):
            sync_redis_run_id.main(["--recover"])

        self.assertNotIn(EPOCH, self.target.read_text())

    def test_rejects_invalid_run_id_and_uninitialized_settings(self):
        self.target.write_text(f"AUTH_SESSION_EPOCH={EPOCH}\n")
        with self.assertRaisesRegex(ValueError, "Not a Redis run_id"):
            approve(self.directory, RUN_ID.upper())
        with self.assertRaisesRegex(ValueError, "init_env"):
            approve(self.directory, RUN_ID)
        self.assertEqual(self.target.read_text(), f"AUTH_SESSION_EPOCH={EPOCH}\n")


if __name__ == "__main__":
    unittest.main()
