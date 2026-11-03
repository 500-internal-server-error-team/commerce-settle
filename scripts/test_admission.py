"""Real collectors -> Prometheus -> 60-second gate recovery -> actual order HTTP/DB.

Owns a fresh Compose project and removes only its containers, volumes and settings.
Financial result recovery is covered separately; this does not certify the full SAGA.
"""

from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import tempfile
import time
from urllib.parse import urlencode
from urllib.request import urlopen
import uuid

from init_env import initialize
from smoke import gateway_json
from sync_redis_run_id import PLACEHOLDER, approve


ROOT = Path(__file__).resolve().parents[1]


def until(action, description, seconds=180):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = action()
        if value:
            return value
        time.sleep(1)
    raise AssertionError(description)


SUBSCRIPTIONS = {
    "commerce.v1": ["payment"], "payment.v1": ["commerce", "settlement"],
    "settlement.v1": ["payment", "commerce"],
}


def prepare_offsets(compose):
    """Start every financial group at the beginning of its contract topics in a new project."""
    # The project is new and all consumers are disabled. Never reset a shared group's offsets.
    for group, topics in SUBSCRIPTIONS.items():
        arguments = ["exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server", "kafka:9092",
                     "--group", group, "--reset-offsets", "--to-earliest", "--execute"]
        for topic in topics:
            arguments += ["--topic", "commerce-settle." + topic + ".v1"]
        compose(*arguments)


@contextmanager
def stack(prefix="cs-admission-", *, memory_storage=False):
    project = prefix + secrets.token_hex(5)
    directory = Path(tempfile.mkdtemp(prefix=project + "-"))
    settings = directory / "override.json"
    environment = {key: value for key, value in os.environ.items() if not key.startswith("COMPOSE_")}
    attempted = False
    command = ["docker", "compose", "--project-name", project, "--env-file", str(directory / ".env"),
               "-f", str(ROOT / "compose.yml"), "-f", str(settings)]

    def compose(*args, input_text=None, timeout=90):
        result = subprocess.run(command + list(args), cwd=ROOT, env=environment,
                                input=input_text, text=True, capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError("Compose failed: " + result.stderr[-8000:])
        return result.stdout

    try:
        shutil.copyfile(ROOT / ".env.example", directory / ".env.example")
        initialize(directory)
        overrides = {"services": {
            "commerce": {"environment": {"APP_OUTBOX_ENABLED": "true", "APP_ORDER_STUB_ENABLED": "false",
                "APP_KAFKA_PAYMENT_ACCEPTED_ENABLED": "false", "APP_PAYMENT_ACCEPTED_PROCESSOR_ENABLED": "false"}},
            "payment-worker": {"environment": {"APP_OUTBOX_ENABLED": "false", "APP_KAFKA_ORDER_PAYMENT_REQUESTED_ENABLED": "false",
                "APP_PAYMENT_REQUEST_PROCESSOR_ENABLED": "false"}},
            "settlement": {"environment": {"APP_OUTBOX_ENABLED": "false", "APP_KAFKA_SETTLEMENT_EVENTS_ENABLED": "false",
                "SETTLEMENT_INBOX_PROCESSOR_ENABLED": "false", "SETTLEMENT_PAYOUT_WORKER_ENABLED": "false", "SETTLEMENT_DAILY_BATCH_ENABLED": "false"}}
        }}
        if memory_storage:
            # This test's real data filesystems have their own capacity, independent of other projects.
            overrides["volumes"] = {
                name: {"driver_opts": {"type": "tmpfs", "device": "tmpfs", "o": f"size=2g,uid={uid},gid={uid}"}}
                for name, uid in [("postgres-data", 999), ("kafka-data", 1000)]
            }
        settings.write_text(json.dumps(overrides))
        for line in (directory / ".env").read_text().splitlines():
            if "=" in line and not line.startswith("#"):
                environment.pop(line.split("=", 1)[0], None)
        for name in ["GATEWAY_PORT", "PROMETHEUS_PORT", "ZIPKIN_PORT", "ALERTMANAGER_PORT"]:
            environment[name] = "0"
        environment.update({"COMPOSE_PROFILES": "", "APP_ORDER_STUB_ENABLED": "false"})
        attempted = True
        environment["AUTH_REDIS_RUN_ID"] = PLACEHOLDER
        compose("up", "-d", "--wait", "redis")
        info = compose("exec", "-T", "redis", "sh", "-c", 'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli INFO server')
        run_id = next((line.split(":", 1)[1].strip() for line in info.splitlines() if line.startswith("run_id:")), None)
        approve(directory, run_id)
        environment.pop("AUTH_REDIS_RUN_ID")
        compose("up", "-d", "--build", "--wait", "--wait-timeout", "300", timeout=600)
        compose("up", "-d", "alertmanager")
        yield compose
    except BaseException:
        if attempted:
            try:
                print(compose("ps", "-a"), flush=True)
            except (OSError, RuntimeError, subprocess.TimeoutExpired):
                pass
        raise
    finally:
        try:
            if attempted:
                compose("--profile", "*", "down", "-v", "--rmi", "local", "--remove-orphans", timeout=180)
                for kind in ["container", "volume"]:
                    remaining = subprocess.check_output(["docker", kind, "ls", "-q", "--filter", "label=com.docker.compose.project=" + project], text=True, timeout=15).strip()
                    if remaining:
                        raise AssertionError("Owned " + kind + " remains")
                images = subprocess.check_output(["docker", "image", "ls", "-q", "--filter", "reference=" + project + "-*"], text=True, timeout=15).strip()
                if images:
                    raise AssertionError("Owned build image tags remain")
            shutil.rmtree(directory)
        except BaseException:
            print(f"Cleanup unverified; retained project {project} settings at {directory}", flush=True)
            raise


def verify():
    with stack() as compose:
        def endpoint(service, port):
            return "http://" + compose("port", service, str(port)).strip()

        base = endpoint("gateway", 8080)
        prometheus = endpoint("prometheus", 9090)

        def query(expression):
            with urlopen(prometheus + "/api/v1/query?" + urlencode({"query": expression}), timeout=5) as response:
                result = json.load(response)
            assert result["status"] == "success", result
            return result["data"]["result"]

        def gate(expected):
            rows = query("commerce_order_admission_open")
            return rows and all(float(row["value"][1]) == expected for row in rows)

        until(lambda: gate(0), "gate did not start closed")
        started = time.monotonic()
        prepare_offsets(compose)
        until(lambda: gate(1), "real observations did not open admission after recovery")
        first_recovery = time.monotonic() - started
        assert first_recovery >= 60, first_recovery
        assert len(query('commerce_settle_admission_observed_timestamp_seconds > 0')) >= 21

        buyer, seller, product = [str(uuid.uuid4()) for _ in range(3)]

        def sql(statement):
            return compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "commerce", "-v", "ON_ERROR_STOP=1", "-Atq", input_text=statement).strip()

        sql(f"""
INSERT INTO commerce.buyer_accounts (id,email_normalized,password_hash,display_name,status,created_at,updated_at,version)
VALUES ('{buyer}','{buyer}@admission.test','test','test','ACTIVE',now(),now(),0);
INSERT INTO commerce.seller_accounts (id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version)
VALUES ('{seller}','{seller}@admission.test','test','test','ACTIVE','test-account',now(),now(),0);
INSERT INTO commerce.products (id,seller_id,name,unit_price,status,created_at,updated_at,version)
VALUES ('{product}','{seller}','admission test',1000,'ON_SALE',now(),now(),0);
INSERT INTO commerce.product_inventory (id,product_id,available_qty,reserved_qty,event_allocated_qty,sold_qty,created_at,updated_at,version)
VALUES ('{uuid.uuid4()}','{product}',10,0,0,0,now(),now(),0);
""")
        config = json.loads(compose("config", "--format", "json"))
        epoch = config["services"]["gateway"]["environment"]["AUTH_SESSION_EPOCH"]
        token = secrets.token_urlsafe(32)
        session = f"commerce-settle:auth:session:{epoch}:{hashlib.sha256(token.encode()).hexdigest()}"
        guard = f"commerce-settle:auth:subject:{epoch}:BUYER:{buyer}"

        def redis(*args):
            return compose("exec", "-T", "redis", "sh", "-c", 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"', "admission", *args)

        seconds, micros = redis("TIME").split()
        now = int(seconds) * 1000 + int(micros) // 1000
        redis("HSET", session, "actorKind", "BUYER", "actorId", buyer, "purpose", "SESSION", "authVersion", "0", "issuedAt", str(now), "expiresAt", str(now + 600_000))
        redis("HSET", guard, "authVersion", "0", "state", "ACTIVE")

        def request(method, path, body=None, key=None):
            return gateway_json(base, method, path, body, token, {"Idempotency-Key": key or str(uuid.uuid4())})

        status, address = request("POST", "/api/buyers/me/addresses", {"recipient": "test", "phone": "010-0000-0000", "postalCode": "00000", "addressLine1": "test", "addressLine2": "", "isDefault": True})
        assert status == 201, (status, address)
        body = {"productId": product, "quantity": 1, "eventId": None, "buyerCouponId": None, "mileageAmount": 0,
                "addressId": address["id"], "expectedProductVersion": 0, "expectedEventVersion": None,
                "expectedTotalAmount": 1000, "externalMethod": "CREDIT_CARD", "methodAccountRef": "test-card"}
        order_key = str(uuid.uuid4())
        status, order = request("POST", "/api/orders", body, order_key)
        assert status == 201, (status, order)
        assert sql(f"select count(*) from commerce.orders where id='{order['id']}'") == "1"
        assert sql(f"select count(*) from commerce.outbox where aggregate_id='{order['id']}' and event_type='OrderPaymentRequested'") == "1"

        compose("stop", "data-storage-metrics")
        until(lambda: gate(0), "collection outage did not close admission", 100)
        until(lambda: not query('financial:store_value'), "stopped collector retained valid observations", 30)
        def observation_alert():
            alerts = json.loads(compose("exec", "-T", "gateway", "curl", "--fail", "--silent", "http://alertmanager:9093/api/v2/alerts"))
            return any(alert["labels"]["alertname"] == "FinancialObservationUnavailable" for alert in alerts)
        until(observation_alert, "Prometheus did not deliver the collection failure to Alertmanager", 100)
        status, rejected = request("POST", "/api/orders", body)
        assert status == 503 and rejected["code"] == "COMMON_CAPACITY_EXCEEDED", (status, rejected)
        assert sql(f"select count(*) from commerce.orders where buyer_id='{buyer}'") == "1"
        status, replay = request("POST", "/api/orders", body, order_key)
        assert status == 201 and replay["id"] == order["id"], (status, replay)
        assert request("GET", "/api/orders/" + order["id"])[0] == 200
        status, cancelled = request("POST", "/api/orders/" + order["id"] + "/cancellation", {"expectedVersion": order["version"], "reason": "test"})
        assert status == 202 and cancelled["status"] == "CANCEL_PENDING", (status, cancelled)

        restarted = time.monotonic()
        compose("start", "data-storage-metrics")
        until(lambda: gate(1), "collector recovery did not reopen admission", 180)
        second_recovery = time.monotonic() - restarted
        assert second_recovery >= 60, second_recovery
        status, recovered = request("POST", "/api/orders", body)
        assert status == 201, (status, recovered)
        print(json.dumps({"result": "PASS", "firstRecoverySeconds": first_recovery,
                          "secondRecoverySeconds": second_recovery, "newOrder": 201,
                          "outageNewOrder": 503, "replay": 201, "read": 200, "cancellation": 202,
                          "scope": "actual owner collectors, Kafka, mounted data filesystems, Prometheus, gateway and order DB; financial SAGA is separate"}), flush=True)


if __name__ == "__main__":
    def stop(*_args):
        raise SystemExit(143)

    signal.signal(signal.SIGTERM, stop)
    verify()
