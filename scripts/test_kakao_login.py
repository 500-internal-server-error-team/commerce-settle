"""Exercise credential binding and private storage without a real Kakao account."""

import json
import fcntl
from contextlib import redirect_stdout
from http.server import HTTPServer
from io import StringIO
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import urlopen

import kakao_login


class KakaoLoginTest(unittest.TestCase):
    def setUp(self):
        self.result = {"access_token": "test-access", "refresh_token": "test-refresh",
                       "expires_in": 3600, "refresh_token_expires_in": 86400, "scope": "talk_message"}
        self.info = {"id": 123, "app_id": 456}

    def test_binds_expiry_and_owner_to_exchanged_tokens(self):
        value = kakao_login.credentials(self.result, self.info, "test-client", "test-secret", "123", "456", 100)

        self.assertEqual(value["expiresAt"], 3700)
        self.assertEqual(value["refreshExpiresAt"], 86500)
        self.assertEqual(value["kakaoUserId"], "123")

    def test_rejects_another_account_or_app(self):
        for info in ({"id": 999, "app_id": 456}, {"id": 123, "app_id": 999}):
            with self.subTest(info=info), self.assertRaises(ValueError):
                kakao_login.credentials(self.result, info, "client", "secret", "123", "456", 100)

    def test_requires_message_consent(self):
        self.result["scope"] = "profile_nickname"
        with self.assertRaises(ValueError):
            kakao_login.credentials(self.result, self.info, "client", "secret", "123", "456", 100)

    def test_stores_private_file_without_leaving_temporary_tokens(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "credentials.json"
            kakao_login.save(path, {"accessToken": "test-access"})
            kakao_login.save(path, {"accessToken": "next-access"})

            self.assertEqual(json.loads(path.read_text()), {"accessToken": "next-access"})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            self.assertEqual({p.name for p in path.parent.iterdir()}, {"credentials.json", "credentials.json.lock"})

    def test_rejects_symlink_instead_of_overwriting_its_target(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "original.json"
            target.write_text("preserved")
            path = Path(directory) / "credentials.json"
            path.symlink_to(target)

            with self.assertRaises(ValueError):
                kakao_login.save(path, {"accessToken": "test-access"})

            self.assertEqual(target.read_text(), "preserved")

    def test_respects_another_process_posix_token_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "credentials.json"
            lock = path.with_name(path.name + ".lock")
            lock.touch(mode=0o600)
            with lock.open("w") as held:
                fcntl.lockf(held, fcntl.LOCK_EX | fcntl.LOCK_NB)
                child = """import sys
from pathlib import Path
import kakao_login
try:
    kakao_login.save(Path(sys.argv[1]), {"accessToken": "test-access"})
except BlockingIOError:
    raise SystemExit(7)
"""
                result = subprocess.run(
                    [sys.executable, "-B", "-c", child, str(path)],
                    cwd=Path(__file__).resolve().parent,
                    capture_output=True, text=True, timeout=5,
                )

            self.assertEqual(result.returncode, 7, result.stderr)
            self.assertFalse(path.exists())

    def test_callback_checks_state_and_never_prints_secrets(self):
        ready = threading.Event()
        errors = []

        class ReadyServer(HTTPServer):
            def server_activate(self):
                super().server_activate()
                ready.set()

        with tempfile.TemporaryDirectory() as directory, socket.socket() as reserve:
            reserve.bind(("127.0.0.1", 0))
            port = reserve.getsockname()[1]
            reserve.close()
            args = SimpleNamespace(file=Path(directory) / "credentials.json", port=port,
                                   user_id="123", app_id="456")

            def run():
                try:
                    kakao_login.authorize(args, "test-client", "test-secret", wait_seconds=3)
                except BaseException as error:
                    errors.append(type(error).__name__)

            output = StringIO()
            with patch.object(kakao_login, "HTTPServer", ReadyServer), \
                    patch.object(kakao_login.secrets, "token_urlsafe", return_value="test-state"), \
                    patch.object(kakao_login, "kakao_json", side_effect=[self.result, self.info]) as remote, \
                    redirect_stdout(output):
                thread = threading.Thread(target=run, daemon=True)
                thread.start()
                self.assertTrue(ready.wait(1))
                with self.assertRaises(HTTPError) as rejected:
                    urlopen(f"http://127.0.0.1:{port}/callback?state=wrong&code=test-code", timeout=1)
                self.assertEqual(rejected.exception.code, 400)
                self.assertFalse(args.file.exists())
                remote.assert_not_called()

                with urlopen(f"http://127.0.0.1:{port}/callback?state=test-state&code=test-code", timeout=1) as response:
                    self.assertEqual(response.status, 200)
                thread.join(4)
                self.assertFalse(thread.is_alive())
                self.assertEqual(errors, [])
                self.assertTrue(args.file.exists())

            for secret in ["test-secret", "test-code", "test-state", "test-access", "test-refresh"]:
                self.assertNotIn(secret, output.getvalue())


if __name__ == "__main__":
    unittest.main()
