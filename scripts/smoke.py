#!/usr/bin/env python3
"""Check the running local foundation, not payment business functionality."""

import json
from pathlib import Path
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
    print("PASS PostgreSQL: four databases, own access and cross-service denial")

    kafka = ["exec", "-T", "kafka"]
    tool = "/opt/kafka/bin/"
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
