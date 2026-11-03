"""Real Compose: order -> payment -> settlement -> refund SAGA -> COMPLETED, and a Settlement outage.

Owns a fresh Compose project like test_admission.py and removes only its resources. PG approval and the
external refund are the in-service stubs; finance-mock refunds, payouts and notifications are separate.
"""

import hashlib
import json
from pathlib import Path
import secrets
import signal
import time
from urllib.parse import urlencode
from urllib.request import urlopen
import uuid

from smoke import gateway_json
from test_admission import prepare_offsets, stack, until


ROOT = Path(__file__).resolve().parents[1]
FLOW = ROOT / "compose.financial-flow.yml"
APPS = ["commerce", "payment-worker", "settlement"]
PRICE, MILEAGE = 1000, 300


def verify():
    with stack("cs-financial-flow-") as compose:
        def flow(*args, **kwargs):
            return compose("-f", str(FLOW), *args, **kwargs)

        def endpoint(service, port):
            return "http://" + compose("port", service, str(port)).strip()

        base = endpoint("gateway", 8080)
        prometheus = endpoint("prometheus", 9090)

        def gate_open():
            with urlopen(prometheus + "/api/v1/query?" + urlencode({"query": "commerce_order_admission_open"}), timeout=5) as response:
                rows = json.load(response)["data"]["result"]
            return rows and all(float(row["value"][1]) == 1 for row in rows)

        def sql(database, statement):
            return compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database, "-v", "ON_ERROR_STOP=1",
                           "-Atq", input_text=statement).strip()

        # Producers created their topics with every consumer disabled; prepare offsets, then enable the flow.
        prepare_offsets(compose)
        flow("up", "-d", "--wait", "--wait-timeout", "300", *APPS, timeout=600)
        until(gate_open, "order admission did not open with the financial flow enabled", 300)

        buyer, seller, product = [str(uuid.uuid4()) for _ in range(3)]
        sql("commerce", f"""
INSERT INTO commerce.buyer_accounts (id,email_normalized,password_hash,display_name,status,created_at,updated_at,version)
VALUES ('{buyer}','{buyer}@flow.test','test','test','ACTIVE',now(),now(),0);
INSERT INTO commerce.seller_accounts (id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version)
VALUES ('{seller}','{seller}@flow.test','test','test','ACTIVE','test-account',now(),now(),0);
INSERT INTO commerce.products (id,seller_id,name,unit_price,status,created_at,updated_at,version)
VALUES ('{product}','{seller}','financial flow test',{PRICE},'ON_SALE',now(),now(),0);
INSERT INTO commerce.product_inventory (id,product_id,available_qty,reserved_qty,event_allocated_qty,sold_qty,created_at,updated_at,version)
VALUES ('{uuid.uuid4()}','{product}',10,0,0,0,now(),now(),0);
""")
        # Mileage is owned by Payment; two orders each use MILEAGE.
        sql("payment", f"""
INSERT INTO payment.mileage_wallets (id,buyer_id,balance,reserved_amount,created_at,updated_at,version)
VALUES ('{uuid.uuid4()}','{buyer}',{2 * MILEAGE},0,now(),now(),0);
""")

        epoch = json.loads(compose("config", "--format", "json"))["services"]["gateway"]["environment"]["AUTH_SESSION_EPOCH"]
        token = secrets.token_urlsafe(32)

        def redis(*args):
            return compose("exec", "-T", "redis", "sh", "-c", 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"',
                           "flow", *args)

        seconds, micros = redis("TIME").split()
        now = int(seconds) * 1000 + int(micros) // 1000
        redis("HSET", f"commerce-settle:auth:session:{epoch}:{hashlib.sha256(token.encode()).hexdigest()}",
              "actorKind", "BUYER", "actorId", buyer, "purpose", "SESSION", "authVersion", "0",
              "issuedAt", str(now), "expiresAt", str(now + 3_600_000))
        redis("HSET", f"commerce-settle:auth:subject:{epoch}:BUYER:{buyer}", "authVersion", "0", "state", "ACTIVE")

        def request(method, path, body=None):
            return gateway_json(base, method, path, body, token, {"Idempotency-Key": str(uuid.uuid4())})

        status, address = request("POST", "/api/buyers/me/addresses", {
            "recipient": "test", "phone": "010-0000-0000", "postalCode": "00000",
            "addressLine1": "test", "addressLine2": "", "isDefault": True})
        assert status == 201, (status, address)

        def paid_order():
            status, order = request("POST", "/api/orders", {
                "productId": product, "quantity": 1, "eventId": None, "buyerCouponId": None, "mileageAmount": MILEAGE,
                "addressId": address["id"], "expectedProductVersion": 0, "expectedEventVersion": None,
                "expectedTotalAmount": PRICE, "externalMethod": "CREDIT_CARD", "methodAccountRef": "test-card"})
            assert status == 201, (status, order)
            until(lambda: sql("commerce", f"select status from commerce.orders where id='{order['id']}'") == "PAID",
                  "order was not paid through Payment")
            payment = sql("commerce", f"select payment_id from commerce.orders where id='{order['id']}'")
            until(lambda: sql("settlement", f"select count(*) from settlement.settlement_items where payment_id='{payment}'") == "1",
                  "Settlement did not create the item from PaymentConfirmed")
            return order["id"], payment

        def cancel(order_id):
            status, order = request("GET", "/api/orders/" + order_id)
            assert status == 200, (status, order)
            status, refund = request("POST", f"/api/orders/{order_id}/refund-requests",
                                     {"kind": "CANCEL", "reason": "financial flow test", "expectedVersion": order["version"]})
            assert status == 201, (status, refund)

        def inventory():
            return sql("commerce", f"""select available_qty || '/' || reserved_qty || '/' || sold_qty
from commerce.product_inventory where product_id='{product}'""")

        def reservation(order_id):
            return sql("commerce", f"select status from commerce.inventory_reservations where order_id='{order_id}'")

        def refund_state(payment):
            return sql("payment", f"""
select r.status || '/' || s.step from payment.refunds r join payment.refund_sagas s on s.refund_id = r.id
where r.payment_id='{payment}'""")

        def completed(order_id, payment):
            until(lambda: refund_state(payment) == "COMPLETED/COMPLETED", "refund SAGA did not complete", 240)
            until(lambda: sql("commerce", f"select status from commerce.orders where id='{order_id}'") == "REFUNDED",
                  "Commerce did not restore the refunded order")
            # The order's own reservation ends restored, so a matching total cannot hide another order's stock.
            assert reservation(order_id) == "RESTORED", reservation(order_id)
            refund = sql("payment", f"select id || '/' || external_amount || '/' || mileage_amount from payment.refunds where payment_id='{payment}'")
            refund_id, external, mileage = refund.split("/")
            assert (int(external), int(mileage)) == (PRICE - MILEAGE, MILEAGE), refund
            assert sql("payment", f"select count(*) from payment.payment_jobs where refund_id='{refund_id}' and operation='REFUND'") == "1"
            assert sql("payment", f"""select count(*) || '/' || coalesce(sum(amount),0) from payment.mileage_entries
where kind='RESTORE' and payment_id='{payment}'""") == f"1/{MILEAGE}"
            assert sql("settlement", f"select status from settlement.settlement_items where payment_id='{payment}'") == "REFUNDED"
            assert sql("settlement", f"""select string_agg(r.status, ',') from settlement.settlement_reservations r
join settlement.settlement_items i on i.id = r.item_id where i.payment_id='{payment}' and r.kind='REFUND'""") == "CONSUMED"
            assert sql("settlement", f"select sum(amount) from settlement.settlement_entries where payment_id='{payment}'") == "0"
            return refund_id

        try:
            first_refund, second_refund = scenarios(paid_order, cancel, refund_state, completed, inventory, reservation,
                                                    compose)
        except BaseException:
            print(compose("logs", "--no-color", "--tail=120", *APPS), flush=True)
            raise

        for database in ["commerce", "payment", "settlement"]:
            assert sql(database, f"select count(*) from {database}.inbox where status='REVIEW_REQUIRED'") == "0", database
            until(lambda: sql(database, f"select count(*) from {database}.outbox where status<>'PUBLISHED'") == "0",
                  database + " outbox was not drained", 60)
        assert sql("payment", f"select balance || '/' || reserved_amount from payment.mileage_wallets where buyer_id='{buyer}'") == f"{2 * MILEAGE}/0"
        assert inventory() == "10/0/0", inventory()
        print(json.dumps({"result": "PASS", "refunds": [first_refund, second_refund],
                          "externalRefund": PRICE - MILEAGE, "mileageRestored": MILEAGE,
                          "scope": "actual Commerce, Payment and Settlement over Kafka/Inbox/Outbox with stub PG and external refund"}),
              flush=True)


def scenarios(paid_order, cancel, refund_state, completed, inventory, reservation, compose):
    # 1. Normal round trip.
    first_order, first_payment = paid_order()
    cancel(first_order)
    first_refund = completed(first_order, first_payment)

    # 2. Without Settlement the refund waits for the reservation; after a restart the same refund completes once.
    second_order, second_payment = paid_order()
    compose("stop", "settlement")
    cancel(second_order)
    until(lambda: refund_state(second_payment) == "RESERVING/RESERVATION_REQUESTED", "refund was not accepted by Payment")
    time.sleep(15)
    assert refund_state(second_payment) == "RESERVING/RESERVATION_REQUESTED", refund_state(second_payment)
    # Commerce restores stock only after the refund completes, not when the cancellation is accepted.
    assert inventory() == "9/0/1", inventory()
    assert reservation(second_order) == "CONSUMED", reservation(second_order)
    compose("start", "settlement")
    second_refund = completed(second_order, second_payment)
    return first_refund, second_refund


if __name__ == "__main__":
    def stop(*_args):
        raise SystemExit(143)

    signal.signal(signal.SIGTERM, stop)
    verify()
