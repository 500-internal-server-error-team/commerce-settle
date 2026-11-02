"""Obtain one owner's talk_message grant through a loopback callback; never send a message."""

import argparse
from contextlib import contextmanager
import fcntl
import getpass
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import secrets
import stat
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, location):
        raise ValueError("카카오 응답의 리다이렉트를 거절했습니다.")


def kakao_json(url, fields=None, access=None):
    headers = {"Content-Type": "application/x-www-form-urlencoded;charset=utf-8"}
    if access:
        headers["Authorization"] = "Bearer " + access
    request = Request(url, data=None if fields is None else urlencode(fields).encode(), headers=headers)
    try:
        with build_opener(NoRedirect()).open(request, timeout=5) as response:
            raw = response.read(32769)
        if len(raw) > 32768:
            raise ValueError("카카오 응답 크기가 허용 범위를 넘었습니다.")
        return json.loads(raw)
    except HTTPError as error:
        # OAuth code, client secret and remote error bodies must not reach the terminal.
        raise ValueError(f"카카오 인증 요청이 거절됐습니다(HTTP {error.code}).") from None


def credentials(result, info, client_id, client_secret, user_id, app_id, now):
    if str(info.get("id")) != user_id or str(info.get("app_id")) != app_id:
        raise ValueError("선택한 본인 계정·앱과 발급된 토큰이 다릅니다.")
    if "talk_message" not in result.get("scope", "").split():
        raise ValueError("talk_message 동의를 완료해 주세요.")
    for key in ("access_token", "refresh_token"):
        if not isinstance(result.get(key), str) or not result[key] or any(
            ord(character) < 33 or ord(character) > 126 for character in result[key]
        ):
            raise ValueError("카카오 토큰 응답이 올바르지 않습니다.")
    for key in ("expires_in", "refresh_token_expires_in"):
        if type(result.get(key)) is not int or result[key] <= 0:
            raise ValueError("카카오 토큰 만료 시각이 올바르지 않습니다.")
    return {
        "clientId": client_id, "clientSecret": client_secret,
        "kakaoUserId": user_id, "appId": app_id,
        "accessToken": result["access_token"], "refreshToken": result["refresh_token"],
        "expiresAt": now + result["expires_in"],
        "refreshExpiresAt": now + result["refresh_token_expires_in"],
    }


def secure(path, directory=False):
    entry = path.lstat()
    expected = 0o700 if directory else 0o600
    valid = stat.S_ISDIR(entry.st_mode) if directory else stat.S_ISREG(entry.st_mode)
    if not valid or stat.S_IMODE(entry.st_mode) != expected or entry.st_uid != os.getuid():
        raise ValueError("자격 폴더는 본인 소유 0700, 자격·잠금 파일은 0600이어야 합니다.")


@contextmanager
def token_lock(path):
    secure(path.parent, directory=True)
    lock = path.with_name(path.name + ".lock")
    descriptor = os.open(lock, os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    try:
        secure(lock)
        # Linux flock과 Java FileChannel 잠금은 호환되지 않아 POSIX record lock으로 맞춘다.
        fcntl.lockf(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)


def save(path, value):
    with token_lock(path):
        if path.exists() or path.is_symlink():
            secure(path)
        descriptor, name = tempfile.mkstemp(prefix=".kakao-token-", dir=path.parent)
        try:
            with os.fdopen(descriptor, "w") as output:
                json.dump(value, output, ensure_ascii=False)
                output.flush()
                os.fsync(output.fileno())
            os.replace(name, path)
        finally:
            if os.path.exists(name):
                os.unlink(name)


def authorize(args, client_id, client_secret, wait_seconds=300):
    state = secrets.token_urlsafe(32)
    redirect = f"http://127.0.0.1:{args.port}/callback"
    login = "https://kauth.kakao.com/oauth/authorize?" + urlencode({
        "response_type": "code", "client_id": client_id, "redirect_uri": redirect,
        "scope": "talk_message", "state": state,
    })
    outcome = {"done": False, "success": False}

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, format, *values):
            pass

        def do_GET(self):
            if self.headers.get("Host") != f"127.0.0.1:{args.port}":
                self.reply(400, "허용하지 않는 요청입니다.")
                return
            url = urlsplit(self.path)
            if url.path == "/login" and not url.query:
                self.send_response(302)
                self.send_header("Location", login)
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                return
            query = parse_qs(url.query, keep_blank_values=True)
            states = query.get("state", [])
            if (url.path != "/callback" or len(states) != 1
                    or not secrets.compare_digest(states[0].encode(), state.encode())
                    or len(query.get("code", [])) != 1):
                self.reply(400, "로그인 동의 또는 요청 상태를 확인해 주세요.")
                return
            outcome["done"] = True
            try:
                result = kakao_json("https://kauth.kakao.com/oauth/token", {
                    "grant_type": "authorization_code", "client_id": client_id,
                    "client_secret": client_secret, "redirect_uri": redirect, "code": query["code"][0],
                })
                info = kakao_json("https://kapi.kakao.com/v1/user/access_token_info", access=result["access_token"])
                save(args.file, credentials(result, info, client_id, client_secret, args.user_id, args.app_id, int(time.time())))
                outcome["success"] = True
                self.reply(200, "본인 카카오 인증을 저장했습니다. 이 창을 닫아 주세요.")
            except (OSError, ValueError, KeyError, TypeError):
                self.reply(400, "인증을 저장하지 못했습니다. 앱 설정·본인 계정·동의·파일 권한을 확인해 주세요.")

        def reply(self, status, text):
            body = text.encode()
            self.send_response(status)
            self.send_header("Content-Type", "text/plain;charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    with HTTPServer(("127.0.0.1", args.port), Handler) as server:
        server.timeout = 1
        print(f"본인 카카오 계정으로 동의하려면 http://127.0.0.1:{args.port}/login 을 여세요.")
        deadline = time.monotonic() + wait_seconds
        while not outcome["done"] and time.monotonic() < deadline:
            server.handle_request()
    if not outcome["success"]:
        raise ValueError("인증이 완료되지 않았습니다. 원인을 확인한 뒤 다시 실행해 주세요.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--file", type=Path, required=True)
    parser.add_argument("--user-id", required=True)
    parser.add_argument("--app-id", required=True)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if not args.file.is_absolute() or not 1024 <= args.port <= 65535:
        parser.error("절대 자격 파일 경로와 1024~65535 포트가 필요합니다.")
    if any(not value.isascii() or not value.isdecimal() or int(value) <= 0
           for value in (args.user_id, args.app_id)):
        parser.error("본인 카카오 사용자 ID와 앱 ID를 확인해 주세요.")
    secure(args.file.parent, directory=True)
    client_id = getpass.getpass("Kakao REST API 키: ")
    client_secret = getpass.getpass("Kakao client secret: ")
    if not client_id or not client_secret:
        parser.error("앱의 REST API 키와 활성화한 client secret이 필요합니다.")
    authorize(args, client_id, client_secret)
    print("인증 저장 완료. 메시지는 이 도구가 아니라 실제 거래 알림 Worker가 발송합니다.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyboardInterrupt):
        raise SystemExit("인증을 완료하지 못했습니다. 본인 계정·앱 설정·동의·파일 권한을 확인해 주세요.") from None
