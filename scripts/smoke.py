#!/usr/bin/env python3
"""Check the running local foundation, not payment business functionality."""

import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
SERVICES = [
    "gateway", "commerce", "payment-api", "payment-worker",
    "settlement", "finance-mock",
]


def compose(*args, input_text=None, check=True):
    return subprocess.run(
        ["docker", "compose", *args], cwd=ROOT, input=input_text,
        text=True, capture_output=True, timeout=60, check=check,
    )


def internal_json(service, path):
    return json.loads(compose(
        "exec", "-T", service, "curl", "--fail", "--silent",
        f"http://localhost:9091{path}",
    ).stdout)


def redis_as(user, password_key, *command):
    result = compose(
        "exec", "-T", "redis", "sh", "-c",
        f'export REDISCLI_AUTH="${password_key}"; '
        f'exec redis-cli --no-auth-warning --raw --user {user} "$@"',
        "redis-smoke", *command, check=False,
    )
    return (result.stdout + result.stderr).strip()


def check_redis_acl(config):
    redis_users = {
        "gateway": "gateway", "commerce": "commerce", "payment-api": "payment",
        "payment-worker": "payment", "settlement": "settlement", "finance-mock": "finance-mock",
    }
    admin_password = config["services"]["redis"]["environment"]["REDIS_PASSWORD"]
    for service, user in redis_users.items():
        environment = config["services"][service]["environment"]
        assert environment.get("SPRING_DATA_REDIS_USERNAME") == user, service
        assert environment.get("SPRING_DATA_REDIS_PASSWORD") not in (None, "", admin_password), service

    gateway = ("gateway", "GATEWAY_REDIS_PASSWORD")
    commerce = ("commerce", "COMMERCE_REDIS_PASSWORD")
    session = "commerce-settle:auth:session:smoke:" + uuid.uuid4().hex
    read_session = "redis.call('TIME'); return redis.call('HMGET', KEYS[1], 'purpose')"
    read_sha = hashlib.sha1(read_session.encode()).hexdigest()
    expire_session = "return redis.call('PEXPIRE', KEYS[1], 60000)"
    try:
        assert redis_as(*commerce, "HSET", session, "purpose", "SMOKE") == "1"
        assert redis_as(*commerce, "EVAL", expire_session, "1", session) == "1"
        assert redis_as(*commerce, "EVALSHA", hashlib.sha1(expire_session.encode()).hexdigest(),
                        "1", session) == "1"
        # EVAL_RO caches the script, so the following EVALSHA_RO covers Gateway's usual path.
        assert redis_as(*gateway, "EVAL_RO", read_session, "1", session) == "SMOKE"
        assert redis_as(*gateway, "EVALSHA_RO", read_sha, "1", session) == "SMOKE"
        for command in [
            ("HSET", session, "purpose", "FORGED"),
            ("EVAL", read_session, "1", session),
            ("EVALSHA", "0" * 40, "1", session),
            ("SCRIPT", "LOAD", read_session),
            ("SCRIPT", "FLUSH"),
            ("HMGET", "commerce-settle:auth:rate-limit:smoke", "count"),
        ]:
            assert "NOPERM" in redis_as(*gateway, *command), command
        for command in [
            ("SET", "commerce-settle:payment:smoke", "1"),
            ("SCRIPT", "FLUSH"),
            ("FUNCTION", "FLUSH"),
            ("SCAN", "0"),
            ("RANDOMKEY",),
            ("SELECT", "1"),
        ]:
            assert "NOPERM" in redis_as(*commerce, *command), command
        for user, password_key in [
            ("payment", "PAYMENT_REDIS_PASSWORD"), ("settlement", "SETTLEMENT_REDIS_PASSWORD"),
            ("finance-mock", "FINANCE_MOCK_REDIS_PASSWORD"),
        ]:
            assert "NOPERM" in redis_as(user, password_key, "HMGET", session, "purpose"), user
    finally:
        compose("exec", "-T", "redis", "sh", "-c",
                'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli DEL "$1"', "redis-smoke", session)


def gateway_status(base, method, path, token=None):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    try:
        with urlopen(Request(base + path, method=method, headers=headers), timeout=15) as response:
            return response.status, {}
    except HTTPError as error:
        body = error.read()
        return error.code, json.loads(body) if body.startswith(b"{") else {}


def gateway_json(base, method, path, body=None, token=None, headers=None):
    headers = dict(headers or {})
    if body is not None:
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urlopen(Request(base + path, data=data, method=method, headers=headers),
                     timeout=15) as response:
            payload = response.read()
            return response.status, json.loads(payload) if payload else {}
    except HTTPError as error:
        payload = error.read()
        return error.code, json.loads(payload) if payload.startswith(b"{") else {}


def check_login_flow(base):
    # A fresh email per run keeps reruns away from the per-email login limit.
    email = f"smoke-{uuid.uuid4().hex}@example.com"
    password = uuid.uuid4().hex
    status, account = gateway_json(base, "POST", "/api/sellers", {
        "email": email, "password": password, "displayName": "smoke", "payoutAccountRef": "smoke",
    })
    assert status == 201 and account["actorKind"] == "SELLER", (status, account)
    status, _ = gateway_json(base, "POST", "/api/seller-sessions",
                             {"email": email, "password": password + "x"})
    assert status == 401, status
    status, session = gateway_json(base, "POST", "/api/seller-sessions",
                                   {"email": email.upper(), "password": password})
    assert status == 200 and session["tokenType"] == "Bearer", (status, session)
    assert session["expiresAt"].endswith("+09:00"), session
    token = session["token"]

    status, me = gateway_json(base, "GET", "/api/accounts/me", token=token)
    assert status == 200 and me["id"] == account["id"], (status, me)
    status, problem = gateway_json(base, "GET", "/api/buyers/me/coupons", token=token)
    assert (status, problem.get("code")) == (403, "COMMON_ROLE_FORBIDDEN"), status
    status, _ = gateway_json(base, "DELETE", "/api/sessions/current", token=token)
    assert status == 204, status
    status, problem = gateway_json(base, "GET", "/api/accounts/me", token=token)
    assert (status, problem.get("code")) == (401, "COMMON_AUTHENTICATION_REQUIRED"), status


def check_password_reset(base):
    # The token is only stored as a delivery ciphertext, so smoke checks the public contract only.
    email = f"smoke-{uuid.uuid4().hex}@example.com"
    status, _ = gateway_json(base, "POST", "/api/sellers", {
        "email": email, "password": uuid.uuid4().hex, "displayName": "smoke",
        "payoutAccountRef": "smoke",
    })
    assert status == 201, status
    responses = [
        gateway_json(base, "POST", "/api/password-reset-requests",
                     {"actorKind": "SELLER", "email": address})
        for address in (email, f"none-{uuid.uuid4().hex}@example.com")
    ]
    assert responses[0] == responses[1] and responses[0][0] == 200, responses
    assert "token" not in responses[0][1], responses[0]
    status, problem = gateway_json(base, "POST", "/api/password-resets", {
        "actorKind": "SELLER", "token": uuid.uuid4().hex, "newPassword": uuid.uuid4().hex,
    })
    assert (status, problem.get("code")) == (401, "COMMON_AUTHENTICATION_REQUIRED"), status


def check_session_auth(config, base):
    epoch = config["services"]["gateway"]["environment"]["AUTH_SESSION_EPOCH"]
    token = uuid.uuid4().hex
    seller_id = str(uuid.uuid4())
    session = f"commerce-settle:auth:session:{epoch}:{hashlib.sha256(token.encode()).hexdigest()}"
    guard = f"commerce-settle:auth:subject:{epoch}:SELLER:{seller_id}"
    admin = ["exec", "-T", "redis", "sh", "-c",
             'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"', "redis-smoke"]
    try:
        status, problem = gateway_status(base, "GET", "/api/sellers/me")
        assert (status, problem.get("code")) == (401, "COMMON_AUTHENTICATION_REQUIRED"), status

        seconds, micros = compose(*admin, "TIME").stdout.split()
        now = int(seconds) * 1000 + int(micros) // 1000
        compose(*admin, "HSET", session, "actorKind", "SELLER", "actorId", seller_id,
                "purpose", "SESSION", "authVersion", "0", "issuedAt", str(now),
                "expiresAt", str(now + 60_000))
        compose(*admin, "HSET", guard, "authVersion", "0", "state", "ACTIVE")
        status, _ = gateway_status(base, "GET", "/api/sellers/me", token)
        # Commerce has no seller profile controller yet, so any non-auth status shows the request passed.
        assert status < 500 and status not in (401, 403), status
        status, problem = gateway_status(base, "GET", "/api/buyers/me/coupons", token)
        assert (status, problem.get("code")) == (403, "COMMON_ROLE_FORBIDDEN"), status

        compose(*admin, "HSET", guard, "pendingChangeId", str(uuid.uuid4()))
        status, problem = gateway_status(base, "GET", "/api/sellers/me", token)
        assert (status, problem.get("code")) == (503, "COMMON_TEMPORARILY_UNAVAILABLE"), status
    finally:
        compose(*admin, "DEL", session, guard)


def check_buyer_addresses(config, base):
    # Buyer signup waits for the welcome coupon, so the buyer row and session are written directly.
    epoch = config["services"]["gateway"]["environment"]["AUTH_SESSION_EPOCH"]
    token = uuid.uuid4().hex
    buyer_id = str(uuid.uuid4())
    session = f"commerce-settle:auth:session:{epoch}:{hashlib.sha256(token.encode()).hexdigest()}"
    guard = f"commerce-settle:auth:subject:{epoch}:BUYER:{buyer_id}"
    admin = ["exec", "-T", "redis", "sh", "-c",
             'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"', "redis-smoke"]
    psql = ["exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "commerce",
            "-v", "ON_ERROR_STOP=1", "-v", f"buyer={buyer_id}", "-Atq"]
    address = {"recipient": "smoke", "phone": "010-0000-0000", "postalCode": "00000",
               "addressLine1": "smoke street", "addressLine2": "", "isDefault": False}
    try:
        compose(*psql, input_text="""
INSERT INTO commerce.buyer_accounts
    (id, email_normalized, password_hash, display_name, status, created_at, updated_at, version)
VALUES (:'buyer', :'buyer' || '@smoke.local', 'smoke', 'smoke', 'ACTIVE', now(), now(), 0);
""")
        seconds, micros = compose(*admin, "TIME").stdout.split()
        now = int(seconds) * 1000 + int(micros) // 1000
        compose(*admin, "HSET", session, "actorKind", "BUYER", "actorId", buyer_id,
                "purpose", "SESSION", "authVersion", "0", "issuedAt", str(now),
                "expiresAt", str(now + 60_000))
        compose(*admin, "HSET", guard, "authVersion", "0", "state", "ACTIVE")

        key = {"Idempotency-Key": f"smoke-{uuid.uuid4().hex}"}
        status, created = gateway_json(base, "POST", "/api/buyers/me/addresses", address,
                                       token, key)
        assert status == 201 and created["isDefault"] is True, (status, created)
        status, replay = gateway_json(base, "POST", "/api/buyers/me/addresses", address,
                                      token, key)
        assert status == 201 and replay["id"] == created["id"], (status, replay)
        status, page = gateway_json(base, "GET", "/api/buyers/me/addresses", token=token)
        assert status == 200 and [item["id"] for item in page["items"]] == [created["id"]], page
        status, _ = gateway_json(
            base, "DELETE",
            f"/api/buyers/me/addresses/{created['id']}?expectedVersion={created['version']}",
            token=token, headers={"Idempotency-Key": f"smoke-{uuid.uuid4().hex}"})
        assert status == 204, status
        status, page = gateway_json(base, "GET", "/api/buyers/me/addresses", token=token)
        assert status == 200 and page["totalElements"] == 0, page
    finally:
        compose(*admin, "DEL", session, guard)
        compose(*psql, input_text="""
DELETE FROM commerce.idempotency_records WHERE actor_kind = 'BUYER' AND actor_id = :'buyer';
DELETE FROM commerce.buyer_addresses WHERE buyer_id = :'buyer';
DELETE FROM commerce.buyer_accounts WHERE id = :'buyer';
""")


def main():
    # Reading the resolved port supports overrides in .env and COMPOSE_FILE.
    config = json.loads(compose("config", "--format", "json").stdout)
    port = config["services"]["gateway"]["ports"][0]["published"]
    base = f"http://127.0.0.1:{port}"

    for service in SERVICES:
        health = internal_json(service, "/actuator/health")
        assert health["status"] == "UP", (service, health)
        assert health["components"]["redis"]["status"] == "UP", service
        if service != "gateway":
            assert health["components"]["db"]["status"] == "UP", service
        metrics = compose(
            "exec", "-T", service, "curl", "--fail", "--silent",
            "http://localhost:9091/actuator/prometheus",
        ).stdout
        assert "jvm_memory_used_bytes" in metrics, service
        if service != "gateway":
            assert not config["services"][service].get("ports"), service
        print(f"PASS {service}: readiness, metrics and port boundary")

        logs = compose("logs", "--no-color", "--no-log-prefix", "--tail=100", service).stdout
        timestamps = re.findall(r"(?m)^(\d{4}-\d{2}-\d{2}T\S+)\s", logs)
        assert timestamps and all(stamp.endswith("+09:00") for stamp in timestamps), service
    print("PASS app logs: explicit KST offset for all six processes")

    for service in ["commerce", "payment", "settlement"]:
        with urlopen(f"{base}/v3/api-docs/{service}", timeout=15) as response:
            document = json.load(response)
        assert document["openapi"].startswith("3."), document
        assert document["info"]["title"].lower() == service, document
        assert document["servers"] == [{"url": "/"}], document
    with urlopen(f"{base}/swagger-ui.html", timeout=15) as response:
        assert response.status == 200
    for path in ["/internal/pg", "/actuator/env", "/actuator/prometheus", "/api/mock/approve"]:
        try:
            urlopen(base + path, timeout=10)
            raise AssertionError(f"Unexpected external route: {path}")
        except HTTPError as error:
            assert error.code == 404, (path, error.code)
    print("PASS Gateway: service docs, Swagger UI and blocked internal paths")

    assert not config["services"]["redis"].get("ports"), "Redis must remain internal"
    unauthenticated = compose("exec", "-T", "redis", "redis-cli", "ping")
    assert "NOAUTH" in unauthenticated.stdout, unauthenticated.stdout
    redis_cli = ["exec", "-T", "redis", "sh", "-c",
                 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"',
                 "redis-smoke"]
    key = "commerce-settle:bootstrap:smoke:" + uuid.uuid4().hex
    value = uuid.uuid4().hex
    try:
        assert compose(*redis_cli, "SET", key, value, "EX", "60").stdout.strip() == "OK"
        assert compose(*redis_cli, "GET", key).stdout.strip() == value
        assert 0 < int(compose(*redis_cli, "TTL", key).stdout.strip()) <= 60
    finally:
        compose(*redis_cli, "DEL", key)
    print("PASS Redis: all app connections, auth boundary and expiring key round trip")

    check_redis_acl(config)
    print("PASS Redis ACL: per-app users, Commerce auth writes and read-only Gateway checks")

    check_session_auth(config, base)
    print("PASS Gateway session: 401 without a session, Redis session pass, role 403, pending guard 503")

    check_login_flow(base)
    print("PASS Commerce login: seller signup, Gateway-verified session, logout then 401")

    check_password_reset(base)
    print("PASS Commerce password reset: same request response for unknown accounts, bad token 401")

    check_buyer_addresses(config, base)
    print("PASS Buyer addresses: first address default, idempotent replay, list, delete")

    trace_id = uuid.uuid4().hex
    parent_id = uuid.uuid4().hex[:16]
    request = Request(f"{base}/v3/api-docs/commerce", headers={
        "traceparent": f"00-{trace_id}-{parent_id}-01",
    })
    with urlopen(request, timeout=15) as response:
        assert response.status == 200
    zipkin_port = config["services"]["zipkin"]["ports"][0]["published"]
    spans = []
    for _ in range(30):
        try:
            with urlopen(f"http://127.0.0.1:{zipkin_port}/api/v2/trace/{trace_id}", timeout=5) as response:
                spans = json.load(response)
        except HTTPError as error:
            if error.code != 404:
                raise
        gateway_clients = {span["id"] for span in spans
                           if span.get("localEndpoint", {}).get("serviceName") == "gateway"
                           and span.get("kind") == "CLIENT"}
        if any(span.get("localEndpoint", {}).get("serviceName") == "commerce"
               and span.get("kind") == "SERVER" and span.get("parentId") in gateway_clients
               for span in spans):
            break
        time.sleep(1)
    else:
        raise AssertionError(f"Gateway to Commerce trace propagation missing: {spans}")
    print("PASS Zipkin: Gateway client and Commerce server share a parent-linked trace")

    for service, password_key in [
        ("commerce", "COMMERCE_DB_PASSWORD"), ("payment", "PAYMENT_DB_PASSWORD"),
        ("settlement", "SETTLEMENT_DB_PASSWORD"), ("finance_mock", "FINANCE_MOCK_DB_PASSWORD"),
    ]:
        for database in ["commerce", "payment", "settlement", "finance_mock"]:
            command = (
                f'PGPASSWORD="${password_key}" psql -h 127.0.0.1 '
                f'-U {service} -d {database} -c "SELECT 1"'
            )
            result = compose("exec", "-T", "postgres", "bash", "-c", command, check=False)
            if service == database:
                assert result.returncode == 0, (service, database, result.stderr)
            else:
                assert result.returncode != 0 and "permission denied" in result.stderr, (
                    service, database, result.stderr
                )
        ownership = compose(
            "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", service,
            "-v", "ON_ERROR_STOP=1", "-v", f"service={service}", "-Atq",
            input_text="""
SELECT pg_get_userbyid(nspowner) = :'service'
FROM pg_namespace WHERE nspname = :'service';
SELECT coalesce(bool_and(tableowner = :'service'), true)
FROM pg_tables WHERE schemaname = :'service';
""",
        ).stdout.splitlines()
        assert ownership == ["t", "t"], (service, ownership)
    print("PASS PostgreSQL: service-owned initial schemas/tables, own access and cross-service denial")

    times = compose(
        "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "postgres",
        "-v", "ON_ERROR_STOP=1", "-Atq", input_text="""
SHOW TimeZone;
SHOW log_timezone;
CREATE TEMP TABLE time_smoke (occurred_at timestamptz NOT NULL);
INSERT INTO time_smoke VALUES ('2026-10-31T15:00:00.123456Z');
SELECT occurred_at::text FROM time_smoke;
SELECT occurred_at = '2026-11-01T00:00:00.123456+09:00'::timestamptz FROM time_smoke;
""",
    ).stdout.splitlines()
    assert times == ["Asia/Seoul", "Asia/Seoul", "2026-11-01 00:00:00.123456+09", "t"], times
    print("PASS PostgreSQL: KST query/log settings and stored instant/microsecond preservation")

    kafka = ["exec", "-T", "kafka"]
    tool = "/opt/kafka/bin/"
    for owner in ["commerce", "payment", "settlement", "commerce-notifications"]:
        topics = [(f"commerce-settle.{owner}.v1.dlt", "604800000")]
        if owner != "commerce-notifications":
            topics.append((f"commerce-settle.{owner}.v1", "86400000"))
        for name, retention in topics:
            description = compose(
                *kafka, tool + "kafka-topics.sh", "--bootstrap-server", "kafka:9092",
                "--describe", "--topic", name,
            ).stdout
            summary = next(line for line in description.splitlines() if "Configs:" in line)
            assert re.search(r"PartitionCount:\s*3\b", summary), description
            assert re.search(r"ReplicationFactor:\s*1\b", summary), description
            configs = dict(item.split("=", 1) for item in summary.split("Configs:", 1)[1].strip().split(","))
            assert configs.get("cleanup.policy") == "delete", description
            assert configs.get("retention.ms") == retention, description
            assert configs.get("retention.bytes") == "-1", description
    print("PASS Kafka: three original and four consumer DLT topic settings")
    topic = "bootstrap.smoke." + uuid.uuid4().hex
    compose(*kafka, tool + "kafka-topics.sh", "--bootstrap-server", "kafka:9092",
            "--create", "--topic", topic, "--partitions", "1", "--replication-factor", "1")
    try:
        message = uuid.uuid4().hex
        compose(*kafka, tool + "kafka-console-producer.sh", "--bootstrap-server", "kafka:9092",
                "--topic", topic, input_text=message + "\n")
        received = compose(*kafka, tool + "kafka-console-consumer.sh", "--bootstrap-server", "kafka:9092",
                           "--topic", topic, "--from-beginning", "--max-messages", "1", "--timeout-ms", "10000")
        assert received.stdout.strip() == message, received.stdout
    finally:
        compose(*kafka, tool + "kafka-topics.sh", "--bootstrap-server", "kafka:9092", "--delete", "--topic", topic)
    print("PASS Kafka: broker round trip; temporary verification topic removed")
    print("Bootstrap smoke passed. Payment, refund and settlement APIs are not implemented yet.")


if __name__ == "__main__":
    main()
