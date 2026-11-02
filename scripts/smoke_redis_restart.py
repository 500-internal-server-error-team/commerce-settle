#!/usr/bin/env python3
"""Check auth blocking and recovery after Redis restarts and same-run_id reconnects.

This restarts Redis and recreates Gateway and Commerce, so run it after smoke.py on a stack
that nobody else is using. It updates AUTH_REDIS_RUN_ID and AUTH_SESSION_EPOCH in .env.
"""

import json
import subprocess
import time
import uuid

from smoke import ROOT, compose, gateway_json, redis_as
import sync_redis_run_id


def login(base, email, password):
    status, session = gateway_json(base, "POST", "/api/seller-sessions",
                                   {"email": email, "password": password})
    return status, session.get("token")


def require_blocked(base, email, password, token):
    deadline = time.monotonic() + 10
    while True:
        status, problem = gateway_json(base, "GET", "/api/accounts/me", token=token)
        login_status, _ = login(base, email, password)
        if (status, problem.get("code"), login_status) == (
                503, "COMMON_TEMPORARILY_UNAVAILABLE", 503):
            return
        assert time.monotonic() < deadline, (status, problem, login_status)
        time.sleep(0.1)


def main():
    config = json.loads(compose("config", "--format", "json").stdout)
    base = f"http://127.0.0.1:{config['services']['gateway']['ports'][0]['published']}"
    email = f"smoke-{uuid.uuid4().hex}@example.com"
    password = uuid.uuid4().hex
    status, _ = gateway_json(base, "POST", "/api/sellers", {
        "email": email, "password": password, "displayName": "smoke", "payoutAccountRef": "smoke",
    })
    assert status == 201, status
    status, token = login(base, email, password)
    assert status == 200, status
    status, _ = gateway_json(base, "GET", "/api/accounts/me", token=token)
    assert status == 200, status

    for failure in ("restart", "reconnect"):
        before_run_id = sync_redis_run_id.running_run_id()
        if failure == "restart":
            # Keep the AOF and restore the session under a new run_id.
            compose("restart", "redis")
            compose("up", "-d", "--wait", "redis")
        else:
            # Disconnect both app users without restarting the Redis process.
            for user in ("gateway", "commerce"):
                assert int(redis_as("default", "REDIS_PASSWORD", "CLIENT", "KILL", "USER", user)) > 0
        after_run_id = sync_redis_run_id.running_run_id()
        assert (after_run_id == before_run_id) == (failure == "reconnect"), failure
        require_blocked(base, email, password, token)
        print(f"PASS Redis {failure}: existing session and new login are refused with 503")

        sync_redis_run_id.main(["--recover"] if failure == "reconnect" else [])
        subprocess.run(["docker", "compose", "up", "-d", "--wait", "--wait-timeout", "300",
                        "gateway", "commerce"], cwd=ROOT, check=True, timeout=360)
        status, problem = gateway_json(base, "GET", "/api/accounts/me", token=token)
        assert (status, problem.get("code")) == (401, "COMMON_AUTHENTICATION_REQUIRED"), status
        status, token = login(base, email, password)
        assert status == 200, status
        status, _ = gateway_json(base, "GET", "/api/accounts/me", token=token)
        assert status == 200, status
        print(f"PASS Redis {failure} recovery: old session rejected and new login allowed")


if __name__ == "__main__":
    main()
