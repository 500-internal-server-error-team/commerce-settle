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
from prepare_financial_kafka import SUBSCRIPTIONS, bounds, group_offsets, partitions
from test_admission import prepare_offsets, stack, until


ROOT = Path(__file__).resolve().parents[1]
FLOW = ROOT / "compose.financial-flow.yml"
APPS = ["commerce", "payment-worker", "settlement"]
PRICE, MILEAGE = 1000, 300


def timed(stage, action, *args, **kwargs):
    """Run one smoke stage and print its wall time so CI logs show where the job budget goes."""
    started = time.monotonic()
    try:
        return action(*args, **kwargs)
    finally:
        print(json.dumps({"stage": stage, "elapsedSeconds": round(time.monotonic() - started, 1)}), flush=True)


def wait_drained(compose, groups=("commerce.v1", "payment.v1"), seconds=60):
    # Fix the log end once after the replays, then poll only each group's committed position.
    ends = bounds(compose, partitions(compose), "latest")
    for group in groups:
        target = {key: end for key, end in ends.items() if key[0] in SUBSCRIPTIONS[group]}

        def consumed():
            offsets = group_offsets(compose, group)
            return all(offsets.get(key, 0) >= end for key, end in target.items())

        until(consumed, f"{group} did not consume the replayed records", seconds)


def observed_trace(zipkin, trace_id, required, services, *, retry_wait=False):
    last = {}
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
        last.update(missingNames=sorted(required - names), missingServices=sorted(services - sources))
        last["missingRetryWait"] = retry_wait and not any(
            span.get("name") == "payment.refund.process.wait"
            and span.get("tags", {}).get("kind") in {"retry", "lease-recovery"}
            for span in spans)
        if not required <= names or not services <= sources or last["missingRetryWait"]:
            return None
        # Every selected durable-work parent is an actual exported span, not a new root.
        last["missingParents"] = [
            (span.get("name"), span.get("parentId")) for span in work if span.get("parentId") not in ids]
        if last["missingParents"]:
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
        last["wrongAncestors"] = [
            key(span) for span in spans if key(span) in ancestry and not ancestor_matches(span, ancestry[key(span)])]
        if last["wrongAncestors"]:
            return None
        return spans
    try:
        spans = until(collected, "missing sampled async trace boundaries: " + ",".join(sorted(required)), 60)
    except AssertionError as failure:
        raise AssertionError(str(failure) + "; " + json.dumps(last, sort_keys=True)) from failure
    print(json.dumps({"traceId": trace_id, "spanCount": len(spans),
                      "asyncBoundaries": sorted(required)}), flush=True)
    return spans

def verify():
    with stack("cs-financial-flow-", memory_storage=True, commerce_outbox=False) as compose:
        def flow(*args, **kwargs):
            return compose("-f", str(FLOW), *args, **kwargs)

        def endpoint(service, port):
            return "http://" + compose("port", service, str(port)).strip()

        base = endpoint("gateway", 8080)
        prometheus = endpoint("prometheus", 9090)
        zipkin = endpoint("zipkin", 9411)
        refund_traces, refund_orders = {}, {}

        def trace_headers(trace_id):
            return {"traceparent": f"00-{trace_id}-{uuid.uuid4().hex[:16]}-01"}

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
            timed("payment-worker restart: " + ",".join(f"{k}={v}" for k, v in environment.items()),
                  flow, "-f", "-", "up", "-d", "--no-deps", "--wait", "payment-worker",
                  input_text=json.dumps({"services": {"payment-worker": {"environment": environment}}}), timeout=180)

        # Immutable delay scenarios: one loses responses, one returns a late success for fencing.
        for version, loss, delay in [("flow-delayed-v1", 1, 7000), ("flow-late-success-v1", 0, 7000),
                                     ("flow-crash-v1", 1, 15000)]:
            sql("finance_mock", f"""
INSERT INTO finance_mock.mock_scenarios
SELECT '{uuid.uuid4()}',settlement_delay_business_days,provider_id,'{version}',
min_delay_ms,max_delay_ms,rejection_rate,response_loss_rate,lookup_failure_rate,
{delay},{delay},0,{loss},lookup_min_delay_ms,lookup_max_delay_ms,seed,created_at
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
                order_id = path.split("/")[3]
                refund_traces[order_id] = trace_id
                refund_orders[result[1]["id"]] = order_id
            if (method == "POST" and path.startswith("/api/sellers/me/refund-requests/")
                    and path.endswith("/decision") and result[0] == 202 and body.get("decision") == "APPROVE"):
                # RETURN waits for a separate seller command. That command actually starts its refund SAGA.
                refund_traces[refund_orders[path.split("/")[5]]] = trace_id
            return result

        status, address = request("POST", "/api/buyers/me/addresses", {
            "recipient": "test", "phone": "010-0000-0000", "postalCode": "00000",
            "addressLine1": "test", "addressLine2": "", "isDefault": True})
        assert status == 201, (status, address)

        def place_order(mileage=MILEAGE, method="CREDIT_CARD", account="test-card", *, recover_admission=True):
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

            return until(create_when_admitted, "order was not admitted within the recovery budget", 300), trace_id

        def order_status(order_id):
            return sql("commerce", f"select status from commerce.orders where id='{order_id}'")

        def retry_payment(order_id):
            """Calls the actual retry API after a confirmed decline and returns the new cycle and its trace."""
            until(gate_open,"retry admission did not recover",300)
            status,current=request("GET","/api/orders/"+order_id)
            assert status==200 and current["status"]=="DECLINED",(status,current)
            previous=sql("commerce",f"select payment_cycle_id from commerce.orders where id='{order_id}'")
            retry_key=str(uuid.uuid4())
            trace_id=uuid.uuid4().hex
            retry_headers={"Idempotency-Key":retry_key,**trace_headers(trace_id)}
            def retry_when_admitted():
                status,result=gateway_json(base,"POST",f"/api/orders/{order_id}/payment-retries",
                    {"expectedVersion":current["version"]},token,retry_headers)
                if status==503 and result.get("code")=="COMMON_CAPACITY_EXCEEDED":return None
                assert status==202,(status,result)
                return result
            until(retry_when_admitted,"actual payment retry API was not accepted",300)
            cycle=sql("commerce",f"select payment_cycle_id from commerce.orders where id='{order_id}'")
            assert cycle!=previous,(cycle,previous)
            return cycle,trace_id

        def mileage_history(payment):
            reservations=sql("payment",f"select string_agg(status, ',' order by created_at,id) from payment.mileage_reservations where payment_id='{payment}'")
            entries=sql("payment",f"select string_agg(kind || ':' || amount, ',' order by kind,created_at) from payment.mileage_entries where payment_id='{payment}'")
            return reservations,entries

        def attempts(payment):
            return sql("payment",f"select string_agg(attempt_no || ':' || status, ',' order by attempt_no) from payment.payment_attempts where payment_id='{payment}'")

        def outbox_event(database, event_type, aggregate, cycle):
            return sql(database,f"""select payload->>'eventId' from {database}.outbox where event_type='{event_type}'
and aggregate_id='{aggregate}' and payload->'payload'->>'paymentCycleId'='{cycle}'""")

        def replay(database, event_id):
            # Re-send the stored bytes with the original topic/key. Consumers must treat it as a duplicate.
            topic,key,body=sql(database,f"select topic || chr(9) || message_key || chr(9) || convert_from(raw_body,'UTF8') from {database}.outbox where payload->>'eventId'='{event_id}'").split("\t",2)
            compose("exec","-T","kafka","/opt/kafka/bin/kafka-console-producer.sh","--bootstrap-server","kafka:9092",
                    "--topic",topic,"--property","parse.key=true","--property","key.separator=\t",input_text=key+"\t"+body+"\n")

        def paid_order(mileage=MILEAGE, method="CREDIT_CARD", account="test-card", *, recover_admission=True, retry_after_decline=False):
            order, trace_id = place_order(mileage, method, account, recover_admission=recover_admission)
            if retry_after_decline:
                until(lambda: order_status(order["id"]) == "DECLINED", "first approval did not reach confirmed decline")
                first_cycle=sql("commerce",f"select payment_cycle_id from commerce.orders where id='{order['id']}'")
                worker_settings()
                cycle,trace_id=retry_payment(order["id"])
            until(lambda: sql("commerce", f"select status from commerce.orders where id='{order['id']}'") == "PAID",
                  "order was not paid through Payment")
            payment = sql("commerce", f"select payment_id from commerce.orders where id='{order['id']}'")
            until(lambda: sql("settlement", f"select count(*) from settlement.settlement_items where payment_id='{payment}'") == "1",
                  "Settlement did not create the item from PaymentConfirmed")
            accepted = sql("commerce", f"select extract(epoch from occurred_at)::text from commerce.outbox where event_type='OrderPaymentRequested' and aggregate_id='{order['id']}' order by created_at,id limit 1")
            order_created = sql("commerce", f"select extract(epoch from created_at)::text from commerce.orders where id='{order['id']}'")
            assert accepted == order_created, (accepted,order_created)
            persisted = sql("payment", f"select extract(epoch from order_accepted_at)::text from payment.outbox where event_type='PaymentConfirmed' and aggregate_id='{payment}'")
            received = sql("settlement", f"select extract(epoch from order_accepted_at)::text from settlement.inbox where event_type='PaymentConfirmed' and aggregate_id='{payment}' and status='APPLIED'")
            captured=sql("payment",f"select extract(epoch from order_accepted_at)::text from payment.inbox where event_type='OrderPaymentRequested' and aggregate_id='{order['id']}' order by created_at desc,id desc limit 1")
            assert accepted and accepted == captured == persisted == received, (accepted,captured,persisted,received)
            if retry_after_decline:
                history=attempts(payment)
                assert history == "1:DECLINED,2:APPROVED", history
                settlement_items=int(sql("settlement",f"select count(*) from settlement.settlement_items where payment_id='{payment}'"))
                approved=int(sql("payment",f"select count(*) from payment.payment_attempts a join payment.payments p on p.id=a.payment_id and p.current_cycle_id=a.cycle_id where p.id='{payment}' and a.status='APPROVED'"))
                assert approved == settlement_items == 1, (approved,settlement_items)
                cycles=sql("commerce",f"select count(distinct payload->'payload'->>'paymentCycleId') from commerce.outbox where event_type='OrderPaymentRequested' and aggregate_id='{order['id']}'")
                assert cycles == '2', cycles
                if mileage:
                    # Each cycle reserves again: the declined cycle's reservation stays RELEASED and only the new one is debited.
                    assert mileage_history(payment) == ("RELEASED,CONSUMED", f"DEBIT:{mileage},RELEASE:{mileage},RESERVE:{mileage},RESERVE:{mileage}"), mileage_history(payment)
                    assert sql("payment",f"select string_agg(cycle_id::text, ',' order by created_at,id) from payment.mileage_reservations where payment_id='{payment}'") == f"{first_cycle},{cycle}"
                # Duplicate deliveries of the old cycle's request and result do not change the paid cycle.
                before=(history,mileage_history(payment),order_status(order["id"]))
                replay("payment",outbox_event("payment","PaymentDeclined",payment,first_cycle))
                replay("commerce",outbox_event("commerce","OrderPaymentRequested",order["id"],first_cycle))
                timed("replay drain", wait_drained, compose)
                assert (attempts(payment),mileage_history(payment),order_status(order["id"])) == before
                assert sql("payment",f"select status || '/' || current_cycle_id from payment.payments where id='{payment}'") == f"CONFIRMED/{cycle}"
                print(json.dumps({"paymentRetryCase":{"retry_completed":True,"settlement_items":settlement_items,"approved_current_attempts":approved,"old_attempt_status":history.split(',')[0].split(':')[1],"new_attempt_no":int(history.split(',')[1].split(':')[0]),"mileage":mileage,"origin_preserved":accepted == captured == persisted == received == order_created}}),flush=True)
            required = {"commerce.outbox.send", "payment.inbox.process", "payment.outbox.send",
                        "commerce.inbox.accepted", "commerce.inbox.result"}
            services = {"gateway", "commerce", "payment", "settlement"}
            if mileage < PRICE:
                required.add("payment.approval.process")
                services.add("finance-mock")
            observed_trace(zipkin, trace_id, required, services)
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

        def completed(order_id, payment, expected_mileage=MILEAGE, stock="RESTORED", recovered_parent=None):
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
            retry_wait = int(sql("payment", f"select attempts from payment.payment_jobs where refund_id='{refund_id}'")) > 1
            spans = observed_trace(zipkin, refund_traces[order_id],
                                   {"commerce.outbox.send", "payment.inbox.process", "payment.refund.process",
                                    "payment.outbox.send", "settlement.outbox.send", "commerce.inbox.result"},
                                   {"gateway", "commerce", "payment", "settlement"} | ({"finance-mock"} if int(external) else set()),
                                   retry_wait=retry_wait)
            if recovered_parent is not None:
                assert any(span.get("name") == "payment.refund.process" and span.get("parentId") == recovered_parent
                           for span in spans), "recovered worker did not restore the persisted parent"
            print(json.dumps({"completed": refund_id, "external": int(external), "mileage": expected_mileage}), flush=True)
            if expected_mileage == MILEAGE:
                print(json.dumps({"mixedRefundCase":{"refund_completed":True,"external_refund_amount":int(external),"mileage_restored_amount":int(mileage)}}),flush=True)
            return refund_id

        def cancelled_retry(block_approval):
            """Mixed payment: confirmed decline, real retry API, then the buyer's unpaid cancellation of the retry cycle."""
            worker_settings("fixture-pg-reject-v1")
            wallet=sql("payment",f"select balance || '/' || reserved_amount from payment.mileage_wallets where buyer_id='{buyer}'")
            order,_=place_order(MILEAGE)
            order_id=order["id"]
            until(lambda: order_status(order_id) == "DECLINED", "first approval did not reach confirmed decline")
            first_cycle=sql("commerce",f"select payment_cycle_id from commerce.orders where id='{order_id}'")
            payment=sql("commerce",f"select payment_id from commerce.orders where id='{order_id}'")
            if block_approval:
                worker_settings("fixture-pg-reject-v1",APP_PAYMENT_APPROVAL_ENABLED="false")
            posts=mock_calls("POST")
            cycle,_=retry_payment(order_id)
            if block_approval:
                # Payment adopted the cycle and reserved mileage again, but no approval ran yet.
                until(lambda: sql("payment",f"select status || '/' || current_cycle_id from payment.payments where id='{payment}'") == f"DECLINED/{cycle}",
                      "Payment did not accept the retry cycle")
                assert sql("payment",f"select status from payment.mileage_reservations where payment_id='{payment}' and cycle_id='{cycle}'") == "RESERVED"
                assert sql("payment",f"select reserved_amount from payment.mileage_wallets where buyer_id='{buyer}'") == str(MILEAGE)
            else:
                until(lambda: order_status(order_id) == "DECLINED" and attempts(payment) == "1:DECLINED,2:DECLINED",
                      "retry cycle was not declined again")
                assert sql("commerce",f"select payment_cycle_id from commerce.orders where id='{order_id}'") == cycle
            status,current=request("GET","/api/orders/"+order_id)
            assert status==200,(status,current)
            status,cancelled=request("POST",f"/api/orders/{order_id}/cancellation",
                                     {"expectedVersion":current["version"],"reason":"financial flow retry cancellation"})
            assert status==202 and cancelled["status"]=="CANCEL_PENDING",(status,cancelled)
            until(lambda: order_status(order_id) == "CANCELLED", "retry cycle cancellation was not confirmed")
            if block_approval:
                worker_settings()
                until(lambda: sql("payment",f"select status || '/' || last_error_code from payment.payment_jobs where operation='APPROVE' and business_key='{cycle}'") == "DONE/PAYMENT_CANCELLED",
                      "retry approval job was not closed by the cancellation")
            expected_attempts="1:DECLINED" if block_approval else "1:DECLINED,2:DECLINED"
            assert attempts(payment) == expected_attempts, attempts(payment)
            assert mock_calls("POST") == posts + (0 if block_approval else 1), (mock_calls("POST"), posts)
            assert sql("payment",f"select status || '/' || current_cycle_id from payment.payments where id='{payment}'") == f"CANCELLED/{cycle}"
            assert mileage_history(payment) == ("RELEASED,RELEASED", f"RELEASE:{MILEAGE},RELEASE:{MILEAGE},RESERVE:{MILEAGE},RESERVE:{MILEAGE}"), mileage_history(payment)
            assert sql("payment",f"select string_agg(cycle_id::text, ',' order by created_at,id) from payment.mileage_reservations where payment_id='{payment}'") == f"{first_cycle},{cycle}"
            assert sql("payment",f"select balance || '/' || reserved_amount from payment.mileage_wallets where buyer_id='{buyer}'") == wallet
            assert sql("payment",f"select string_agg(payload->'payload'->>'paymentCycleId' || ':' || (payload->'payload'->>'reasonCode'), ',') from payment.outbox where aggregate_id='{payment}' and event_type='PaymentCancelled'") == f"{cycle}:CANCELLED_BEFORE_APPROVAL"
            assert reservation(order_id) == "RELEASED", reservation(order_id)
            assert sql("settlement",f"select count(*) from settlement.settlement_items where payment_id='{payment}'") == "0"
            # Duplicate deliveries of both cycles' requests and results keep the cancelled cycle unchanged.
            before=(attempts(payment),mileage_history(payment),order_status(order_id),reservation(order_id),inventory())
            replay("payment",outbox_event("payment","PaymentDeclined",payment,first_cycle))
            if not block_approval:
                replay("payment",outbox_event("payment","PaymentDeclined",payment,cycle))
            replay("payment",outbox_event("payment","PaymentCancelled",payment,cycle))
            replay("commerce",outbox_event("commerce","OrderPaymentRequested",order_id,cycle))
            replay("commerce",outbox_event("commerce","PaymentCancellationRequested",order_id,cycle))
            timed("replay drain", wait_drained, compose)
            assert (attempts(payment),mileage_history(payment),order_status(order_id),reservation(order_id),inventory()) == before
            assert mock_calls("POST") == posts + (0 if block_approval else 1)
            print(json.dumps({"paymentRetryCancellationCase":{"approval_blocked":block_approval,"attempts":expected_attempts,
                              "mileage_reservations":"RELEASED,RELEASED","order":"CANCELLED","inventory_reservation":"RELEASED",
                              "duplicates_replayed":True}}),flush=True)

        try:
            first_refund, second_refund = timed("base scenarios", scenarios, paid_order, cancel, refund_state, completed,
                                                inventory, reservation, compose, gate_open)
            worker_settings("fixture-pg-reject-v1")
            retry_order, retry_paid = timed("retry approval", paid_order, MILEAGE, retry_after_decline=True)
            timed("retry approval refund", lambda: (cancel(retry_order), completed(retry_order, retry_paid)))
            timed("retry decline and cancellation", cancelled_retry, block_approval=False)
            timed("retry cancellation before approval", cancelled_retry, block_approval=True)
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
            worker_settings("flow-crash-v1", APP_REFUND_HTTP_TIMEOUT="20s")
            crash_order, crash_payment = paid_order()
            cancel(crash_order)
            until(lambda: sql("payment", f"select count(*) from payment.refunds where payment_id='{crash_payment}'") == "1", "refund not accepted")
            crash_refund, crash_key = refund_info(crash_payment)
            until(lambda: external_count(crash_key) == "1", "external refund not committed before crash")
            stored = sql("payment", f"select trace_parent from payment.payment_jobs where refund_id='{crash_refund}'").split("-")
            assert len(stored) == 4 and stored[1] == refund_traces[crash_order]
            crash_parent = stored[2]

            def parent_exported():
                try:
                    with urlopen(zipkin + "/api/v2/trace/" + stored[1], timeout=5) as response:
                        return any(span["id"] == crash_parent for span in json.load(response))
                except HTTPError as error:
                    if error.code == 404:
                        return False
                    raise
            # Prove the completed predecessor was collected before SIGKILL. A killed process cannot export
            # its in-flight spans; do not turn batch-export timing into a false durable-context failure.
            until(parent_exported, "persisted predecessor was not exported before the crash barrier", 10)
            assert sql("payment", f"select external_status from payment.refunds where id='{crash_refund}'") == "REQUESTED"
            assert sql("payment", f"select status from payment.payment_jobs where refund_id='{crash_refund}'") == "RUNNING"
            compose("kill", "-s", "SIGKILL", "payment-worker")
            assert reserved(crash_payment) == "ACTIVE"
            assert sql("payment", f"select count(*) from payment.mileage_entries where payment_id='{crash_payment}' and kind='RESTORE'") == "0"
            sql("payment", f"update payment.payment_jobs set lease_until=now()-interval '1s' where refund_id='{crash_refund}'")
            worker_settings()
            completed(crash_order, crash_payment, recovered_parent=crash_parent)
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
            def committed_business_metrics():
                expected = {
                    "payment.confirmed": int(sql("payment", "select count(*) from payment.payments where confirmed_at >= statement_timestamp()-interval '900 seconds' and confirmed_at < statement_timestamp()")),
                    "refund.completed": int(sql("payment", "select count(*) from payment.refund_sagas s join payment.refunds r on r.id=s.refund_id where s.step='COMPLETED' and r.status='COMPLETED' and s.order_restored_at >= statement_timestamp()-interval '900 seconds' and s.order_restored_at < statement_timestamp()")),
                    "order.settlement.applied": int(sql("settlement", "select count(*) from settlement.settlement_items where created_at >= statement_timestamp()-interval '900 seconds' and created_at < statement_timestamp()")),
                }
                expression='business:completions{window="900"}'
                with urlopen(prometheus + "/api/v1/query?"+urlencode({"query":expression}),timeout=5) as response:
                    rows=json.load(response)["data"]["result"]
                actual={row["metric"]["operation"]:float(row["value"][1]) for row in rows}
                return expected if all(value>0 and actual.get(operation)==value for operation,value in expected.items()) else None
            counts=until(committed_business_metrics,"committed DB completion TPS did not reach Prometheus",60)
            with urlopen(prometheus + "/api/v1/query?"+urlencode({"query":'business:duration_count{window="900",operation="order.settlement.applied"}'}),timeout=5) as response:
                samples=json.load(response)["data"]["result"]
            assert samples and float(samples[0]["value"][1])>0, samples
            with urlopen(prometheus + "/api/v1/query?"+urlencode({"query":'sum(http_server_requests_seconds_bucket{application="gateway",le="+Inf"})'}),timeout=5) as response:
                histogram=json.load(response)["data"]["result"]
            assert histogram and float(histogram[0]["value"][1])>0, histogram
            print(json.dumps({"businessCompletions":counts,"orderToSettlementLatencySamples":samples[0]["value"][1],"httpHistogram":"observed"}),flush=True)
            dashboard = json.loads((ROOT / "infra/grafana/dashboards/business-performance.json").read_text())
            evaluations = 0
            for window in (60, 300, 900):
                for application in ("gateway", ".*"):
                    for panel in dashboard["panels"]:
                        for target in panel["targets"]:
                            expression = (target["expr"].replace("${window}", str(window))
                                          .replace("$window", str(window)).replace("$comparison_offset", "1h")
                                          .replace("$application", application))
                            with urlopen(prometheus + "/api/v1/query?" + urlencode({"query": expression}), timeout=5) as response:
                                result = json.load(response)
                            assert result["status"] == "success" and result["data"]["resultType"] == "vector", (panel["id"], result)
                            if window == 900 and application == "gateway" and target["refId"] == "A" and panel["id"] in (1, 4, 6, 9):
                                assert any(float(row["value"][1]) > 0 for row in result["data"]["result"]), (panel["id"], result)
                            evaluations += 1
            print(json.dumps({"businessDashboardPanels": len(dashboard["panels"]), "queryEvaluations": evaluations}), flush=True)
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
