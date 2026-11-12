"""선착순 이벤트 집중 부하 A단계: Gateway·Commerce의 주문 접수와 이벤트 예약 경합만 측정한다.

새 Compose 프로젝트를 만들어 시험이 끝나면 소유 자원만 지운다(scripts/test_admission.py의 stack). 실제 신규 접수 gate를
그대로 쓰며, Payment·Settlement 소비는 꺼 두므로 주문은 PAYMENT_PENDING에서 재고·이벤트 예약을 잡은 채 남는다.
결제 확정·환불 복원을 포함한 B단계는 이 범위가 아니다.

    python3 -B tests/load/event_load.py --buyers 6000 --peak-rate 600 --tail-rate 100 --tail-seconds 25
    python3 -B tests/load/event_load.py   # 문서 목표: 60,000명, 첫 5초 6,000/s, 이후 55초 545/s

결과는 Git에서 제외한 tests/load/work/event-<시각>/summary.json에 남는다.
"""

import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import secrets
import signal
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from smoke import gateway_json  # noqa: E402
from test_admission import prepare_offsets, stack, until  # noqa: E402

EVENT_PRICE, NORMAL_PRICE = 9_000, 10_000
NORMAL_STOCK = 1_000_000


def buyer_id(index):
    return str(uuid.UUID(hex=hashlib.md5(f"load-buyer-{index}".encode()).hexdigest()))


def command(*parts):
    # 인라인 명령은 LF·CRLF 줄끝을 모두 받으므로 Windows 텍스트 모드의 줄끝 변환에도 깨지지 않는다. 값에는 공백이 없다.
    assert all(" " not in part for part in parts)
    return " ".join(parts) + "\n"


def outcome_counts(metrics):
    return {name.removeprefix("order_"): int(metrics[name]["count"]) for name in metrics
            if name.startswith("order_") and "count" in metrics[name] and "{" not in name}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--buyers", type=int, default=60_000)
    parser.add_argument("--quota", type=int, default=1_000)
    parser.add_argument("--peak-seconds", type=int, default=5)
    parser.add_argument("--peak-rate", type=int, default=6_000)
    parser.add_argument("--tail-seconds", type=int, default=55)
    parser.add_argument("--tail-rate", type=int, default=545)
    parser.add_argument("--gauge-rate", type=int, default=0, help="게이지 조회 초당 건수. 0이면 끈다")
    parser.add_argument("--max-vus", type=int, default=1_000)
    parser.add_argument("--warmup-seconds", type=int, default=0, help="이벤트 공개 전 일반 상품 주문으로 예열하는 시간(초). 0이면 끈다")
    parser.add_argument("--warmup-rate", type=int, default=50, help="예열 초당 주문 수")
    args = parser.parse_args(argv)
    orders = args.peak_seconds * args.peak_rate + args.tail_seconds * args.tail_rate
    if orders > args.buyers:
        parser.error(f"{orders} orders need at least as many buyers")
    if args.warmup_seconds < 0 or args.warmup_rate < 0:
        parser.error("warm-up seconds and rate must be non-negative")
    # 예열 구매자는 집중 구간 구매자와 따로 둬 집중 구간이 계속 서로 다른 구매자의 첫 주문이 되게 한다.
    # 초당 주문 수만큼 두면 예열 구매자마다 초당 약 1건이라 구매자 행 잠금끼리 기다리지 않는다.
    warmup_buyers = args.warmup_rate if args.warmup_seconds and args.warmup_rate else 0
    prepared = args.buyers + warmup_buyers

    work = ROOT / "tests" / "load" / "work" / datetime.now().strftime("event-%Y%m%d-%H%M%S")
    work.mkdir(parents=True)
    with stack("cs-event-load-") as compose:
        def sql(statement):
            return compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "commerce", "-v", "ON_ERROR_STOP=1",
                           "-Atq", input_text=statement, timeout=600).strip()

        def k6(mode, **environment):
            overlay = {"services": {"k6": {"mem_limit": "3g", "volumes": [f"{work.as_posix()}:/work",
                                                                          f"{(ROOT / 'tests' / 'load').as_posix()}:/scripts:ro"]}}}
            options = []
            for key, value in {"MODE": mode, "MAX_VUS": args.max_vus, **environment}.items():
                options += ["-e", f"{key}={value}"]
            return compose("-f", "-", "run", "--rm", "--no-deps", *options, "k6", "run", "--quiet", "--log-output=stdout",
                           "--summary-export", f"/work/k6-{mode}.json", "/scripts/event_flash.js",
                           input_text=json.dumps(overlay), timeout=3_600)

        base = "http://" + compose("port", "gateway", "8080").strip()
        prometheus = "http://" + compose("port", "prometheus", "9090").strip()
        prepare_offsets(compose)

        def gate_open():
            from urllib.parse import urlencode
            from urllib.request import urlopen
            with urlopen(prometheus + "/api/v1/query?" + urlencode({"query": "commerce_order_admission_open"}), timeout=5) as r:
                rows = json.load(r)["data"]["result"]
            return rows and all(float(row["value"][1]) == 1 for row in rows)

        # 구매자·판매자·상품은 SQL로, 주소는 실제 API로 만든다.
        seller, event_product, normal_product = (str(uuid.uuid4()) for _ in range(3))
        sql(f"""
INSERT INTO commerce.buyer_accounts (id,email_normalized,password_hash,display_name,status,created_at,updated_at,version)
SELECT md5('load-buyer-'||g)::uuid, 'load-'||g||'@load.test','test','load','ACTIVE',now(),now(),0
FROM generate_series(0,{prepared - 1}) g;
INSERT INTO commerce.seller_accounts (id,email_normalized,password_hash,display_name,status,payout_account_ref,created_at,updated_at,version)
VALUES ('{seller}','{seller}@load.test','test','load','ACTIVE','load-account',now(),now(),0);
INSERT INTO commerce.products (id,seller_id,name,unit_price,status,created_at,updated_at,version) VALUES
('{event_product}','{seller}','load event',{NORMAL_PRICE},'ON_SALE',now(),now(),0),
('{normal_product}','{seller}','load normal',{NORMAL_PRICE},'ON_SALE',now(),now(),0);
INSERT INTO commerce.product_inventory (id,product_id,available_qty,reserved_qty,event_allocated_qty,sold_qty,created_at,updated_at,version) VALUES
('{uuid.uuid4()}','{event_product}',{args.quota},0,0,0,now(),now(),0),
('{uuid.uuid4()}','{normal_product}',{NORMAL_STOCK},0,0,0,now(),now(),0);
""")

        epoch = json.loads(compose("config", "--format", "json"))["services"]["gateway"]["environment"]["AUTH_SESSION_EPOCH"]
        seconds, micros = compose("exec", "-T", "redis", "sh", "-c",
                                  'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw TIME').split()
        now = int(seconds) * 1000 + int(micros) // 1000

        def session(stream, kind, actor):
            token = secrets.token_urlsafe(32)
            stream.append(command("HSET", f"commerce-settle:auth:session:{epoch}:{hashlib.sha256(token.encode()).hexdigest()}",
                               "actorKind", kind, "actorId", actor, "purpose", "SESSION", "authVersion", "0",
                               "issuedAt", str(now), "expiresAt", str(now + 6 * 3_600_000)))
            stream.append(command("HSET", f"commerce-settle:auth:subject:{epoch}:{kind}:{actor}", "authVersion", "0", "state", "ACTIVE"))
            return token

        stream = []
        seller_token = session(stream, "SELLER", seller)
        tokens = [session(stream, "BUYER", buyer_id(i)) for i in range(prepared)]
        compose("exec", "-T", "redis", "sh", "-c", 'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --pipe',
                input_text="".join(stream), timeout=600)
        (work / "buyers-seed.json").write_text(json.dumps([[t, None] for t in tokens]))
        started = time.monotonic()
        k6("seed", SEED_VUS=50)
        # 개발 fixture의 시연 구매자 주소도 같은 테이블에 있으므로 시험 구매자 것만 센다.
        addresses = dict(line.split("|") for line in sql("""
select a.buyer_id, a.id from commerce.buyer_addresses a join commerce.buyer_accounts b on b.id=a.buyer_id
where a.deleted_at is null and b.email_normalized like '%@load.test'""").splitlines())
        assert len(addresses) == prepared, f"{len(addresses)} of {prepared} addresses were created"
        (work / "buyers.json").write_text(json.dumps([[t, addresses[buyer_id(i)]] for i, t in enumerate(tokens)]))
        print(json.dumps({"seeded": prepared, "seconds": round(time.monotonic() - started)}), flush=True)

        # 이벤트 공개 전에 같은 주문 API를 일반 상품으로 호출해 JIT·연결 풀을 데운다. 이벤트 재고·참여는 건드리지 않고,
        # 예열 주문도 일반 상품 예약으로 남으므로 아래 일반 상품 재고 보존 검사에 함께 들어간다.
        warmup = None
        if warmup_buyers:
            normal_version = int(sql(f"select version from commerce.products where id='{normal_product}'"))
            (work / "plan-warmup.json").write_text(json.dumps({
                "normal": {"productId": normal_product, "productVersion": normal_version, "price": NORMAL_PRICE}}))
            until(gate_open, "order admission did not open", 300)
            k6("warmup", WARMUP_SECONDS=args.warmup_seconds, WARMUP_RATE=args.warmup_rate, WARMUP_BUYERS=warmup_buyers)
            warmup_metrics = json.loads((work / "k6-warmup.json").read_text())["metrics"]
            warmup = {"buyers": warmup_buyers, "outcomes": outcome_counts(warmup_metrics),
                      "dropped": int(warmup_metrics.get("dropped_iterations", {}).get("count", 0))}
            print(json.dumps({"warmup": warmup}), flush=True)

        # 이미 시작한 기간으로 공개하면 바로 OPEN이 되어 시작 처리 배치를 기다리지 않는다.
        def seller_call(method, path, body=None):
            status, result = gateway_json(base, method, path, body, seller_token, {"Idempotency-Key": str(uuid.uuid4())})
            assert status in (200, 201), (method, path, status, result)
            return result

        start = datetime.now(timezone.utc) - timedelta(minutes=1)
        event = seller_call("POST", "/api/sellers/me/events", {
            "name": "load flash", "productId": event_product, "eventPrice": EVENT_PRICE, "quota": args.quota,
            "startsAt": start.isoformat(), "endsAt": (start + timedelta(hours=6)).isoformat()})
        event = seller_call("POST", f"/api/sellers/me/events/{event['id']}/publication", {"expectedVersion": event["version"]})
        assert event["status"] == "OPEN", event
        versions = dict(line.split("|") for line in sql(
            f"select id, version from commerce.products where id in ('{event_product}','{normal_product}')").splitlines())
        (work / "plan-flash.json").write_text(json.dumps({
            "event": {"eventId": event["id"], "eventVersion": event["version"], "productId": event_product,
                      "productVersion": int(versions[event_product]), "price": EVENT_PRICE},
            "normal": {"productId": normal_product, "productVersion": int(versions[normal_product]), "price": NORMAL_PRICE}}))
        until(gate_open, "order admission did not open", 300)

        # 주문 트랜잭션의 행 잠금 대기를 1초 간격으로 표본 추출한다.
        # 같은 표본에서 이벤트의 예약+판매 수를 읽어 수량이 처음 다 찬 시각(k6 시작 기준 초)을 남긴다.
        samples, stop = [], threading.Event()
        load_started = time.monotonic()

        def sample():
            while not stop.is_set():
                try:
                    row = sql(f"""select count(*) filter (where wait_event_type='Lock') || '|' || count(*) filter (where state='active')
|| '|' || (select reserved_count + sold_count from commerce.sale_events where id='{event['id']}')
from pg_stat_activity where datname='commerce'""")
                    samples.append([round(time.monotonic() - load_started, 1), *(int(v) for v in row.split("|"))])
                except RuntimeError:
                    pass
                stop.wait(1)

        sampler = threading.Thread(target=sample, daemon=True)
        sampler.start()
        try:
            output = k6("flash", PEAK_SECONDS=args.peak_seconds, PEAK_RATE=args.peak_rate, TAIL_SECONDS=args.tail_seconds,
                        TAIL_RATE=args.tail_rate, GAUGE_RATE=args.gauge_rate, WARMUP_BUYERS=warmup_buyers)
        finally:
            stop.set()
            sampler.join()
        (work / "k6.txt").write_text(output)
        metrics = json.loads((work / "k6-flash.json").read_text())["metrics"]
        latency = {name.split("{", 1)[0].removeprefix("order_").removesuffix("_duration") + "." + name.split("outcome:", 1)[1].rstrip("}"):
                   {key: round(metrics[name][key]) for key in ["p(50)", "p(95)", "p(99)", "max"]}
                   for name in metrics if "{outcome:" in name and metrics[name].get("max")}
        counters = outcome_counts(metrics)
        sold_out_at = next((s[0] for s in samples if s[3] >= args.quota), None)

        counts = dict(line.split("|") for line in sql(f"""
select 'event', status||'/'||reserved_count||'/'||sold_count||'/'||quota from commerce.sale_events where id='{event['id']}'
union all select 'participations', count(*)::text from commerce.event_participations
  where event_id='{event['id']}' and status in ('RESERVED','CONFIRMED','REFUNDED')
union all select 'duplicateBuyers', count(*)::text from (select buyer_id from commerce.event_participations
  where event_id='{event['id']}' and status in ('RESERVED','CONFIRMED','REFUNDED') group by buyer_id having count(*)>1) d
union all select 'eventOrders', count(*)::text from commerce.orders where event_id='{event['id']}'
union all select 'eventReservations', count(*)::text from commerce.inventory_reservations where event_id='{event['id']}' and status='RESERVED'
union all select 'normalOrders', count(*)::text from commerce.orders where product_id='{normal_product}'
union all select 'eventInventory', available_qty||'/'||reserved_qty||'/'||event_allocated_qty||'/'||sold_qty
  from commerce.product_inventory where product_id='{event_product}'
union all select 'normalInventory', available_qty||'/'||reserved_qty||'/'||event_allocated_qty||'/'||sold_qty
  from commerce.product_inventory where product_id='{normal_product}'
""").splitlines())
        status, gauge = gateway_json(base, "GET", f"/api/events/{event['id']}/gauge")
        event_status, reserved, sold, quota = counts["event"].split("/")
        normal_qty = [int(v) for v in counts["normalInventory"].split("/")]
        event_qty = [int(v) for v in counts["eventInventory"].split("/")]
        checks = {
            "noOversell": int(reserved) + int(sold) <= int(quota),
            "noDuplicateBuyer": counts["duplicateBuyers"] == "0",
            "participationsMatchEvent": int(counts["participations"]) == int(reserved) + int(sold),
            "reservationsMatchOrders": counts["eventReservations"] == counts["eventOrders"] == counts["participations"],
            "eventStockConserved": sum(event_qty) == args.quota,
            "normalStockConserved": sum(normal_qty) == NORMAL_STOCK and normal_qty[1] == int(counts["normalOrders"]),
            "gaugeRead": status == 200,
        }
        summary = {
            "input": vars(args) | {"orders": orders},
            "warmup": warmup,
            "counts": counts, "gauge": gauge, "checks": checks,
            "outcomes": counters, "latencyMs": latency,
            "soldOutAtSeconds": sold_out_at, "dropped": int(metrics.get("dropped_iterations", {}).get("count", 0)),
            "lockWaitersMax": max((s[1] for s in samples), default=0),
            "activeMax": max((s[2] for s in samples), default=0),
            "timeline": samples,
            "scope": "A: Gateway+Commerce order admission and event reservation; payment consumers disabled",
        }
        (work / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False))
        print(output, flush=True)
        print(json.dumps(summary, ensure_ascii=False), flush=True)
        if not all(checks.values()):
            raise SystemExit("event load integrity checks failed: " + str([k for k, v in checks.items() if not v]))


if __name__ == "__main__":
    def stop(*_args):
        raise SystemExit(143)

    signal.signal(signal.SIGTERM, stop)
    main()
