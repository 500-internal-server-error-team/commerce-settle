"""Real Compose: order -> payment -> settlement -> refund SAGA -> COMPLETED, a Settlement outage, and shipped returns.

Owns a fresh Compose project and removes only its resources. Approval/refund use Finance Mock HTTP;
Commerce, Payment and Settlement use real PostgreSQL/Kafka. Payouts/notifications are separate.
"""

import hashlib
import json
from pathlib import Path
import secrets
import signal
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import uuid

from smoke import gateway_json
from test_admission import prepare_offsets, stack, until


ROOT = Path(__file__).resolve().parents[1]
FLOW = ROOT / "compose.financial-flow.yml"
APPS = ["commerce", "payment-worker", "settlement"]
PRICE, MILEAGE = 1000, 300


def verify():
    with stack("cs-financial-flow-", memory_storage=True, commerce_outbox=False) as compose:
        def flow(*args, **kwargs):
            return compose("-f", str(FLOW), *args, **kwargs)

        def endpoint(service, port):
            return "http://" + compose("port", service, str(port)).strip()

        base = endpoint("gateway", 8080)
        prometheus = endpoint("prometheus", 9090)
        zipkin = endpoint("zipkin", 9411)
        order_traces, refund_traces = {}, {}

        def trace_headers(trace_id):
            return {"traceparent": f"00-{trace_id}-{uuid.uuid4().hex[:16]}-01"}

        def observed_trace(trace_id, required, services):
            def collected():
                try:
                    with urlopen(zipkin + "/api/v2/trace/" + trace_id, timeout=5) as response:
                        spans = json.load(response)
                except HTTPError as error:
                    if error.code == 404:
                        return None
                    raise
                names = {span.get("name") for span in spans}
                sources = {span.get("localEndpoint", {}).get("serviceName") for span in spans}
                ids = {span["id"] for span in spans}
                work = [span for span in spans if span.get("name") in required]
                if not required <= names or not services <= sources:
                    return None
                # Every selected durable-work parent is an actual exported span, not a new root.
                if any(span.get("parentId") not in ids for span in work):
                    return None
                assert all(span["traceId"] == trace_id for span in spans)
                by_id = {span["id"]: span for span in spans}

                def key(span):
                    return (span.get("localEndpoint", {}).get("serviceName"), span.get("name"))

                def ancestor_matches(span, expected):
                    seen = {span["id"]}
                    parent_id = span.get("parentId")
                    while parent_id in by_id and parent_id not in seen:
                        parent = by_id[parent_id]
                        if key(parent) in expected:
                            return True
                        seen.add(parent_id)
                        parent_id = parent.get("parentId")
                    return False

                # Check the actual runtime composition, including Kafka spans between saved boundaries.
                ancestry = {
                    ("payment", "payment.inbox.process"): {
                        ("commerce", "commerce.outbox.send"), ("settlement", "settlement.outbox.send")},
                    ("payment", "payment.approval.process"): {("payment", "payment.inbox.process")},
                    ("payment", "payment.refund.process"): {("payment", "payment.inbox.process")},
                    ("commerce", "commerce.inbox.accepted"): {("payment", "payment.outbox.send")},
                    ("commerce", "commerce.inbox.result"): {("payment", "payment.outbox.send")},
                    ("settlement", "settlement.inbox.process"): {
                        ("payment", "payment.outbox.send"), ("commerce", "commerce.outbox.send")},
                }
                if any(key(span) in ancestry and not ancestor_matches(span, ancestry[key(span)]) for span in spans):
                    return None
                return spans
            spans = until(collected, "missing sampled async trace boundaries: " + ",".join(sorted(required)), 60)
            print(json.dumps({"traceId": trace_id, "spanCount": len(spans),
                              "asyncBoundaries": sorted(required)}), flush=True)
            return spans

        def gate_open():
            with urlopen(prometheus + "/api/v1/query?" + urlencode({"query": "commerce_order_admission_open"}), timeout=5) as response:
                rows = json.load(response)["data"]["result"]
            return rows and all(float(row["value"][1]) == 1 for row in rows)

        def sql(database, statement):
            return compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database, "-v", "ON_ERROR_STOP=1",
                           "-Atq", input_text=statement).strip()

        # Follow the first-run procedure: base stack, explicit offsets, then the financial overlay.
        assert json.loads(compose("config", "--format", "json"))["services"]["commerce"]["environment"]["APP_OUTBOX_ENABLED"] == "false"
        prepare_offsets(compose)
        # Publish the mock only on a random loopback port in this owned test project.
        flow("-f", "-", "up", "-d", "--wait", "--wait-timeout", "300", *APPS, "finance-mock",
             input_text=json.dumps({"services": {"finance-mock": {"ports": ["127.0.0.1::8080", "127.0.0.1::9091"]}}}), timeout=600)
        finance = endpoint("finance-mock", 8080)
        finance_metrics = endpoint("finance-mock", 9091)
        credentials = json.loads(compose("config", "--format", "json"))["services"]["payment-worker"]["environment"]
        payment_credential = credentials["FINANCE_MOCK_PAYMENT_CREDENTIAL"]

        def mock_request(method, path, body=None, credential=payment_credential):
            headers = {"Content-Type": "application/json", "Idempotency-Key": "flow-" + str(uuid.uuid4())}
            if credential is not None:
                headers["Authorization"] = "Bearer " + credential
            req = Request(finance + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
            try:
                with urlopen(req, timeout=20) as response:
                    return response.status, json.load(response)
            except HTTPError as error:
                return error.code, error.read().decode()

        def mock_calls(method=None, status=None):
            with urlopen(finance_metrics + "/actuator/prometheus", timeout=5) as response:
                lines = response.read().decode().splitlines()
            return sum(float(line.rsplit(" ", 1)[1]) for line in lines
                       if line.startswith("http_server_requests_seconds_count{") and 'uri="/mock/providers/' in line
                       and (method is None or f'method="{method}"' in line)
                       and (status is None or f'status="{status}"' in line))

        def worker_settings(version="fixture-pg-normal-v1", **extra):
            environment = {"PAYMENT_SCENARIO_VERSION": version, **extra}
            flow("-f", "-", "up", "-d", "--no-deps", "--wait", "payment-worker",
                 input_text=json.dumps({"services": {"payment-worker": {"environment": environment}}}), timeout=180)

        # Immutable delay scenarios: one loses responses, one returns a late success for fencing.
        for version, loss in [("flow-delayed-v1", 1), ("flow-late-success-v1", 0)]:
            sql("finance_mock", f"""
INSERT INTO finance_mock.mock_scenarios
SELECT '{uuid.uuid4()}',settlement_delay_business_days,provider_id,'{version}',
min_delay_ms,max_delay_ms,rejection_rate,response_loss_rate,lookup_failure_rate,
7000,7000,0,{loss},lookup_min_delay_ms,lookup_max_delay_ms,seed,created_at
FROM finance_mock.mock_scenarios WHERE provider_id='PG_A' AND scenario_version='fixture-pg-normal-v1';
""")
        missing = f"/mock/providers/PG_A/transactions/{uuid.uuid4()}?operation=REFUND&scenarioVersion=fixture-pg-normal-v1"
        assert mock_request("GET", missing, credential=None)[0] == 401
        settlement_credential = json.loads(compose("config", "--format", "json"))["services"]["settlement"]["environment"]["FINANCE_MOCK_SETTLEMENT_CREDENTIAL"]
        assert mock_request("GET", missing, credential=settlement_credential)[0] == 403
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
        # Mileage belongs to Payment; every successful refund restores its original consumption.
        sql("payment", f"""
INSERT INTO payment.mileage_wallets (id,buyer_id,balance,reserved_amount,created_at,updated_at,version)
VALUES ('{uuid.uuid4()}','{buyer}',{10_000},0,now(),now(),0);
""")

        epoch = json.loads(compose("config", "--format", "json"))["services"]["gateway"]["environment"]["AUTH_SESSION_EPOCH"]

        def redis(*args):
            return compose("exec", "-T", "redis", "sh", "-c", 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"',
                           "flow", *args)

        def session(kind, actor):
            token = secrets.token_urlsafe(32)
            seconds, micros = redis("TIME").split()
            now = int(seconds) * 1000 + int(micros) // 1000
            redis("HSET", f"commerce-settle:auth:session:{epoch}:{hashlib.sha256(token.encode()).hexdigest()}",
                  "actorKind", kind, "actorId", actor, "purpose", "SESSION", "authVersion", "0",
                  "issuedAt", str(now), "expiresAt", str(now + 3_600_000))
            redis("HSET", f"commerce-settle:auth:subject:{epoch}:{kind}:{actor}", "authVersion", "0", "state", "ACTIVE")
            return token

        token = session("BUYER", buyer)
        seller_token = session("SELLER", seller)

        def request(method, path, body=None, actor_token=None):
            trace_id = uuid.uuid4().hex
            result = gateway_json(base, method, path, body, actor_token or token,
                                  {"Idempotency-Key": str(uuid.uuid4()), **trace_headers(trace_id)})
            if method == "POST" and path.startswith("/api/orders/") and path.endswith("/refund-requests") and result[0] == 201:
                refund_traces[path.split("/")[3]] = trace_id
            return result

        status, address = request("POST", "/api/buyers/me/addresses", {
            "recipient": "test", "phone": "010-0000-0000", "postalCode": "00000",
            "addressLine1": "test", "addressLine2": "", "isDefault": True})
        assert status == 201, (status, address)

        def paid_order(mileage=MILEAGE, method="CREDIT_CARD", account="test-card", *, recover_admission=True):
            body = {
                "productId": product, "quantity": 1, "eventId": None, "buyerCouponId": None, "mileageAmount": mileage,
                "addressId": address["id"], "expectedProductVersion": 0, "expectedEventVersion": None,
                "expectedTotalAmount": PRICE, "externalMethod": method if mileage < PRICE else None,
                "methodAccountRef": account if mileage < PRICE else None}
            key = str(uuid.uuid4())
            trace_id = uuid.uuid4().hex
            headers = {"Idempotency-Key": key, **trace_headers(trace_id)}

            def create_when_admitted():
                if recover_admission and not gate_open():
                    return None
                # The scraped gauge can lag an admission transition. Retry only this explicit rejection with the same key.
                status, result = gateway_json(base, "POST", "/api/orders", body, token, headers)
                if recover_admission and status == 503 and result.get("code") == "COMMON_CAPACITY_EXCEEDED":
                    return None
                assert status == 201, (status, result)
                return result

            order = until(create_when_admitted, "order was not admitted within the recovery budget", 300)
            until(lambda: sql("commerce", f"select status from commerce.orders where id='{order['id']}'") == "PAID",
                  "order was not paid through Payment")
            payment = sql("commerce", f"select payment_id from commerce.orders where id='{order['id']}'")
            until(lambda: sql("settlement", f"select count(*) from settlement.settlement_items where payment_id='{payment}'") == "1",
                  "Settlement did not create the item from PaymentConfirmed")
            order_traces[order["id"]] = trace_id
            required = {"commerce.outbox.send", "payment.inbox.process", "payment.outbox.send",
                        "commerce.inbox.accepted", "commerce.inbox.result"}
            services = {"gateway", "commerce", "payment", "settlement"}
            if mileage < PRICE:
                required.add("payment.approval.process")
                services.add("finance-mock")
            observed_trace(trace_id, required, services)
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

        def completed(order_id, payment, expected_mileage=MILEAGE, stock="RESTORED"):
            until(lambda: refund_state(payment) == "COMPLETED/COMPLETED", "refund SAGA did not complete", 240)
            until(lambda: sql("commerce", f"select status from commerce.orders where id='{order_id}'") == "REFUNDED",
                  "Commerce did not restore the refunded order")
            # The order's own reservation ends restored, so a matching total cannot hide another order's stock.
            # A return's stock follows the seller's receipt, not the refund, so its caller checks it.
            if stock is not None:
                assert reservation(order_id) == stock, reservation(order_id)
            refund = sql("payment", f"select id || '/' || external_amount || '/' || mileage_amount from payment.refunds where payment_id='{payment}'")
            refund_id, external, mileage = refund.split("/")
            assert (int(external), int(mileage)) == (PRICE - expected_mileage, expected_mileage), refund
            assert sql("payment", f"select count(*) from payment.payment_jobs where refund_id='{refund_id}' and operation='REFUND'") == "1"
            assert sql("payment", f"""select count(*) || '/' || coalesce(sum(amount),0) from payment.mileage_entries
where kind='RESTORE' and payment_id='{payment}'""") == f"{1 if expected_mileage else 0}/{expected_mileage}"
            assert sql("settlement", f"select status from settlement.settlement_items where payment_id='{payment}'") == "REFUNDED"
            assert sql("settlement", f"""select string_agg(r.status, ',') from settlement.settlement_reservations r
join settlement.settlement_items i on i.id = r.item_id where i.payment_id='{payment}' and r.kind='REFUND'""") == "CONSUMED"
            assert sql("settlement", f"select sum(amount) from settlement.settlement_entries where payment_id='{payment}'") == "0"
            assert sql("settlement", f"""select count(*) from settlement.settlement_entries reversal
join settlement.settlement_entries original on original.id=reversal.reversal_of
where reversal.payment_id='{payment}' and reversal.amount=-original.amount
and reversal.leg=original.leg and reversal.policy_version=original.policy_version""") == "5"
            assert sql("settlement", f"select count(*) from settlement.settlement_entries where payment_id='{payment}'") == "10"
            assert sql("payment", f"select count(*) from payment.outbox where aggregate_id='{payment}' and event_type='RefundFinancialCompleted'") == "1"
            if int(external):
                refund_key = sql("payment", f"select business_key from payment.refunds where id='{refund_id}'")
                assert sql("finance_mock", f"""select count(*) from finance_mock.mock_transactions r
join finance_mock.mock_transactions a on a.id=r.original_id
where r.business_key='{refund_key}'::uuid
and r.operation='REFUND' and a.operation='APPROVE' and r.status='SUCCEEDED'
and r.amount={int(external)} and r.amount=a.amount and r.provider_id=a.provider_id
and r.scenario_version=a.scenario_version and a.pg_received_amount in (0,a.amount)""") == "1"
            spans = observed_trace(refund_traces[order_id],
                                   {"commerce.outbox.send", "payment.inbox.process", "payment.refund.process",
                                    "payment.outbox.send", "settlement.outbox.send", "commerce.inbox.result"},
                                   {"gateway", "commerce", "payment", "settlement"} | ({"finance-mock"} if int(external) else set()))
            if int(sql("payment", f"select attempts from payment.payment_jobs where refund_id='{refund_id}'")) > 1:
                assert any(span.get("name") == "payment.refund.process.wait"
                           and span.get("tags", {}).get("kind") in {"retry", "lease-recovery"} for span in spans), "retry/restart wait span missing"
            print(json.dumps({"completed": refund_id, "external": int(external), "mileage": expected_mileage}), flush=True)
            return refund_id

        try:
            first_refund, second_refund = scenarios(paid_order, cancel, refund_state, completed, inventory, reservation,
                                                    compose, gate_open)
            # Card, all-mileage (including zero external HTTP calls), and external EASY_PAY wallet.
            card_order, card_payment = paid_order(0)
            cancel(card_order)
            completed(card_order, card_payment, 0)
            before_calls = mock_calls()
            mileage_order, mileage_payment = paid_order(PRICE)
            cancel(mileage_order)
            completed(mileage_order, mileage_payment, PRICE)
            assert mock_calls() == before_calls, "E=0 made an external HTTP call"
            easy_order, easy_payment = paid_order(MILEAGE, "EASY_PAY", "fixture-easy-wallet")
            cancel(easy_order)
            completed(easy_order, easy_payment)
            assert sql("finance_mock", "select balance from finance_mock.mock_accounts where account_ref='fixture-easy-wallet'") == "1000000"

            # Move only the owned approval's due date; the real receipt scheduler creates cash before refund.
            cash_order, cash_payment = paid_order()
            approval = sql("payment", f"select external_ref from payment.payment_attempts where payment_id='{cash_payment}' and status='APPROVED'")
            sql("finance_mock", f"update finance_mock.mock_transactions set pg_receipt_due_at=now()-interval '1s' where id='{approval}'")
            until(lambda: sql("finance_mock", f"select count(*) from finance_mock.mock_cash_entries where transaction_id='{approval}' and kind='PG_RECEIPT'") == "1",
                  "real PG receipt scheduler did not receive cash", 100)
            cancel(cash_order)
            cash_refund = completed(cash_order, cash_payment)
            cash_external = sql("payment", f"select external_ref from payment.refunds where id='{cash_refund}'")
            assert sql("finance_mock", f"select amount from finance_mock.mock_cash_entries where transaction_id='{cash_external}' and kind='REFUND_DEBIT'") == str(-(PRICE-MILEAGE))

            def refund_info(payment):
                return sql("payment", f"select id || '/' || business_key from payment.refunds where payment_id='{payment}'").split("/")

            def reserved(payment):
                return sql("settlement", f"select r.status from settlement.settlement_reservations r join settlement.settlement_items i on i.id=r.item_id where i.payment_id='{payment}' and r.kind='REFUND'")

            def external_count(key):
                return sql("finance_mock", f"select count(*) from finance_mock.mock_transactions where operation='REFUND' and business_key='{key}'")

            # Timeout/response loss plus an unavailable lookup endpoint must keep reservation and mileage.
            worker_settings("flow-delayed-v1", APP_REFUND_HTTP_TIMEOUT="1s")
            lost_order, lost_payment = paid_order()
            cancel(lost_order)
            until(lambda: sql("payment", f"select count(*) from payment.refunds where payment_id='{lost_payment}'") == "1", "refund not accepted")
            lost_refund, lost_key = refund_info(lost_payment)
            until(lambda: external_count(lost_key) == "1", "external refund not committed")
            compose("pause", "finance-mock")
            try:
                until(lambda: refund_state(lost_payment) == "UNKNOWN/EXTERNAL_REFUND_PENDING", "timeout was not UNKNOWN")
                until(lambda: sql("payment", f"select count(*) from payment.payment_jobs where refund_id='{lost_refund}' and attempts>=2 and status='RETRY'") == "1",
                      "unavailable lookup result was not recorded as a retry")
                assert reserved(lost_payment) == "ACTIVE"
                assert sql("payment", f"select count(*) from payment.mileage_entries where payment_id='{lost_payment}' and kind='RESTORE'") == "0"
            finally:
                compose("unpause", "finance-mock")
            completed(lost_order, lost_payment)
            assert external_count(lost_key) == "1"

            # External success, then SIGKILL before local recording; a new worker queries the old key.
            worker_settings("flow-delayed-v1", APP_REFUND_HTTP_TIMEOUT="15s")
            crash_order, crash_payment = paid_order()
            cancel(crash_order)
            until(lambda: sql("payment", f"select count(*) from payment.refunds where payment_id='{crash_payment}'") == "1", "refund not accepted")
            crash_refund, crash_key = refund_info(crash_payment)
            until(lambda: external_count(crash_key) == "1", "external refund not committed before crash")
            compose("kill", "-s", "SIGKILL", "payment-worker")
            assert reserved(crash_payment) == "ACTIVE"
            assert sql("payment", f"select count(*) from payment.mileage_entries where payment_id='{crash_payment}' and kind='RESTORE'") == "0"
            sql("payment", f"update payment.payment_jobs set lease_until=now()-interval '1s' where refund_id='{crash_refund}'")
            worker_settings()
            completed(crash_order, crash_payment)
            assert external_count(crash_key) == "1"
            assert int(sql("payment", f"select attempts from payment.payment_jobs where refund_id='{crash_refund}'")) >= 2

            # Fence a late SUCCEEDED response. Keep the replacement lease alive until the old call is rejected.
            worker_settings("flow-late-success-v1", APP_REFUND_HTTP_TIMEOUT="15s")
            late_order, late_payment = paid_order()
            cancel(late_order)
            until(lambda: sql("payment", f"select count(*) from payment.refunds where payment_id='{late_payment}'") == "1", "refund not accepted")
            late_refund, late_key = refund_info(late_payment)
            until(lambda: external_count(late_key) == "1", "external refund not committed before fencing")
            late_job = sql("payment", f"select id from payment.payment_jobs where refund_id='{late_refund}'")
            sql("payment", f"update payment.payment_jobs set lease_token='{uuid.uuid4()}',lease_until=now()+interval '60s' where refund_id='{late_refund}'")
            until(lambda: any('Refund result was not recorded because the lease was lost' in line for line in
                             compose("logs", "--no-color", "--no-log-prefix", "--since", "30s", "payment-worker").splitlines()),
                  "late success was not rejected by the old lease token", 30)
            assert sql("payment", f"select count(*) from payment.mileage_entries where payment_id='{late_payment}' and kind='RESTORE'") == "0"
            assert sql("payment", f"select count(*) from payment.outbox where aggregate_id='{late_payment}' and event_type='RefundFinancialCompleted'") == "0"
            sql("payment", f"update payment.payment_jobs set lease_until=now()-interval '1s' where id='{late_job}'")
            completed(late_order, late_payment)
            assert external_count(late_key) == "1"
            assert int(sql("payment", f"select attempts from payment.payment_jobs where refund_id='{late_refund}'")) >= 2

            # Crash-before-POST fixture: REQUESTED is durable but the mock has no transaction.
            worker_settings(APP_REFUND_ENABLED="false")
            absent_order, absent_payment = paid_order()
            cancel(absent_order)
            until(lambda: refund_state(absent_payment) == "EXTERNAL_PENDING/EXTERNAL_REFUND_PENDING", "reservation not ready")
            absent_refund, absent_key = refund_info(absent_payment)
            sql("payment", f"update payment.refunds set external_status='REQUESTED' where id='{absent_refund}'")
            before_missing = mock_calls("GET", "404")
            worker_settings()
            completed(absent_order, absent_payment)
            assert mock_calls("GET", "404") > before_missing
            assert external_count(absent_key) == "1"

            # PROCESSING is an explicit DB response fixture; Finance Mock normally commits a terminal result.
            worker_settings(APP_REFUND_ENABLED="false")
            processing_order, processing_payment = paid_order()
            cancel(processing_order)
            until(lambda: refund_state(processing_payment) == "EXTERNAL_PENDING/EXTERNAL_REFUND_PENDING", "reservation not ready")
            processing_refund, processing_key = refund_info(processing_payment)
            original = sql("payment", f"select external_ref from payment.payment_attempts where payment_id='{processing_payment}' and status='APPROVED'")
            path = f"/mock/providers/PG_A/payments/{original}/refunds"
            payload = {"businessKey": processing_key, "amount": PRICE-MILEAGE}
            status, external = mock_request("POST", path, payload)
            assert status == 202, (status, external)
            sql("finance_mock", f"update finance_mock.mock_transactions set status='PROCESSING',succeeded_at=null where id='{external['id']}'")
            sql("payment", f"update payment.refunds set external_status='REQUESTED' where id='{processing_refund}'")
            posts_before = mock_calls("POST")
            worker_settings()
            until(lambda: refund_state(processing_payment) == "UNKNOWN/EXTERNAL_REFUND_PENDING", "PROCESSING was not retained as UNKNOWN")
            assert reserved(processing_payment) == "ACTIVE"
            assert mock_calls("POST") == posts_before
            sql("finance_mock", f"update finance_mock.mock_transactions set status='SUCCEEDED',succeeded_at='{external['succeededAt']}' where id='{external['id']}'")
            completed(processing_order, processing_payment)
            status, duplicate = mock_request("POST", path, payload)
            assert status == 202 and duplicate["id"] == external["id"]
            assert external_count(processing_key) == "1"
            assert sql("payment", f"select count(*) from payment.mileage_entries where payment_id='{processing_payment}' and kind='RESTORE'") == "1"

            # A confirmed external decline releases the actual Settlement reservation, with no mileage restore.
            worker_settings("fixture-pg-refund-reject-v1")
            rejected_order, rejected_payment = paid_order()
            cancel(rejected_order)
            until(lambda: refund_state(rejected_payment) == "DECLINED/FAILED", "external decline did not release reservation")
            assert reserved(rejected_payment) == "RELEASED"
            assert sql("payment", f"select count(*) from payment.mileage_entries where payment_id='{rejected_payment}' and kind='RESTORE'") == "0"
            assert sql("settlement", f"select count(*) from settlement.settlement_entries where payment_id='{rejected_payment}' and reversal_of is not null") == "0"
            assert reservation(rejected_order) == "CONSUMED"
            assert sql("finance_mock", "select balance from finance_mock.mock_accounts where account_ref='fixture-payout-funding'") == "1000000"
            assert sql("finance_mock", "select coalesce(sum(amount),0) from finance_mock.mock_cash_entries") == "0"
            print(json.dumps({"httpRefundCases": "card,mixed,mileage-only,easy-pay,receipt,timeout,lookup-unavailable,crash,late-token,absent,processing,declined",
                              "processingAndAbsence": "explicit isolated DB fixtures; transport is real HTTP"}), flush=True)

            worker_settings()
            returns(paid_order, request, seller_token, completed, inventory, reservation, compose)

            for database in ["commerce", "payment", "settlement"]:
                assert sql(database, f"select count(*) from {database}.inbox where status='REVIEW_REQUIRED'") == "0", database
                until(lambda: sql(database, f"select count(*) from {database}.outbox where status<>'PUBLISHED'") == "0",
                      database + " outbox was not drained", 60)
            assert sql("payment", f"select balance || '/' || reserved_amount from payment.mileage_wallets where buyer_id='{buyer}'") == f"{10_000-MILEAGE}/0"
            # The declined refund keeps one sale and the disposed return leaves stock without restoring it.
            assert inventory() == "8/0/1", inventory()
            print(json.dumps({"result": "PASS", "refunds": [first_refund, second_refund],
                              "externalRefund": PRICE - MILEAGE, "mileageRestored": MILEAGE,
                              "scope": "actual HTTP Finance Mock approval/refund and Commerce, Payment, Settlement over Kafka/Inbox/Outbox"}),
                  flush=True)
        except BaseException:
            print(compose("logs", "--no-color", "--tail=120", *APPS, "finance-mock"), flush=True)
            raise


def scenarios(paid_order, cancel, refund_state, completed, inventory, reservation, compose, gate_open):
    # 1. Normal round trip.
    first_order, first_payment = paid_order(recover_admission=False)

    # An unpublished first-order event closes admission after 60 seconds. Do not hide it with recovery retries.
    started = time.monotonic()
    while time.monotonic() - started < 65:
        assert gate_open(), "order admission closed after the first order"
        time.sleep(1)
    cancel(first_order)
    first_refund = completed(first_order, first_payment)

    # 2. Without Settlement the refund waits for the reservation; after a restart the same refund completes once.
    second_order, second_payment = paid_order(recover_admission=False)
    print(json.dumps({"orderAdmission": "two paid orders without capacity retries",
                      "elapsedSeconds": round(time.monotonic() - started, 1)}), flush=True)
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


def returns(paid_order, request, seller_token, completed, inventory, reservation, compose):
    def seller(method, path, body=None):
        return request(method, path, body, seller_token)

    def returned(order_id):
        status, view = seller("GET", "/api/sellers/me/orders/" + order_id)
        assert status == 200, (status, view)
        status, order = seller("POST", f"/api/sellers/me/orders/{order_id}/shipments",
                               {"expectedVersion": view["order"]["version"], "shipmentReference": "flow-" + order_id})
        assert status == 200, (status, order)
        status, order = request("GET", "/api/orders/" + order_id)
        assert status == 200, (status, order)
        status, refund = request("POST", f"/api/orders/{order_id}/refund-requests",
                                 {"kind": "RETURN", "reason": "financial flow return", "expectedVersion": order["version"]})
        assert status == 201 and refund["status"] == "PENDING_SELLER", (status, refund)
        status, refund = seller("POST", f"/api/sellers/me/refund-requests/{refund['id']}/decision",
                                {"decision": "APPROVE", "reason": "financial flow return", "expectedVersion": refund["version"]})
        assert status == 202, (status, refund)
        return refund["id"]

    def receive(refund_id, resalable):
        status, refund = request("GET", "/api/refund-requests/" + refund_id)
        assert status == 200, (status, refund)
        status, refund = seller("POST", f"/api/sellers/me/refund-requests/{refund_id}/receipt",
                                {"expectedVersion": refund["version"], "resalable": resalable})
        assert status == 200 and refund["receiptResalable"] is resalable, (status, refund)

    # 3. A resalable return: the refund completes without touching stock; the later receipt restores it once.
    before = inventory()
    resalable_order, resalable_payment = paid_order()
    resalable_refund = returned(resalable_order)
    completed(resalable_order, resalable_payment, stock="CONSUMED")
    receive(resalable_refund, True)
    assert reservation(resalable_order) == "RESTORED", reservation(resalable_order)
    assert inventory() == before, (inventory(), before)

    # 4. An unsellable return received while Settlement holds the refund: the sale is removed, stock is not added,
    # and the refund still completes afterwards.
    disposed_order, disposed_payment = paid_order()
    available, reserved, sold = map(int, inventory().split("/"))
    compose("stop", "settlement")
    disposed_refund = returned(disposed_order)
    # RefundAccepted still reaches Commerce without Settlement and bumps the version; receive after it to avoid a 409.
    until(lambda: request("GET", "/api/refund-requests/" + disposed_refund)[1]["status"] == "REFUNDING",
          "return was not linked to the refund")
    receive(disposed_refund, False)
    assert reservation(disposed_order) == "DISPOSED", reservation(disposed_order)
    assert inventory() == f"{available}/{reserved}/{sold - 1}", inventory()
    compose("start", "settlement")
    completed(disposed_order, disposed_payment, stock="DISPOSED")
    print(json.dumps({"returns": "resalable after refund, disposed before refund"}), flush=True)


if __name__ == "__main__":
    def stop(*_args):
        raise SystemExit(143)

    signal.signal(signal.SIGTERM, stop)
    verify()
