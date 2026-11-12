"""Real isolated DB/HTTP/Kafka reconciliation, including delivery wait and source outage.

Starts with a contract OrderPaymentRequested, executes the actual PG HTTP approval, Outbox relay,
and Settlement consumer. This is not an order API or refund/payout SAGA certification.
The ten-minute cutoff margin is actually awaited; immutable financial records are never rewritten.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import secrets
import tempfile
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid

from test_admission import prepare_offsets, stack, until
from reconcile import verify as reconcile


def verify():
    operator = secrets.token_urlsafe(32)
    finance_operator = secrets.token_urlsafe(32)
    with tempfile.TemporaryDirectory(prefix="cs-reconciliation-override-") as directory:
        settings = Path(directory) / "override.json"
        overrides = {"services": {
            "payment-api": {"ports": ["127.0.0.1::8080"]},
            "payment-worker": {"environment": {
                "PAYMENT_APPROVAL_GATEWAY": "http", "APP_PAYMENT_APPROVAL_ENABLED": "true",
                "PAYMENT_FUNDING_ACCOUNT_REF": "reconciliation-funding", "PAYMENT_SCENARIO_VERSION": "pg-reconciliation-v1",
                "APP_KAFKA_ORDER_PAYMENT_REQUESTED_ENABLED": "true",
                "APP_PAYMENT_REQUEST_PROCESSOR_ENABLED": "true", "APP_OUTBOX_ENABLED": "true"}},
            "settlement": {"ports": ["127.0.0.1::8080"], "environment": {
                "SETTLEMENT_RECONCILIATION_ENABLED": "true", "APP_KAFKA_SETTLEMENT_EVENTS_ENABLED": "false",
                "SPRING_APPLICATION_JSON": json.dumps({"app": {"internal-auth": {"senders": {"[operator:reconciliation]": {"current": operator}}}}})}},
            "finance-mock": {"ports": ["127.0.0.1::8080"], "environment": {
                "SPRING_APPLICATION_JSON": json.dumps({"app": {"internal-auth": {"senders": {"[operator:reconciliation]": {"current": finance_operator}}}}})}},
        }}
        settings.write_text(json.dumps(overrides))
        settings.chmod(0o600)
        with stack("cs-reconciliation-") as compose:
            def flow(*args, **kwargs):
                return compose("-f", str(settings), *args, **kwargs)

            def endpoint(service):
                return "http://" + flow("port", service, "8080").strip()

            def http(base, path, token, body=None):
                data = None if body is None else json.dumps(body).encode()
                headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json", "Idempotency-Key": "reconciliation-test-" + str(uuid.uuid4())}
                with urlopen(Request(base + path, data=data, headers=headers), timeout=10) as response:
                    return json.load(response)

            def sql(database, statement):
                return flow("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database,
                            "-v", "ON_ERROR_STOP=1", "-Atq", input_text=statement).strip()

            prepare_offsets(compose)
            flow("up", "-d", "--wait", "--wait-timeout", "300", "payment-api", "payment-worker", "settlement", "finance-mock", timeout=600)
            payment, settlement, finance = [endpoint(service) for service in ("payment-api", "settlement", "finance-mock")]
            config = json.loads(flow("config", "--format", "json"))["services"]
            payment_credential = config["settlement"]["environment"]["PAYMENT_SETTLEMENT_CREDENTIAL"]
            finance_credential = config["settlement"]["environment"]["FINANCE_MOCK_SETTLEMENT_CREDENTIAL"]
            http(finance, "/mock/accounts", finance_operator, {"providerId": "PAYOUT", "accountRef": "reconciliation-funding", "purpose": "PAYOUT", "balance": 1000000})
            http(finance, "/mock/scenarios", finance_operator, {
                "providerId": "PG_A", "scenarioVersion": "pg-reconciliation-v1", "minDelayMs": 0, "maxDelayMs": 0,
                "rejectionRate": 0, "responseLossRate": 0, "lookupFailureRate": 0, "seed": 149,
                "settlementDelayBusinessDays": 5, "refundMinDelayMs": 0, "refundMaxDelayMs": 0,
                "refundRejectionRate": 0, "refundResponseLossRate": 0, "lookupMinDelayMs": 0, "lookupMaxDelayMs": 0})
            now = datetime.now(timezone.utc)
            order, buyer, seller, cycle, product = [str(uuid.uuid4()) for _ in range(5)]
            event = {"eventId": str(uuid.uuid4()), "eventType": "OrderPaymentRequested", "eventVersion": 1,
                     "occurredAt": now.isoformat(), "aggregateId": order, "aggregateVersion": 1,
                     "payload": {"orderId": order, "buyerId": buyer, "sellerId": seller, "paymentCycleId": cycle,
                                 "productId": product, "orderVersion": 1, "status": "PAYMENT_PENDING", "policyVersion": "delivery-daily-v1",
                                 "paymentWaitUntil": (now + timedelta(minutes=30)).isoformat(), "externalMethod": "CREDIT_CARD",
                                 "methodAccountRef": "reconciliation-card", "grossAmount": 10000, "couponAmount": 0,
                                 "totalAmount": 10000, "mileageAmount": 0, "externalAmount": 10000,
                                 "sellerCouponAmount": 0, "platformCouponAmount": 0, "settlementBaseAmount": 10000}}
            flow("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092",
                 "--topic", "commerce-settle.commerce.v1", "--property", "parse.key=true",
                 input_text=order + "\t" + json.dumps(event) + "\n")
            until(lambda: sql("payment", f"SELECT count(*) FROM payment.payments WHERE order_id='{order}' AND status='CONFIRMED'") == "1", "actual PG approval did not complete")
            until(lambda: sql("payment", "SELECT count(*) FROM payment.outbox WHERE event_type='PaymentConfirmed' AND status='PUBLISHED'") == "1", "financial source event was not published")
            cutoff = datetime.now(timezone.utc)
            print("Actual PG HTTP approval and Kafka publication complete; waiting for the unchanged 10-minute cutoff margin.", flush=True)
            ready_at = time.monotonic() + 601
            while time.monotonic() < ready_at:
                time.sleep(min(1, ready_at - time.monotonic()))
            run = http(settlement, "/internal/settlement-reconciliations", operator, {"cutoffAt": cutoff.isoformat()})
            path = "/internal/settlement-reconciliations/" + run["id"]
            until(lambda: http(settlement, path, operator)["status"] == "WAITING", "delivery wait was not preserved")
            original = http(settlement, path, operator)
            results = http(settlement, path + "/results", operator)
            assert results["items"][0]["status"] == "WAIT_DELIVERY", results
            flow("stop", "payment-api")
            http(settlement, "/internal/settlement-reconciliations", operator, {"cutoffAt": cutoff.isoformat()})
            until(lambda: http(settlement, path, operator)["status"] == "UNVERIFIED", "source outage was mistaken for a match")
            overrides["services"]["settlement"]["environment"]["APP_KAFKA_SETTLEMENT_EVENTS_ENABLED"] = "true"
            settings.write_text(json.dumps(overrides))
            flow("up", "-d", "--wait", "--wait-timeout", "300", "payment-api", "settlement", timeout=600)
            # Host ephemeral ports may change when a container is recreated.
            payment, settlement = endpoint("payment-api"), endpoint("settlement")
            until(lambda: sql("settlement", "SELECT count(*) FROM settlement.settlement_items") == "1", "actual Settlement consumer did not apply the event")
            resumed = http(settlement, "/internal/settlement-reconciliations", operator, {"cutoffAt": cutoff.isoformat()})
            assert resumed["id"] == original["id"]
            until(lambda: http(settlement, path, operator)["status"] == "COMPLETED", "fixed reconciliation did not resume")
            assert http(settlement, path, operator)["snapshotAt"] == original["snapshotAt"]
            previous = {key: os.environ.get(key) for key in ("SETTLEMENT_OPERATOR_CREDENTIAL", "PAYMENT_SETTLEMENT_CREDENTIAL", "FINANCE_MOCK_SETTLEMENT_CREDENTIAL")}
            os.environ.update({"SETTLEMENT_OPERATOR_CREDENTIAL": operator, "PAYMENT_SETTLEMENT_CREDENTIAL": payment_credential, "FINANCE_MOCK_SETTLEMENT_CREDENTIAL": finance_credential})
            try:
                assert reconcile(argparse.Namespace(run_id=run["id"], cutoff_at=None, settlement_url=settlement, payment_url=payment, finance_url=finance, timeout=60)) == 0
            finally:
                for key, value in previous.items():
                    if value is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = value
            assert sql("settlement", "SELECT count(*) FROM settlement.settlement_entries") == "5"
            print("Real PostgreSQL/HTTP/Kafka: delivery wait, source outage, same snapshot resume, source owner checks passed.", flush=True)


if __name__ == "__main__":
    verify()
