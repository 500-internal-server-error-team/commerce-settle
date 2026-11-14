"""Isolated real-clock demo: immutable run options -> refund -> delivery -> Batch -> mock payout.

Owns a fresh Compose project through the existing stack owner. No dates, financial facts or
existing environments are rewritten. --hold-file keeps the verified stack for local UI review.
"""
import argparse
import hashlib
from http.client import RemoteDisconnected
from urllib.error import URLError
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import time
import uuid
from urllib.request import urlopen

from test_admission import stack, until
from prepare_financial_kafka import prepare_offsets
from smoke import gateway_json

ROOT = Path(__file__).resolve().parents[1]


def verify(hold_file=None, runtime_file=None):
    print("Starting isolated demo-run stack",flush=True)
    with stack("cs-demo-runs-", commerce_outbox=False) as compose:
        try:
            operators = {s:secrets.token_urlsafe(32) for s in ["commerce","settlement","finance-mock"]}
            overlay = {"services": {}}
            for service in ["commerce", "settlement", "finance-mock"]:
                overlay["services"][service] = {"ports": ["127.0.0.1::8080"], "environment": {
                    "SPRING_APPLICATION_JSON": json.dumps({"app": {"internal-auth": {"senders": {"[operator:demo-ui]": {"current": operators[service]}}}}})}}
            overlay["services"]["commerce"]["mem_limit"]="1g"
            overlay["services"]["settlement"]["environment"].update({
                "PAYOUT_FUNDING_ACCOUNT_REF": "fixture-payout-funding", "PAYOUT_SCENARIO_VERSION": "fixture-normal-v1",
                "PAYOUT_LOOKUP_SCENARIO_VERSION": "fixture-normal-v1", "SETTLEMENT_PAYOUT_WORKER_ENABLED": "true"})

            def flow(*args, **kwargs):
                return compose("-f", str(ROOT / "compose.financial-flow.yml"), "-f", "-", *args, input_text=json.dumps(overlay), **kwargs)

            def endpoint(service):
                return "http://" + compose("port", service, "8080").strip()

            def sql(database, text):
                return compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database,
                               "-v", "ON_ERROR_STOP=1", "-Atq", input_text=text).strip()

            def internal(service, method, path, body=None, key=None):
                key=key or str(uuid.uuid4())
                url=endpoint(service)
                def attempt():
                    try: return gateway_json(url, method, path, body, operators[service], {"Idempotency-Key": key})
                    except (RemoteDisconnected,URLError,TimeoutError): return None
                return until(attempt,"internal HTTP endpoint did not recover: "+service,90)

            description=compose("exec","-T","kafka","/opt/kafka/bin/kafka-topics.sh","--bootstrap-server","kafka:9092","--describe","--topic","^commerce-settle\\.(commerce|payment|settlement)\\.v1$")
            print(json.dumps({"stage":"base topic observation","description":description}),flush=True)
            from prepare_financial_kafka import partitions
            def topics_ready():
                try: return partitions(compose)
                except RuntimeError: return None
            until(topics_ready,"base financial topics did not become ready",120)
            prepare_offsets(compose)
            flow("up", "-d", "--wait", "--wait-timeout", "300", "commerce", "payment-worker", "settlement", "finance-mock", timeout=600)
            for service, path in [("commerce", "/internal/demo-runs"), ("settlement", "/internal/demo-settlement-runs")]:
                print(json.dumps({"stage":"default-off HTTP check","service":service}),flush=True)
                status, data = internal(service, "POST", path, {"sellerId": str(uuid.uuid4()), "productId": str(uuid.uuid4()), "deliverySeconds": 5, "refundSeconds": 10} if service == "commerce" else {"runId": str(uuid.uuid4())})
                assert status == 503 and data["code"] == "DEMO_DISABLED", (service, status, data)
                overlay["services"][service]["environment"].update({"APP_DEMO_ENABLED": "true", "APP_DEMO_ISOLATED": "true", "SETTLEMENT_DAILY_BATCH_ENABLED": "false", "APP_DEMO_AUTO_SHIPMENT_ENABLED": "false"})
            flow("up", "-d", "--wait", "--wait-timeout", "300", "commerce", "settlement", timeout=600)
            base = endpoint("gateway")
            buyer, seller, product = [str(uuid.uuid4()) for _ in range(3)]
            sql("commerce", f"""
    INSERT INTO commerce.buyer_accounts(id,email_normalized,password_hash,display_name,status,created_at,updated_at,version) VALUES('{buyer}','{buyer}@demo.test','test','demo','ACTIVE',now(),now(),0);
    INSERT INTO commerce.seller_accounts(id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version) VALUES('{seller}','{seller}@demo.test','test','demo','ACTIVE','test-account',now(),now(),0);
    INSERT INTO commerce.products(id,seller_id,name,unit_price,status,created_at,updated_at,version) VALUES('{product}','{seller}','Demo run',1000,'ON_SALE',now(),now(),0);
    INSERT INTO commerce.product_inventory(id,product_id,available_qty,reserved_qty,event_allocated_qty,sold_qty,created_at,updated_at,version) VALUES('{uuid.uuid4()}','{product}',100,0,0,0,now(),now(),0);
    """)
            config = json.loads(compose("config", "--format", "json"))
            epoch = config["services"]["gateway"]["environment"]["AUTH_SESSION_EPOCH"]
            token = secrets.token_urlsafe(32)

            def redis(*args):
                return compose("exec", "-T", "redis", "sh", "-c", 'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"', "demo", *args)

            seconds, micros = redis("TIME").split()
            now = int(seconds) * 1000 + int(micros) // 1000
            redis("HSET", f"commerce-settle:auth:session:{epoch}:{hashlib.sha256(token.encode()).hexdigest()}", "actorKind", "BUYER", "actorId", buyer, "purpose", "SESSION", "authVersion", "0", "issuedAt", str(now), "expiresAt", str(now + 3_600_000))
            redis("HSET", f"commerce-settle:auth:subject:{epoch}:BUYER:{buyer}", "authVersion", "0", "state", "ACTIVE")

            def request(method, path, body=None, key=None):
                key=key or str(uuid.uuid4())
                def attempt():
                    try: return gateway_json(base, method, path, body, token, {"Idempotency-Key":key})
                    except (RemoteDisconnected,URLError,TimeoutError): return None
                return until(attempt,"gateway HTTP endpoint did not recover",90)

            status, address = request("POST", "/api/buyers/me/addresses", {"recipient": "demo", "phone": "010-0000-0000", "postalCode": "00000", "addressLine1": "demo", "addressLine2": "", "isDefault": True})
            assert status == 201, (status, address)

            def order(run_id):
                body = {"productId": product, "quantity": 1, "eventId": None, "buyerCouponId": None, "mileageAmount": 0, "addressId": address["id"], "expectedProductVersion": 0, "expectedEventVersion": None, "expectedTotalAmount": 1000, "externalMethod": "CREDIT_CARD", "methodAccountRef": "test-card", "demoRunId": run_id}
                key = str(uuid.uuid4())
                def admitted():
                    status, data = request("POST", "/api/orders", body, key)
                    if status == 503 and data.get("code") == "COMMON_CAPACITY_EXCEEDED": return None
                    assert status == 201, (status, data)
                    return data
                data = until(admitted, "order admission did not recover", 300)
                until(lambda: sql("commerce", f"SELECT status FROM commerce.orders WHERE id='{data['id']}'") == "PAID", "payment did not confirm")
                return data["id"]

            def stage(run_id, name):
                key, after = str(uuid.uuid4()), None
                while True:
                    path = f"/internal/demo-runs/{run_id}/{name}" + ("?after=" + after if after else "")
                    status, data = internal("commerce", "POST", path, {}, key)
                    assert status == 200, (status, data)
                    after = data["nextCursor"]
                    if after is None: return

            records=[]
            for index, (delivery, refund) in enumerate([(5, 10), (2, 3)]):
                body = {"sellerId": seller, "productId": product, "eventId": None, "deliverySeconds": delivery, "refundSeconds": refund}
                key = str(uuid.uuid4())
                status, run = internal("commerce", "POST", "/internal/demo-runs", body, key)
                assert status == 201, (status, run)
                run_id = run["id"]
                print(json.dumps({"stage":"run created","runId":run_id,"deliverySeconds":delivery,"refundSeconds":refund}),flush=True)
                assert internal("commerce", "POST", "/internal/demo-runs", body, key)[1]["id"] == run_id
                assert internal("commerce", "POST", "/internal/demo-runs", {**body, "refundSeconds": refund + 1}, key)[0] == 409
                payable = order(run_id)
                refunded = None
                if index == 0:
                    refunded = order(run_id)
                    current = request("GET", "/api/orders/" + refunded)[1]
                    status, data = request("POST", f"/api/orders/{refunded}/refund-requests", {"kind": "CANCEL", "reason": "시연 환불", "expectedVersion": current["version"]})
                    assert status == 201, (status, data)
                    until(lambda: sql("commerce", f"SELECT status FROM commerce.orders WHERE id='{refunded}'") == "REFUNDED", "refund SAGA did not finish")
                assert internal("commerce", "POST", f"/internal/demo-runs/{run_id}/freeze", {})[0] == 200
                # Early processing cannot manufacture delivery or purchase confirmation.
                stage(run_id, "processing")
                assert sql("commerce", f"SELECT fulfillment_status FROM commerce.orders WHERE id='{payable}'") == "UNSHIPPED"
                stage(run_id, "shipments")
                assert sql("commerce", f"SELECT extract(epoch FROM delivery_due_at-shipped_at)::int FROM commerce.orders WHERE id='{payable}'") == str(delivery)
                compose("restart", "commerce", "settlement", timeout=180)
                flow("up", "-d", "--wait", "--wait-timeout", "300", "--no-recreate", "commerce", "settlement", timeout=600)
                until(lambda: sql("commerce", f"SELECT now()>=delivery_due_at FROM commerce.orders WHERE id='{payable}'") == "t", "delivery not due")
                stage(run_id, "processing")
                assert sql("commerce", f"SELECT extract(epoch FROM refund_deadline_at-delivered_at)::int FROM commerce.orders WHERE id='{payable}'") == str(refund)
                until(lambda: sql("commerce", f"SELECT now()>refund_deadline_at FROM commerce.orders WHERE id='{payable}'") == "t", "refund period not elapsed")
                stage(run_id, "processing")
                assert sql("commerce", f"SELECT status FROM commerce.orders WHERE id='{payable}'") == "PURCHASE_CONFIRMED"
                start_key=str(uuid.uuid4())
                def start():
                    status, data=internal("settlement", "POST", "/internal/demo-settlement-runs", {"runId":run_id}, start_key)
                    if status==409 and data.get("code")=="DEMO_NOT_READY": return None
                    assert status==202,(status,data)
                    return data
                saved=until(start,"settlement receipts did not arrive")
                until(lambda: internal("settlement","GET",f"/internal/demo-settlement-runs/{run_id}")[1].get("batchStatus")=="COMPLETED","batch did not finish")
                def paid():
                    data=internal("settlement","GET",f"/internal/demo-settlement-runs/{run_id}")[1]
                    return data if data.get("batches") and all(b["payoutStatus"]=="SUCCEEDED" for b in data["batches"]) else None
                result=until(paid,"mock payout did not succeed")
                assert result["itemCount"]==1 and result["refundedCount"]==(1 if refunded else 0),result
                assert result["batches"][0]["netAmount"]==850,result
                for duplicate_key in [start_key,str(uuid.uuid4())]:
                    status,replay=internal("settlement","POST","/internal/demo-settlement-runs",{"runId":run_id},duplicate_key)
                    assert status==202 and replay["cutoffAt"]==saved["cutoffAt"],(status,replay)
                assert sql("settlement",f"SELECT count(*) FROM settlement.settlement_batches WHERE demo_run_id='{run_id}'")=="1"
                assert sql("settlement",f"SELECT bool_and(i.ready_at<r.cutoff_at) FROM settlement.settlement_items i JOIN settlement.demo_runs r ON i.id IN (SELECT value::uuid FROM jsonb_array_elements_text(r.item_ids)) WHERE r.id='{run_id}'")=="t"
                records.append(result)
            print(json.dumps({"result":"PASS","scope":"real-clock HTTP/Kafka/PostgreSQL/Spring Batch/Finance Mock; two immutable runs with restart and duplicates", "runs":records},ensure_ascii=False),flush=True)
            if hold_file:
                environment={**os.environ,"DEMO_GATEWAY_URL":base,"DEMO_COMMERCE_URL":endpoint("commerce"),"DEMO_SETTLEMENT_URL":endpoint("settlement"),"DEMO_COMMERCE_OPERATOR_CREDENTIAL":operators["commerce"],"DEMO_SETTLEMENT_OPERATOR_CREDENTIAL":operators["settlement"],"DEMO_CONTROL_PORT":"0"}
                log=Path(runtime_file).with_suffix('.log')
                with log.open('w') as output:
                    process=subprocess.Popen(["/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java","-jar",str(ROOT/'demo-control/build/libs/app.jar')],cwd=ROOT,env=environment,stdout=output,stderr=subprocess.STDOUT)
                    try:
                        import re
                        def port():
                            match=re.search(r'Tomcat started on port (\d+)',log.read_text())
                            return match.group(1) if match else None
                        control_port=until(port,"demo-control did not start",60)
                        Path(runtime_file).write_text(json.dumps({"url":"http://127.0.0.1:"+control_port,"sellerId":seller,"productId":product,"runId":run_id,"processId":process.pid}))
                        deadline=time.monotonic()+1800
                        while not Path(hold_file).exists():
                            if time.monotonic()>deadline:raise TimeoutError("UI review hold expired")
                            time.sleep(1)
                    finally:
                        process.terminate();process.wait(timeout=30)
        except BaseException:
            print(compose("logs","--no-color","--tail=80","commerce","settlement","payment-worker","finance-mock"),flush=True)
            raise


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--hold-file');parser.add_argument('--runtime-file')
    args=parser.parse_args()
    signal.signal(signal.SIGTERM,lambda *_: (_ for _ in ()).throw(SystemExit(143)))
    verify(args.hold_file,args.runtime_file)
