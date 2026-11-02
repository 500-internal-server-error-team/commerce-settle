#!/usr/bin/env python3
"""Check that a Redis restart blocks user auth until the run_id and epoch are re-approved.

This restarts Redis and recreates Gateway and Commerce, so run it after smoke.py on a stack
that nobody else is using. It updates AUTH_REDIS_RUN_ID and AUTH_SESSION_EPOCH in .env.
"""

import json
import subprocess
import uuid

from smoke import ROOT, compose, gateway_json
import sync_redis_run_id


def login(base, email, password):
    status, session = gateway_json(base, "POST", "/api/seller-sessions",
                                   {"email": email, "password": password})
    return status, session.get("token")


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

    # The same container keeps its AOF, so the session is restored under a new run_id.
    compose("restart", "redis")
    compose("up", "-d", "--wait", "redis")
    status, problem = gateway_json(base, "GET", "/api/accounts/me", token=token)
    assert (status, problem.get("code")) == (503, "COMMON_TEMPORARILY_UNAVAILABLE"), status
    status, _ = login(base, email, password)
    assert status == 503, status
    print("PASS Redis restart: restored session and new login are refused with 503")

    sync_redis_run_id.main()
    subprocess.run(["docker", "compose", "up", "-d", "--wait", "--wait-timeout", "300",
                    "gateway", "commerce"], cwd=ROOT, check=True, timeout=360)
    status, problem = gateway_json(base, "GET", "/api/accounts/me", token=token)
    assert (status, problem.get("code")) == (401, "COMMON_AUTHENTICATION_REQUIRED"), status
    status, token = login(base, email, password)
    assert status == 200, status
    status, _ = gateway_json(base, "GET", "/api/accounts/me", token=token)
    assert status == 200, status
    print("PASS Redis recovery: new epoch and run_id reject the old session and allow new logins")


if __name__ == "__main__":
    main()
