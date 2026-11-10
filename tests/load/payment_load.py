
 #!/usr/bin/env python3
"""결제 부하·복구 시험(#133) 도구. 표준 라이브러리만 쓰고 Kafka·DB는 Compose 컨테이너 안의 CLI로 다룬다.

Commerce가 발행하는 것과 같은 `OrderPaymentRequested` v1을 `commerce-settle.commerce.v1`에 직접 넣는다. 측정 구간은 Payment 수신부터
finance-mock 승인과 결과 Outbox 발행까지다. 순서와 해석은 tests/load/README.md를 따른다.

    python tests/load/payment_load.py up         # 별도 프로젝트로 전체 스택 기동(수신 Listener는 꺼 둠)
    python tests/load/payment_load.py prepare    # 수신 그룹 offset·PG 시나리오·마일리지 지갑 준비
    python tests/load/payment_load.py enable     # payment-worker를 수신 Listener를 켜고 다시 만든다
    python tests/load/payment_load.py inject --rate 50 --seconds 60
    python tests/load/payment_load.py ramp --stages 50:60,100:60,200:60,400:60
    python tests/load/payment_load.py scenario loss     # latency·decline·loss·lookup-fail·restart
    python tests/load/payment_load.py replay            # 확인 대상 작업을 운영 재처리 API로 다시 대기
    python tests/load/payment_load.py reconcile         # 원장 ↔ Payment ↔ finance-mock 대조
    python tests/load/payment_load.py status
"""

import argparse
import csv
import json
import os
import re
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORK = Path(os.environ.get("LOAD_WORK_DIR", ROOT / "tests" / "load" / "work")).resolve()
PROJECT = os.environ.get("LOAD_PROJECT", "commerce-settle-load")
TOPIC = "commerce-settle.commerce.v1"
GROUP = "payment.v1"
REDIS_PLACEHOLDER = "0" * 40
SELLER = "00000000-0000-4000-8000-0000000005e1"
PRODUCT = "00000000-0000-4000-8000-0000000005a1"
BUYER_POOL = 200
WALLET_BALANCE = 100_000_000
PROVIDERS = ("PG_A", "PG_B", "PG_C", "EASY_PAY")
# 이름 → (승인 지연 ms 최소·최대, 확정 거절률, 응답 유실률, 조회 실패율)
SCENARIOS = {
    "load-pg-normal-v1": ((0, 0), "0", "0", "0"),
    "load-pg-latency-v1": ((200, 800), "0", "0", "0"),
    "load-pg-decline-v1": ((0, 0), "0.2", "0", "0"),
    "load-pg-loss-v1": ((0, 0), "0", "0.2", "0"),
    "load-pg-lookup-fail-v1": ((0, 0), "0", "0.2", "0.5"),
}


def operator_credential():
    """시험 스택 payment-api의 운영자(operator:load) 자격. 처음 쓸 때 무작위로 만들고 Git에서 제외한 work에 둔다."""
    path = WORK / "operator.credential"
    if not path.exists():
        import secrets
        WORK.mkdir(parents=True, exist_ok=True)
        path.write_text(secrets.token_urlsafe(32), encoding="utf-8")
    return path.read_text(encoding="utf-8").strip()


def compose_env():
    env = dict(os.environ)
    env.setdefault("AUTH_REDIS_RUN_ID", REDIS_PLACEHOLDER)
    env.setdefault("LOAD_OPERATOR_CREDENTIAL", operator_credential())
    return env


def compose(*args, input_text=None, check=True, capture=True, env=None, timeout=None):
    command = [
        "docker", "compose", "-p", PROJECT,
        "-f", str(ROOT / "compose.yml"), "-f", str(ROOT / "tests" / "load" / "compose.load.yml"),
        *args,
    ]
    result = subprocess.run(
        command, cwd=ROOT, env=env or compose_env(), input=input_text, text=True, timeout=timeout,
        stdout=subprocess.PIPE if capture else None, stderr=subprocess.PIPE if capture else None,
    )
    if check and result.returncode != 0:
        raise SystemExit(f"{' '.join(args[:3])} failed:\n{result.stderr or ''}")
    return result.stdout or ""


def redis_run_id():
    """이 프로젝트의 Redis run_id. 공용 .env의 승인 값은 바꾸지 않고 환경 변수로만 넘긴다."""
    compose("up", "-d", "--wait", "redis")
    info = compose("exec", "-T", "redis", "sh", "-c",
                   'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli INFO server')
    match = re.search(r"^run_id:([0-9a-f]{40})\s*$", info, re.MULTILINE)
    if not match:
        raise SystemExit("Redis INFO server has no run_id")
    os.environ["AUTH_REDIS_RUN_ID"] = match[1]
    return match[1]


def psql(database, sql):
    out = compose("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database,
                  "-v", "ON_ERROR_STOP=1", "-At", "-F", "\t", "-c", sql)
    return [line.split("\t") for line in out.splitlines() if line]


def kafka(script, *args):
    return compose("exec", "-T", "kafka", f"/opt/kafka/bin/{script}",
                   "--bootstrap-server", "kafka:9092", *args)


def cmd_up(args):
    redis_run_id()
    env = compose_env()
    env["LOAD_PAYMENT_LISTENER_ENABLED"] = "false"
    compose("up", "-d", "--build", "--wait", "--wait-timeout", "600", capture=False, env=env)
    if STACK_RUNS.exists():
        STACK_RUNS.unlink()
    print(f"Stack {PROJECT} is up. Next: prepare")


def offsets(time_spec):
    out = kafka("kafka-get-offsets.sh", "--topic", TOPIC, "--time", time_spec)
    return {int(p): int(o) for _, p, o in (line.split(":") for line in out.split())}


def cmd_prepare(args):
    redis_run_id()
    described = kafka("kafka-consumer-groups.sh", "--describe", "--group", GROUP)
    has_offsets = any(line.startswith(GROUP) and TOPIC in line and line.split()[3] != "-"
                      for line in described.splitlines())
    if has_offsets:
        print(f"{GROUP} already has offsets on {TOPIC}; keeping them")
    else:
        start, end = offsets("-2"), offsets("-1")
        if any(start.values()) or any(end.values()):
            raise SystemExit(f"{TOPIC} is not empty ({end}); refusing to reset a new group")
        kafka("kafka-consumer-groups.sh", "--reset-offsets", "--group", GROUP,
              "--topic", TOPIC, "--to-earliest", "--execute")
        print(f"{GROUP} reset to earliest on empty {TOPIC}")

    rows = []
    for provider in PROVIDERS:
        days = {"PG_B": 6, "PG_C": 7}.get(provider, 5)
        for version, ((low, high), reject, loss, lookup_fail) in SCENARIOS.items():
            rows.append(
                f"(gen_random_uuid(), {days}, '{provider}', '{version}', {low}, {high}, {reject},"
                f" {loss}, {lookup_fail}, 0, 0, 0, 0, 0, 0, 20261103, clock_timestamp())")
    psql("finance_mock", """
        INSERT INTO finance_mock.mock_scenarios (id, settlement_delay_business_days, provider_id,
            scenario_version, min_delay_ms, max_delay_ms, rejection_rate, response_loss_rate,
            lookup_failure_rate, refund_min_delay_ms, refund_max_delay_ms, refund_rejection_rate,
            refund_response_loss_rate, lookup_min_delay_ms, lookup_max_delay_ms, seed, created_at)
        VALUES """ + ",\n".join(rows) + """
        ON CONFLICT (provider_id, scenario_version) DO NOTHING""")
    psql("finance_mock", f"""
        INSERT INTO finance_mock.mock_accounts (id, provider_id, account_ref, purpose,
            opening_balance, balance, reserved_amount, created_at, updated_at)
        SELECT gen_random_uuid(), 'EASY_PAY', 'load-wallet-' || n, 'WALLET', {WALLET_BALANCE},
            {WALLET_BALANCE}, 0, clock_timestamp(), clock_timestamp()
        FROM generate_series(0, {BUYER_POOL - 1}) n
        ON CONFLICT (provider_id, account_ref, purpose) DO NOTHING""")
    psql("payment", f"""
        INSERT INTO payment.mileage_wallets (id, buyer_id, balance, reserved_amount, created_at,
            updated_at, version)
        SELECT gen_random_uuid(), {buyer_sql('n')}, {WALLET_BALANCE}, 0, clock_timestamp(),
            clock_timestamp(), 0
        FROM generate_series(0, {BUYER_POOL - 1}) n
        ON CONFLICT (buyer_id) DO NOTHING""")
    print(f"Scenarios {list(SCENARIOS)} for {PROVIDERS}, {BUYER_POOL} buyer wallets and "
          f"EASY_PAY test wallets are ready. Next: enable")


def buyer(n):
    return f"00000000-0000-4000-8000-{0x100000000000 + n:012x}"


def buyer_sql(column):
    return f"('00000000-0000-4000-8000-' || lpad(to_hex(17592186044416 + {column}), 12, '0'))::uuid"


def cmd_enable(args):
    redis_run_id()
    env = compose_env()
    env["LOAD_PAYMENT_LISTENER_ENABLED"] = "true"
    if args.scenario:
        env["LOAD_PG_SCENARIO"] = args.scenario
    compose("up", "-d", "--wait", "--no-deps", "--force-recreate", "payment-worker",
            capture=False, env=env)
    print(f"payment-worker listens on {TOPIC} with scenario "
          f"{env.get('LOAD_PG_SCENARIO', 'load-pg-normal-v1')}")


def request_event(index, mix):
    order_id, cycle_id, event_id = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    n = index % BUYER_POOL
    gross = 10_000 + (index % 50) * 1_000
    mileage, method, account = 0, "CREDIT_CARD", "load-card"
    kind = mix[index % len(mix)]
    if kind == "mixed":
        mileage = 1_000
    elif kind == "mileage":
        mileage, method, account = gross, None, None
    elif kind == "easy":
        method, account = "EASY_PAY", f"load-wallet-{n}"
    now = datetime.now(timezone.utc)
    envelope = {
        "eventId": str(event_id),
        "eventType": "OrderPaymentRequested",
        "eventVersion": 1,
        "occurredAt": now.isoformat(timespec="microseconds").replace("+00:00", "Z"),
        "aggregateId": str(order_id),
        "aggregateVersion": 0,
        "payload": {
            "orderId": str(order_id),
            "buyerId": buyer(n),
            "sellerId": SELLER,
            "paymentCycleId": str(cycle_id),
            "productId": PRODUCT,
            "orderVersion": 0,
            "status": "PAYMENT_PENDING",
            "policyVersion": "load-v1",
            "paymentWaitUntil": (now + timedelta(minutes=10)).isoformat().replace("+00:00", "Z"),
            "externalMethod": method,
            "methodAccountRef": account,
            "grossAmount": gross,
            "couponAmount": 0,
            "totalAmount": gross,
            "mileageAmount": mileage,
            "externalAmount": gross - mileage,
            "sellerCouponAmount": 0,
            "platformCouponAmount": 0,
            "settlementBaseAmount": gross,
        },
    }
    return order_id, kind, gross, mileage, json.dumps(envelope, separators=(",", ":"))


def parse_stages(text):
    """`50:60,100:60` → [(50, 60), (100, 60)]. 초당 건수:유지 초."""
    stages = []
    for part in text.split(","):
        rate, seconds = (int(value) for value in part.split(":"))
        if rate < 1 or seconds < 1:
            raise SystemExit("stage rate and seconds must be positive")
        stages.append((rate, seconds))
    return stages


def produce(stages, mix, directory, offset, marks, stop_file=None, stop_event=None):
    """단계별 도착률로 요청을 넣는다. 각 단계의 시작·끝 시각을 marks에 남긴다."""
    producer = subprocess.Popen(
        ["docker", "compose", "-p", PROJECT, "-f", str(ROOT / "compose.yml"),
         "-f", str(ROOT / "tests" / "load" / "compose.load.yml"),
         "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
         "--bootstrap-server", "kafka:9092", "--topic", TOPIC,
         "--reader-property", "parse.key=true", "--reader-property", "key.separator=\t",
         "--command-property", "acks=all", "--command-property", "linger.ms=5"],
        cwd=ROOT, env=compose_env(), stdin=subprocess.PIPE, text=True)
    index = offset
    try:
        with open(directory / "requests.csv", "a", newline="", encoding="utf-8") as ledger:
            writer = csv.writer(ledger)
            for number, (rate, seconds) in enumerate(stages):
                begin_wall = datetime.now(timezone.utc)
                begin = time.monotonic()
                behind = 0.0
                sent = 0
                for step in range(rate * seconds):
                    if (stop_event and stop_event.is_set()) or (stop_file and Path(stop_file).is_file()):
                        break
                    delay = begin + step / rate - time.monotonic()
                    if delay > 0:
                        if stop_event is not None:
                            stop_event.wait(delay)
                        else:
                            time.sleep(delay)
                    else:
                        behind = max(behind, -delay)
                    if (stop_event and stop_event.is_set()) or (stop_file and Path(stop_file).is_file()):
                        break
                    order_id, kind, gross, mileage, body = request_event(index, mix)
                    index += 1
                    sent += 1
                    producer.stdin.write(f"{order_id}\t{body}\n")
                    writer.writerow([order_id, kind, gross, mileage, number,
                                     datetime.now(timezone.utc).isoformat()])
                    if step % max(1, rate // 10) == 0:
                        producer.stdin.flush()
                producer.stdin.flush()
                marks.append({"stage": number, "rate": rate, "seconds": seconds,
                              "start": begin_wall.isoformat(),
                              "end": datetime.now(timezone.utc).isoformat(),
                              "sent": sent,
                              "stopped": sent < rate * seconds,
                              "elapsed": round(time.monotonic() - begin, 2),
                              "maxBehindSeconds": round(behind, 2)})
                if sent < rate * seconds:
                    break
    finally:
        try:
            producer.stdin.close()
        except BrokenPipeError:
            pass
        try:
            producer.wait(timeout=10)
        except subprocess.TimeoutExpired:
            producer.terminate()
            try:
                producer.wait(timeout=2)
            except subprocess.TimeoutExpired:
                producer.kill()
                producer.wait(timeout=2)
    if producer.returncode != 0:
        raise SystemExit(f"producer exited with {producer.returncode}")
    return index


def snapshot():
    lag = 0
    for line in kafka("kafka-consumer-groups.sh", "--describe", "--group", GROUP).splitlines():
        parts = line.split()
        if len(parts) > 5 and parts[0] == GROUP and parts[1] == TOPIC and parts[5].isdigit():
            lag += int(parts[5])
    row = psql("payment", """
        SELECT clock_timestamp(),
          (SELECT count(*) FROM payment.inbox WHERE event_type = 'OrderPaymentRequested'),
          (SELECT count(*) FROM payment.inbox
            WHERE event_type = 'OrderPaymentRequested' AND status = 'WAITING'),
          (SELECT count(*) FROM payment.payments WHERE status = 'CONFIRMED'),
          (SELECT count(*) FROM payment.payments WHERE status = 'DECLINED'),
          (SELECT count(*) FROM payment.payments
            WHERE status IN ('PENDING', 'PROCESSING', 'UNKNOWN')),
          (SELECT count(*) FROM payment.payment_jobs WHERE status = 'REVIEW_REQUIRED'),
          (SELECT count(*) FROM payment.inbox WHERE status = 'REVIEW_REQUIRED'),
          (SELECT count(*) FROM payment.outbox WHERE status <> 'PUBLISHED')""")[0]
    names = ("at", "received", "waiting", "confirmed", "declined", "unfinished",
             "jobsReview", "inboxReview", "outboxPending")
    values = dict(zip(names, row))
    return {"kafkaLag": lag, **{k: (v if k == "at" else int(v)) for k, v in values.items()}}


def cmd_inject(args):
    run = args.run or datetime.now().strftime("%Y%m%d-%H%M%S")
    directory = WORK / run
    directory.mkdir(parents=True, exist_ok=True)
    marks = []
    remember_run(run)
    produce([(args.rate, args.seconds)], args.mix.split(","), directory, args.offset, marks)
    print(f"Injected {marks[0]['sent']} requests in {marks[0]['elapsed']}s into run {run}")


def cmd_status(args):
    print(json.dumps(snapshot(), ensure_ascii=False))


def latency(start, end):
    """수신(Inbox 저장, DB 시각)부터 결제 확정까지. 같은 Docker VM의 시계라 호스트 시계 오차가 들어가지 않는다."""
    row = psql("payment", f"""
        SELECT count(*),
          round(percentile_cont(0.50) WITHIN GROUP (ORDER BY ms)::numeric, 1),
          round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric, 1),
          round(percentile_cont(0.99) WITHIN GROUP (ORDER BY ms)::numeric, 1),
          round(max(ms)::numeric, 1)
        FROM (SELECT extract(epoch FROM p.confirmed_at - i.created_at) * 1000 AS ms
              FROM payment.inbox i JOIN payment.payments p ON p.order_id = i.aggregate_id
              WHERE i.event_type = 'OrderPaymentRequested' AND p.status = 'CONFIRMED'
                AND i.created_at >= '{start}' AND i.created_at < '{end}') t""")[0]
    return dict(zip(("count", "p50Ms", "p95Ms", "p99Ms", "maxMs"),
                    [int(row[0])] + [float(v) if v else None for v in row[1:]]))


WORKER_SETTINGS = ("PAYMENT_REQUEST_CONCURRENCY", "PAYMENT_APPROVAL_CONCURRENCY", "PAYMENT_DB_POOL_SIZE")


def worker_settings():
    """실행 중인 payment-worker에 실제로 들어간 처리 설정. 기동 셸의 변수와 달라도 결과를 잘못 해석하지 않게 한다.

    Worker가 죽어 읽을 수 없으면 None을 돌려준다. 실패한 실행도 남은 적체를 요약에 남겨야 하기 때문이다.
    """
    out = compose("exec", "-T", "payment-worker", "env", check=False)
    values = dict(line.split("=", 1) for line in out.splitlines() if "=" in line)
    if not values:
        print("payment-worker settings unavailable; recording workerSettings as null", file=sys.stderr)
        return None
    return {name: values.get(name) for name in WORKER_SETTINGS}


def cmd_ramp(args):
    """단계적으로 도착률을 올리며 일정 간격으로 상태를 기록하고, 끝나면 단계별 처리량·적체·지연을 요약한다."""
    import threading

    stages = parse_stages(args.stages)
    if args.kill_after < 0 or args.kill_after >= sum(seconds for _, seconds in stages):
        raise SystemExit("kill-after must be nonnegative and earlier than the end of injection")
    run = args.run or "ramp-" + datetime.now().strftime("%Y%m%d-%H%M%S")
    directory = WORK / run
    directory.mkdir(parents=True, exist_ok=True)
    marks, failure = [], []
    stop_event = threading.Event()
    remember_run(run)

    def worker():
        try:
            produce(stages, args.mix.split(","), directory, args.offset, marks,
                    getattr(args, "stop_file", None), stop_event)
        except BaseException as error:  # noqa: BLE001 - 주 스레드에서 보고한다
            failure.append(error)

    thread = threading.Thread(target=worker)
    samples = [{**snapshot(), "elapsed": 0.0}]
    origin = time.monotonic()
    fault = {}
    fault_thread = None
    if args.kill_after:
        def kill_worker():
            try:
                if stop_event.wait(args.kill_after):
                    return
                fault["killedAt"] = round(time.monotonic() - origin, 2)
                compose("kill", "payment-worker", timeout=30)
                env = compose_env()
                env["LOAD_PAYMENT_LISTENER_ENABLED"] = "true"
                if args.scenario:
                    env["LOAD_PG_SCENARIO"] = args.scenario
                compose("up", "-d", "--wait", "--no-deps", "payment-worker", env=env, timeout=120)
                fault["restartedAt"] = round(time.monotonic() - origin, 2)
            except BaseException as error:  # compose 실패(SystemExit)도 주 스레드에서 보고한다
                failure.append(error)
        fault_thread = threading.Thread(target=kill_worker)
        fault_thread.start()
    thread.start()
    drain_started = None
    restart_incomplete = False
    try:
        with open(directory / "samples.jsonl", "a", encoding="utf-8") as out:
            out.write(json.dumps(samples[0]) + "\n")
            while True:
                time.sleep(args.interval)
                sample = snapshot()
                sample["elapsed"] = round(time.monotonic() - origin, 2)
                previous = samples[-1]
                # 상태 조회에도 시간이 걸리므로 설정 간격 대신 실제 경과 시간으로 나눈다.
                seconds = max(0.001, sample["elapsed"] - previous["elapsed"])
                sample["confirmedPerSecond"] = round(
                    (sample["confirmed"] - previous["confirmed"]) / seconds, 1)
                sample["receivedPerSecond"] = round(
                    (sample["received"] - previous["received"]) / seconds, 1)
                sample["stage"] = len(marks) if thread.is_alive() else "drain"
                samples.append(sample)
                out.write(json.dumps(sample) + "\n")
                out.flush()
                print(json.dumps({k: sample[k] for k in (
                    "stage", "receivedPerSecond", "confirmedPerSecond", "unfinished", "waiting",
                    "kafkaLag", "outboxPending", "jobsReview")}), flush=True)
                if failure:
                    raise SystemExit(f"load worker failed: {failure[0]}")
                if thread.is_alive():
                    continue
                drain_started = drain_started or time.monotonic()
                idle = (sample["unfinished"] == 0 and sample["waiting"] == 0
                        and sample["kafkaLag"] == 0 and sample["outboxPending"] == 0)
                if idle and (fault_thread is None or not fault_thread.is_alive()):
                    break
                if time.monotonic() - drain_started > args.drain:
                    restart_incomplete = fault_thread is not None and fault_thread.is_alive()
                    print("drain timeout; leaving remaining backlog for inspection")
                    break

    finally:
        stop_event.set()
        thread.join()
        if fault_thread is not None:
            fault_thread.join()

    if restart_incomplete:
        raise SystemExit("worker restart did not complete before drain timeout")
    if failure:
        raise SystemExit(f"load worker failed: {failure[0]}")
    if fault_thread is not None:
        if fault_thread.is_alive():
            raise SystemExit("worker restart did not complete before drain timeout")
        fault_thread.join()
        if "restartedAt" not in fault:
            raise SystemExit("worker restart did not complete")

    summary = {"run": run, "mix": args.mix, "scenario": args.scenario, "fault": fault,
               "workerSettings": worker_settings(), "stages": []}
    for mark in marks:
        in_stage = [s for s in samples[1:] if s.get("stage") == mark["stage"]]
        steady = in_stage[1:] or in_stage  # 첫 구간은 warm-up으로 뺀다
        summary["stages"].append({
            **mark,
            "confirmedPerSecond": round(
                sum(s["confirmedPerSecond"] for s in steady) / max(1, len(steady)), 1),
            "unfinishedStart": in_stage[0]["unfinished"] if in_stage else None,
            "unfinishedEnd": in_stage[-1]["unfinished"] if in_stage else None,
            "maxKafkaLag": max((s["kafkaLag"] for s in in_stage), default=None),
            "latency": latency(mark["start"], mark["end"]),
        })
    drain = [s for s in samples[1:] if s.get("stage") == "drain"]
    summary["drainSeconds"] = round(samples[-1]["elapsed"] - drain[0]["elapsed"], 1) if drain else 0
    summary["injectionEndedAt"] = drain[0]["elapsed"] if drain else None
    final = samples[-1]
    summary["final"] = {k: final[k] for k in (
        "received", "confirmed", "declined", "unfinished", "waiting", "kafkaLag",
        "outboxPending", "jobsReview", "inboxReview")}
    (directory / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


STACK_RUNS = WORK / "stack-runs.txt"


def remember_run(run):
    """현재 스택에서 실행한 주입 기록. 대조는 기본으로 이 스택의 원장만 읽는다."""
    directory = WORK / run
    directory.mkdir(parents=True, exist_ok=True)
    # 최초 관측·producer 시작이 실패해도 등록된 실행은 빈 원장으로 대조할 수 있어야 한다.
    with (directory / "requests.csv").open("a", encoding="utf-8"):
        pass
    runs = STACK_RUNS.read_text(encoding="utf-8").split() if STACK_RUNS.exists() else []
    if run not in runs:
        with open(STACK_RUNS, "a", encoding="utf-8") as out:
            out.write(run + "\n")


def ledger(runs):
    """주입 원장. orderId → (종류, 총액, 마일리지, 구매자 순번)."""
    rows = {}
    for run in runs:
        with open(WORK / run / "requests.csv", encoding="utf-8") as source:
            for row in csv.reader(source):
                if row[0] in rows:
                    raise SystemExit(f"duplicate orderId {row[0]} in ledger")
                rows[row[0]] = (row[1], int(row[2]), int(row[3]))
    return rows


def cmd_reconcile(args):
    """주입 원장 ↔ Payment(결제·시도·마일리지·Outbox) ↔ finance-mock(거래·간편결제 지갑)을 대조한다."""
    runs = args.runs.split(",") if args.runs else STACK_RUNS.read_text(encoding="utf-8").split()
    expected = ledger(runs)
    problems = []

    def problem(kind, detail):
        problems.append((kind, detail))

    payments = {}
    for order, payment, cycle, buyer_id, status, total, mileage, external in psql("payment", """
            SELECT order_id, id, current_cycle_id, buyer_id, status, total_amount,
                   mileage_amount, external_amount FROM payment.payments"""):
        if order in payments:
            problem("결제 중복", order)
        payments[order] = (payment, cycle, buyer_id, status, int(total), int(mileage),
                           int(external))
    for order, (kind, gross, mileage) in expected.items():
        found = payments.get(order)
        if found is None:
            problem("결제 누락", order)
            continue
        _, _, _, status, total, paid_mileage, external = found
        if (total, paid_mileage, external) != (gross, mileage, gross - mileage):
            problem("결제 금액 불일치", f"{order} {total}/{paid_mileage}/{external}")
        if status not in ("CONFIRMED", "DECLINED"):
            problem("미완료 결제", f"{order} {status}")
        if kind == "mileage" and status != "CONFIRMED":
            problem("전액 마일리지 미확정", order)
    for order in payments.keys() - expected.keys():
        problem("원장에 없는 결제", order)

    attempts = {}
    for payment, provider, key, amount, status in psql("payment", """
            SELECT payment_id, provider_id, business_key, amount, status
            FROM payment.payment_attempts"""):
        if payment in attempts:
            problem("승인 시도 중복", payment)
        attempts[payment] = (provider, key, int(amount), status)
    mock = {}
    for provider, key, amount, status, account in psql("finance_mock", """
            SELECT provider_id, business_key, amount, status, coalesce(account_id::text, '')
            FROM finance_mock.mock_transactions WHERE operation = 'APPROVE'"""):
        mock[(provider, key)] = (int(amount), status, account)
    used = set()
    for order, (payment, cycle, _, status, _, _, external) in payments.items():
        attempt = attempts.get(payment)
        if external == 0:
            if attempt:
                problem("전액 마일리지에 승인 시도", order)
            continue
        if attempt is None:
            problem("승인 시도 누락", order)
            continue
        provider, key, amount, attempt_status = attempt
        if key != cycle or amount != external:
            problem("승인 시도 업무 키·금액 불일치", order)
        transaction = mock.get((provider, key))
        if transaction is None:
            problem("대역 거래 누락", f"{order} {status}")
            continue
        used.add((provider, key))
        mock_amount, mock_status, _ = transaction
        if mock_amount != external:
            problem("대역 거래 금액 불일치", order)
        if (status, attempt_status, mock_status) not in (
                ("CONFIRMED", "APPROVED", "SUCCEEDED"), ("DECLINED", "DECLINED", "DECLINED")):
            problem("결제·시도·대역 상태 불일치", f"{order} {status}/{attempt_status}/{mock_status}")
    for key in mock.keys() - used:
        problem("결제에 연결되지 않은 대역 거래", "/".join(key))

    debited = {}
    for order, (_, _, buyer_id, status, _, mileage, _) in payments.items():
        if status == "CONFIRMED" and mileage:
            debited[buyer_id] = debited.get(buyer_id, 0) + mileage
    for buyer_id, balance, reserved in psql("payment", """
            SELECT buyer_id, balance, reserved_amount FROM payment.mileage_wallets"""):
        if int(reserved) != 0:
            problem("마일리지 예약 잔존", f"{buyer_id} {reserved}")
        if int(balance) != WALLET_BALANCE - debited.get(buyer_id, 0):
            problem("마일리지 잔액 불일치", f"{buyer_id} {balance}")
    entry_rows = psql("payment", """
            SELECT p.id, p.status,
              count(*) FILTER (WHERE e.kind = 'RESERVE'),
              count(*) FILTER (WHERE e.kind = 'DEBIT'),
              count(*) FILTER (WHERE e.kind = 'RELEASE')
            FROM payment.payments p LEFT JOIN payment.mileage_entries e ON e.payment_id = p.id
            WHERE p.mileage_amount > 0
            GROUP BY p.id, p.status""")
    for payment, status, reserve, debit, release in entry_rows:
        counts = (int(reserve), int(debit), int(release))
        if counts != ((1, 1, 0) if status == "CONFIRMED" else (1, 0, 1)):
            problem("마일리지 원장 불일치", f"{payment} {status} {counts}")

    spent = {}
    for account, amount in psql("finance_mock", """
            SELECT account_id, sum(amount) FROM finance_mock.mock_transactions
            WHERE provider_id = 'EASY_PAY' AND operation = 'APPROVE' AND status = 'SUCCEEDED'
            GROUP BY 1"""):
        spent[account] = int(amount)
    for account, balance, reserved, opening in psql("finance_mock", """
            SELECT id, balance, reserved_amount, opening_balance FROM finance_mock.mock_accounts
            WHERE provider_id = 'EASY_PAY' AND account_ref LIKE 'load-wallet-%'"""):
        if int(reserved) != 0 or int(balance) != int(opening) - spent.get(account, 0):
            problem("간편결제 지갑 불일치", f"{account} {balance}/{reserved}")

    events = {}
    for aggregate, event_type, count, published in psql("payment", """
            SELECT aggregate_id, event_type, count(*), bool_and(status = 'PUBLISHED')
            FROM payment.outbox GROUP BY 1, 2"""):
        events[(aggregate, event_type)] = (int(count), published == "t")
    for order, (payment, _, _, status, _, _, _) in payments.items():
        terminal = "PaymentConfirmed" if status == "CONFIRMED" else "PaymentDeclined"
        for event_type in ("PaymentAccepted", terminal):
            found = events.get((payment, event_type))
            if found is None:
                problem("결과 이벤트 누락", f"{order} {event_type}")
            elif found[0] != 1:
                problem("결과 이벤트 중복", f"{order} {event_type} {found[0]}")
            elif not found[1]:
                problem("결과 이벤트 미발행", f"{order} {event_type}")

    review = psql("payment", """
        SELECT (SELECT count(*) FROM payment.inbox WHERE status <> 'APPLIED'),
               (SELECT count(*) FROM payment.payment_jobs WHERE status <> 'DONE'),
               (SELECT count(*) FROM payment.inbox_conflicts)""")[0]
    if int(review[0]) or int(review[1]):
        problem("미처리 Inbox·작업", f"inbox {review[0]}, jobs {review[1]}")
    if int(review[2]):
        problem("Inbox 본문 충돌", review[2])

    statuses = {}
    for _, _, _, status, _, _, _ in payments.values():
        statuses[status] = statuses.get(status, 0) + 1
    kinds = {}
    for kind, _, _ in expected.values():
        kinds[kind] = kinds.get(kind, 0) + 1
    by_kind = {}
    for kind, detail in problems:
        by_kind.setdefault(kind, []).append(detail)
    report = {
        "runs": runs,
        "requests": len(expected),
        "requestKinds": kinds,
        "payments": statuses,
        "attempts": len(attempts),
        "mockApprovals": len(mock),
        "problems": {kind: {"count": len(details), "examples": details[:5]}
                     for kind, details in by_kind.items()},
    }
    name = args.output or f"reconcile-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
    (WORK / name).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if problems:
        raise SystemExit(f"{len(problems)} reconciliation problems")
    print("대조 통과: 누락·중복·금액 불일치 0")


def cmd_replay(args):
    """확인 대상(REVIEW_REQUIRED) 결제 작업을 운영 재처리 API로 다시 대기시킨다. 원업무 키를 그대로 쓰므로 원거래 조회부터 재개한다."""
    jobs = psql("payment", """
        SELECT id, version FROM payment.payment_jobs WHERE status = 'REVIEW_REQUIRED'
        ORDER BY created_at""")
    credential = operator_credential()
    for job_id, version in jobs:
        body = json.dumps({"expectedVersion": int(version), "reason": args.reason})
        out = compose(
            "exec", "-T", "payment-api", "curl", "-sS", "-o", "/dev/stdout", "-w", "\n%{http_code}",
            "-X", "POST", f"http://localhost:8080/internal/payment-jobs/{job_id}/replays",
            "-H", f"Authorization: Bearer {credential}",
            "-H", f"Idempotency-Key: load-replay-{job_id}-{version}",
            "-H", "Content-Type: application/json", "-d", body)
        response, _, code = out.rpartition("\n")
        print(job_id, code.strip(), response.strip()[:200])
        if code.strip() != "202":
            raise SystemExit(f"replay failed for {job_id}: HTTP {code.strip() or 'missing status'}")
    print(f"replayed {len(jobs)} jobs")


FAULTS = {
    # 이름 → (PG 시나리오, 실행 중 Worker 강제 종료 시각)
    "latency": ("load-pg-latency-v1", 0),
    "decline": ("load-pg-decline-v1", 0),
    "loss": ("load-pg-loss-v1", 0),
    "lookup-fail": ("load-pg-lookup-fail-v1", 0),
    "restart": ("load-pg-normal-v1", 25),
}


def cmd_scenario(args):
    """장애 시나리오 하나를 실행한다. PG 시나리오로 Worker를 다시 만들고, 일정 도착률로 넣은 뒤 적체가 비면 대조한다."""
    version, kill_after = FAULTS[args.name]
    cmd_enable(argparse.Namespace(scenario=version))
    run = args.run or f"fault-{args.name}-" + datetime.now().strftime("%H%M%S")
    cmd_ramp(argparse.Namespace(
        stages=f"{args.rate}:{args.seconds}", mix=args.mix, run=run, offset=args.offset,
        interval=5, drain=args.drain, kill_after=kill_after, scenario=version))
    cmd_reconcile(argparse.Namespace(runs=None, output=f"reconcile-{run}.json"))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("up").set_defaults(func=cmd_up)
    sub.add_parser("prepare").set_defaults(func=cmd_prepare)
    enable = sub.add_parser("enable")
    enable.add_argument("--scenario", help="PAYMENT_SCENARIO_VERSION, 기본 load-pg-normal-v1")
    enable.set_defaults(func=cmd_enable)
    for name, func in (("inject", cmd_inject), ("ramp", cmd_ramp)):
        command = sub.add_parser(name)
        command.add_argument("--mix", default="card", help="card,mixed,mileage,easy 순환")
        command.add_argument("--run", help="결과 디렉터리 이름(tests/load/work/<run>)")
        command.add_argument("--offset", type=int, default=0, help="구매자·금액 순환 시작 위치")
        command.set_defaults(func=func)
        if name == "inject":
            command.add_argument("--rate", type=int, default=10)
            command.add_argument("--seconds", type=int, default=10)
        else:
            command.add_argument("--stop-file", type=Path,
                                 help="이 파일이 생기면 추가 주입을 멈추고 이미 접수한 작업을 관측한다")
            command.add_argument("--stages", default="50:60,100:60,200:60",
                                 help="초당 건수:유지 초, 쉼표로 구분")
            command.add_argument("--interval", type=int, default=10, help="기록 간격(초)")
            command.add_argument("--drain", type=int, default=300,
                                 help="주입이 끝난 뒤 적체가 비기를 기다리는 최대 초")
            command.add_argument("--kill-after", type=int, default=0,
                                 help="시작 뒤 이 초에 payment-worker를 강제 종료하고 다시 띄운다")
            command.add_argument("--scenario", help="재기동할 때 쓸 PG 시나리오(기록용)")
    sub.add_parser("status").set_defaults(func=cmd_status)
    scenario = sub.add_parser("scenario")
    scenario.add_argument("name", choices=sorted(FAULTS))
    scenario.add_argument("--rate", type=int, default=100)
    scenario.add_argument("--seconds", type=int, default=60)
    scenario.add_argument("--mix", default="card,mixed,easy,mileage")
    scenario.add_argument("--run")
    scenario.add_argument("--offset", type=int, default=0)
    scenario.add_argument("--drain", type=int, default=600)
    scenario.set_defaults(func=cmd_scenario)
    replay = sub.add_parser("replay")
    replay.add_argument("--reason", default="LOAD_TEST_RECOVERY", help="감사 기록의 사유 코드")
    replay.set_defaults(func=cmd_replay)
    reconcile = sub.add_parser("reconcile")
    reconcile.add_argument("--runs", help="대조할 주입 실행 이름. 기본은 현재 스택에서 실행한 전부")
    reconcile.add_argument("--output", help="tests/load/work 아래 결과 파일 이름")
    reconcile.set_defaults(func=cmd_reconcile)
    args = parser.parse_args(argv)
    if getattr(args, "rate", 1) < 1 or getattr(args, "seconds", 1) < 1:
        parser.error("rate and seconds must be positive")
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
