"""Mac의 Docker k6 -> 공개 HTTPS 주문 API. 로그인·시험 전용 상품/재고·견적·사전 PAID를 준비한다.

python3 -B tests/load/order_load.py --base-url https://SERVICE_DOMAIN
서버 기동/인증 복구·공유 DB 초기화는 하지 않는다. 결과는 tests/load/work/orders-<runId>에 보존한다.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
import uuid


ROOT = Path(__file__).resolve().parents[2]
K6_IMAGE = "grafana/k6:2.3.0"  # compose.yml과 동일
TERMINAL = {"PAID", "DECLINED", "CANCELLED", "REFUNDED", "PURCHASE_CONFIRMED"}


def now():
    return datetime.now(timezone.utc).isoformat()


def base_url(value):
    parsed = urlsplit(value)
    if (parsed.scheme not in {"https", "http"} or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in {"", "/"}
            or parsed.scheme == "http" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}):
        raise ValueError("대상은 경로·자격 없는 HTTPS 원점이어야 합니다. HTTP는 로컬 통합 검증에만 허용합니다.")
    parsed.port  # 잘못된 포트도 외부 요청 전에 거절한다.
    return value.rstrip("/")


class ApiError(RuntimeError):
    def __init__(self, method, path, status, code=None, transport=None, trace_id=None):
        self.status, self.code = status, code
        self.transport, self.trace_id = transport, trace_id
        super().__init__(f"{method} {path.split('?')[0]}: HTTP {status}, code={code or 'UNKNOWN'}")

    def evidence(self):
        return {"status": self.status, "code": self.code,
                "transportErrorType": self.transport, "traceId": self.trace_id}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


class Api:
    def __init__(self, base):
        self.base = base_url(base)
        self.opener = build_opener(NoRedirect)

    def call(self, method, path, body=None, token=None, key=None):
        headers = {"Accept": "application/json", "Origin": self.base}
        if body is not None:
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        if key:
            headers["Idempotency-Key"] = key
        request = Request(self.base + path, json.dumps(body).encode() if body is not None else None,
                          headers, method=method)
        try:
            with self.opener.open(request, timeout=10) as response:
                raw = response.read(2 * 1024 * 1024 + 1)
                if len(raw) > 2 * 1024 * 1024:
                    raise RuntimeError("API 응답이 읽기 상한을 넘었습니다.")
                return json.loads(raw) if raw else {}
        except HTTPError as error:
            code, trace_id = None, None
            try:
                value = json.loads(error.read(65536))
                candidate = value.get("code")
                if isinstance(candidate, str) and re.fullmatch(r"[A-Z0-9_]+", candidate):
                    code = candidate
                trace = value.get("traceId")
                if isinstance(trace, str) and re.fullmatch(r"[0-9a-f]{16,32}", trace):
                    trace_id = trace
            except (ValueError, AttributeError):
                pass
            raise ApiError(method, path, error.code, code, trace_id=trace_id) from None
        except (URLError, TimeoutError, OSError) as error:
            cause = error.reason if isinstance(error, URLError) else error
            raise ApiError(method, path, 0, transport=type(cause).__name__) from None


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError("시각은 offset이 있는 문자열이어야 합니다.")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("시각에는 UTC offset이 필요합니다.")
    return parsed.timestamp()


def login(api, kind, owned, sessions=None):
    supplied = os.environ.get(kind + "_TOKEN")
    if supplied:
        if sessions is not None:
            expiry = os.environ.get(kind + "_TOKEN_EXPIRES_AT")
            if not expiry:
                raise RuntimeError(kind + "_TOKEN_EXPIRES_AT에 기존 세션의 expiresAt이 필요합니다.")
            sessions[kind] = {"token": supplied, "expiresAt": expiry,
                              "expiry": timestamp(expiry), "owned": False}
        return supplied
    session = api.call("POST", "/swagger-ui/fixture-sessions",
                       {"account": kind + "_A"})
    token = session.get("token")
    if not isinstance(token, str) or len(token) < 32:
        raise RuntimeError("로그인 응답에 유효한 token이 없습니다.")
    owned.append(token)
    if sessions is not None:
        expiry = session["expiresAt"]
        sessions[kind] = {"token": token, "expiresAt": expiry,
                          "expiry": timestamp(expiry), "owned": True,
                          "accountId": session["account"]["id"]}
        sessions.setdefault("_issued", {})[token] = timestamp(expiry)
    return token


def session_for(api, kind, owned, sessions, budget):
    """Own fixture sessions may renew between phases; borrowed sessions never change owner."""
    session = sessions[kind]
    check = {"kind": kind, "checkedAt": now(), "expiresAt": session.get("expiresAt"),
             "requiredSeconds": budget + 30, "valid": False, "renewed": False}
    sessions.setdefault("_checks", []).append(check)
    if session["expiry"] - time.time() < budget + 30:
        if not session["owned"]:
            raise RuntimeError(kind + " 기존 세션이 다음 단계·정리 예산보다 먼저 만료됩니다.")
        account = session["accountId"]
        login(api, kind, owned, sessions)
        session = sessions[kind]
        check.update(expiresAt=session["expiresAt"], renewed=True)
        if session["accountId"] != account:
            raise RuntimeError("재인증 계정이 달라 후속 요청을 중단합니다.")
        if session["expiry"] - time.time() < budget + 30:
            raise RuntimeError("발급된 세션의 유효 시간이 단계 예산보다 짧습니다.")
    check["valid"] = True
    return session["token"]


def prepare(api, buyer, seller, run_id, planned):
    key = lambda operation: run_id + "-" + operation
    addresses = api.call("GET", "/api/buyers/me/addresses?size=100", token=buyer)["items"]
    if addresses:
        address = addresses[0]
    else:
        address = api.call("POST", "/api/buyers/me/addresses", {
            "recipient": "k6 시험", "phone": "000-0000-0000", "postalCode": "00001",
            "addressLine1": "가상시 부하 시험용 주소", "addressLine2": "",
            "isDefault": False,
        }, buyer, key("address"))
    product = api.call("POST", "/api/sellers/me/products", {
        "name": "k6-" + run_id, "unitPrice": 1000, "status": "ON_SALE",
    }, seller, key("product"))
    plan = {"runId": run_id, "baseUrl": api.base, "createdAt": now(),
            "productId": product["id"], "addressId": address["id"],
            "plannedOrders": planned, "stockPrepared": planned + 1 + max(100, planned // 10)}
    return plan


def prepare_order(api, buyer, seller, plan):
    product = plan["productId"]
    inventory_path = "/api/sellers/me/products/" + product + "/inventory"
    inventory = api.call("GET", inventory_path, token=seller)
    # 새로 만든 상품만 준비한다. 기존 공유 상품의 가용량·예약량을 덮어쓰지 않는다.
    if any(inventory[k] for k in ("availableQuantity", "reservedQuantity",
                                  "eventAllocatedQuantity", "soldQuantity")):
        raise RuntimeError("새 시험 상품의 재고가 비어 있지 않아 준비를 중단합니다.")
    api.call("POST", inventory_path + "-adjustments", {
        "delta": plan["stockPrepared"], "expectedVersion": inventory["version"],
        "reason": "k6 주문 부하 시험 준비",
    }, seller, plan["runId"] + "-inventory")
    quote_input = {"productId": product, "quantity": 1, "eventId": None,
                   "buyerCouponId": None, "mileageAmount": 0, "externalMethod": "CREDIT_CARD"}
    quote = api.call("POST", "/api/order-quotes", quote_input, buyer)
    return {**quote_input, "addressId": plan["addressId"],
            "expectedProductVersion": quote["productVersion"], "expectedEventVersion": None,
            "expectedTotalAmount": quote["totalAmount"], "methodAccountRef": "mock-card-1"}


def smoke(api, buyer, body, run_id, timeout, plan=None, checkpoint=None):
    # 명시적인 접수 gate 거절만 같은 키로 최대 두 번 더 확인한다.
    for attempt in range(3):
        try:
            order = api.call("POST", "/api/orders", body, buyer, run_id + "-smoke")
            if plan is not None:
                plan["smokeOrderId"] = order["id"]
                if checkpoint:
                    checkpoint()
            break
        except ApiError as error:
            if error.code != "COMMON_CAPACITY_EXCEEDED" or attempt == 2:
                raise
            print("신규 주문 gate가 닫혀 있습니다. 30초 뒤 같은 사전 주문을 확인합니다.", flush=True)
            time.sleep(30)
    deadline = time.monotonic() + timeout
    while order["status"] != "PAID":
        if order["status"] != "PAYMENT_PENDING" or time.monotonic() >= deadline:
            raise RuntimeError("사전 주문이 PAID로 반영되지 않았습니다. 주문 ID=" + order["id"])
        time.sleep(1)
        order = api.call("GET", "/api/orders/" + order["id"], token=buyer)
    return order["id"]


def docker_target(base):
    parsed = urlsplit(base)
    if parsed.hostname in {"localhost", "127.0.0.1", "::1"}:
        if platform.system() == "Linux":
            return base, ["--network", "host"]
        authority = "host.docker.internal" + (":" + str(parsed.port) if parsed.port else "")
        return parsed.scheme + "://" + authority, []
    return base, []


def cleanup_container(name, run_id):
    observed = subprocess.run(["docker", "inspect", "--format",
                              '{{index .Config.Labels "commerce-settle.load-run"}}', name],
                             capture_output=True, text=True, timeout=15)
    if observed.returncode:
        # 관측 실패를 부재로 취급하지 않는다. 정상 daemon의 목록에서도 없을 때만 끝낸다.
        listing = subprocess.run(["docker", "ps", "-a", "-q", "--filter", "name=^/" + name + "$"],
                                 capture_output=True, text=True, timeout=15)
        if listing.returncode or listing.stdout.strip():
            raise RuntimeError("k6 컨테이너 정리 상태를 확인할 수 없습니다.")
        return
    if observed.stdout.strip() != run_id:
        raise RuntimeError("실행 컨테이너의 소유 label이 달라 정리하지 않았습니다.")
    subprocess.run(["docker", "rm", "-f", name], check=True, capture_output=True, timeout=30)


def inject(base, buyer, plan, directory, args, phase="measurement"):
    target, network = docker_target(base)
    name = "cs-orders-" + plan["runId"] + "-" + phase
    environment = {**os.environ, "BASE_URL": target, "BUYER_TOKEN": buyer,
                   "RUN_ID": plan["runId"], "RATE": str(args.rate),
                   "ORDER_FILE": "/work/order.json",
                   "DURATION_SECONDS": str(args.warmup_seconds if phase == "warmup" else args.seconds),
                   "LOAD_PHASE": phase,
                   "PRE_ALLOCATED_VUS": str(args.pre_allocated_vus), "MAX_VUS": str(args.max_vus)}
    command = ["docker", "run", "--rm", "--name", name,
               "--label", "commerce-settle.load-run=" + plan["runId"], *network,
               "-v", str(Path(__file__).parent) + ":/scripts:ro",
               "-v", str(directory) + ":/work"]
    for key in ("BASE_URL", "BUYER_TOKEN", "RUN_ID", "RATE", "ORDER_FILE", "DURATION_SECONDS",
                "PRE_ALLOCATED_VUS", "MAX_VUS", "LOAD_PHASE"):
        command += ["-e", key]
    summary_name = "k6-summary.json" if phase == "measurement" else "k6-warmup-summary.json"
    command += [K6_IMAGE, "run", "--quiet", "--log-format=raw", "--log-output=stdout",
                "--summary-export", "/work/" + summary_name, "/scripts/orders.js"]
    process = None
    try:
        with (directory / "requests.jsonl").open("a", encoding="utf-8") as ledger:
            (directory / "requests.jsonl").chmod(0o600)
            process = subprocess.Popen(command, env=environment, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True)
            for line in process.stdout:
                line = line.replace(buyer, "<redacted>")
                if line.startswith(("ORDER_PHASE ", "ORDER_START ", "ORDER ")):
                    started = line.startswith("ORDER_START ")
                    # Keep malformed payloads too: a missing row must remain a collection failure.
                    raw = line.split(" ", 1)[1]
                    try:
                        row = json.loads(raw)
                        row["event"] = ("phase" if line.startswith("ORDER_PHASE ") else
                                        "started" if started else "completed")
                        raw = json.dumps(row, ensure_ascii=False) + "\n"
                    except (ValueError, TypeError):
                        pass
                    ledger.write(raw)
                    ledger.flush()
                else:
                    print(line.rstrip(), flush=True)
            code = process.wait()
        return code
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        cleanup_container(name, plan["runId"])


def read_ledger(lines):
    """Stream rows; retain compact attempt flags and IDs, not response dictionaries."""
    attempts, accepted = {}, {"warmup": set(), "measurement": set()}
    counts = {phase: Counter() for phase in accepted}
    windows, errors, invalid = {}, [], 0
    client_errors = Counter()
    first, last = {}, {}
    for number, line in enumerate(lines, 1):
        try:
            row = json.loads(line)
            if not isinstance(row, dict):
                raise ValueError("invalid row")
            phase = next(p for p in accepted if p == row.get("phase", "measurement"))
            event = row["event"]
            if event == "phase":
                start, end = timestamp(row["measurementStart"]), timestamp(row["measurementEnd"])
                if phase in windows or end <= start:
                    raise ValueError("invalid phase window")
                windows[phase] = {"start": start, "end": end}
                continue
            iteration = row["iteration"]
            if type(iteration) is not int or iteration < 0 or event not in {"started", "completed"}:
                raise ValueError("invalid attempt")
            key = (phase, iteration)
            flags = attempts.get(key, 0)
            flag = 1 if event == "started" else 2
            if flags & flag:
                raise ValueError("duplicate event")
            started_at = timestamp(row["startedAt"])
            first[phase] = min(first.get(phase, started_at), started_at)
            last[phase] = max(last.get(phase, started_at), started_at)
            counts[phase][event] += 1
            if event == "completed":
                status = row["status"]
                if type(status) is not int:
                    raise ValueError("invalid status")
                counts[phase]["status:" + str(status)] += 1
                ident = row.get("orderId")
                if status == 201 and isinstance(ident, str) and ident:
                    if any(ident in ids for ids in accepted.values()):
                        raise ValueError("duplicate order ID")
                    accepted[phase].add(ident)
                    flag |= 4
                elif status == 503 and row.get("code") == "COMMON_CAPACITY_EXCEEDED":
                    flag |= 8  # Documented rejection before any new business write.
                if status == 0 or row.get("clientErrorCode"):
                    client_errors[str(row.get("clientErrorCode") or "UNCLASSIFIED_STATUS_0")] += 1
            attempts[key] = flags | flag
        except (ValueError, TypeError, KeyError, StopIteration):
            invalid += 1
            if len(errors) < 20:
                errors.append(number)
    unknown = []
    for (phase, iteration), flags in attempts.items():
        reason = ("missing_start" if not flags & 1 else
                  "response_missing" if not flags & 2 else
                  "acceptance_unknown" if not flags & (4 | 8) else None)
        if reason:
            unknown.append({"phase": phase, "iteration": iteration, "reason": reason})
    return {"accepted": accepted, "counts": counts, "windows": windows,
            "unknown": unknown, "invalidRows": invalid, "invalidRowSamples": errors,
            "ledgerComplete": invalid == 0 and not any(u["reason"] != "acceptance_unknown" for u in unknown),
            "clientErrors": dict(client_errors), "firstStartedAt": first, "lastStartedAt": last}


def ledger_from(directory):
    path = directory / "requests.jsonl"
    if path.is_file():
        with path.open(encoding="utf-8") as source:
            return read_ledger(source)
    value = read_ledger([])
    value["ledgerComplete"] = False
    value["unknown"].append({"phase": "unattributed", "reason": "ledger_missing"})
    return value


def orders_for_product(api, buyer, product, deadline, costs=None):
    result = {}
    page = 0
    while True:
        if page and time.monotonic() >= deadline:
            return result, False, None
        if costs is not None:
            costs["queries"] += 1
            costs["pages"] += 1
        try:
            value = api.call("GET", f"/api/orders?page={page}&size=100", token=buyer)
            items, pages = value["items"], value["totalPages"]
            if not isinstance(items, list) or type(pages) is not int or pages < 0:
                raise ValueError("invalid page")
            for order in items:
                if costs is not None:
                    costs["scannedOrders"] += 1
                if order["productId"] == product:
                    result[order["id"]] = {"status": order["status"],
                                          "paymentConfirmedAt": order.get("paymentConfirmedAt")}
        except ApiError as error:
            return result, False, error.evidence()
        except (ValueError, RuntimeError, KeyError, TypeError) as error:
            return result, False, {"errorType": type(error).__name__}
        page += 1
        if page >= pages:
            return result, True, None


def drain(api, buyer, plan, ledger, seconds, checkpoint=None):
    accepted = set().union(*ledger["accepted"].values())
    deadline = time.monotonic() + seconds
    began = time.monotonic()
    orders, snapshot_complete, error, smoke_seen = {}, False, None, None
    costs = {"queries": 0, "pages": 0, "scannedOrders": 0}
    unknown = list(ledger["unknown"])
    if ledger["invalidRows"]:
        unknown.append({"phase": "unattributed", "reason": "invalid_ledger_rows",
                        "count": ledger["invalidRows"]})
    if plan.get("smokeAttempted") and not plan.get("smokeOrderId") and not plan.get("smokeRejected"):
        unknown.append({"phase": "smoke", "iteration": 0, "reason": "acceptance_unknown"})
    while True:
        scan_started = time.monotonic()
        found, snapshot_complete, error = orders_for_product(api, buyer, plan["productId"], deadline, costs)
        orders.update(found)  # A partial/error snapshot never erases previously observed orders.
        smoke_seen = orders.get(plan.get("smokeOrderId"), smoke_seen)
        orders.pop(plan.get("smokeOrderId"), None)
        smoke_complete = (not plan.get("smokeAttempted") or plan.get("smokeRejected")
                          or plan.get("smokePaid") or smoke_seen and smoke_seen["status"] in TERMINAL)
        complete = (snapshot_complete and accepted <= orders.keys()
                    and all(order["status"] in TERMINAL for order in orders.values())
                    and not unknown and ledger["ledgerComplete"] and bool(smoke_complete))
        confirmations = Counter(order["paymentConfirmedAt"][:19] for order in orders.values()
                                if order.get("paymentConfirmedAt"))
        measurement_ids = ledger["accepted"]["measurement"]
        window = ledger["windows"].get("measurement")
        inside = sum(bool(order.get("paymentConfirmedAt")) and window is not None
                     and window["start"] <= timestamp(order["paymentConfirmedAt"]) < window["end"]
                     for key, order in orders.items() if key in measurement_ids)
        value = {
            "observedAt": now(), "acceptedOrders": len(accepted), "observedOrders": len(orders),
            "snapshotComplete": snapshot_complete, "drainComplete": complete,
            "missingAcceptedOrderIds": sorted(accepted - orders.keys()),
            "states": dict(Counter(order["status"] for order in orders.values())),
            "measurementStates": dict(Counter(orders[key]["status"] for key in measurement_ids & orders.keys())),
            "unresolvedAcceptedOrders": sum(orders[k]["status"] not in TERMINAL for k in accepted & orders.keys()),
            "ordersWithout201Receipt": sorted(orders.keys() - accepted),
            "unresolvedRequests": unknown, "ledgerComplete": ledger["ledgerComplete"],
            "unresolvedSmokeOrderId": None if smoke_complete else plan.get("smokeOrderId"),
            "invalidLedgerRows": ledger["invalidRows"], "invalidLedgerRowSamples": ledger["invalidRowSamples"],
            "recoveryRequired": not complete or bool(orders.keys() - accepted),
            "stopReason": ("complete" if complete else "observation_error" if error else "deadline"),
            "observationError": error, "elapsedSeconds": time.monotonic() - began, "queryCosts": dict(costs),
            "paymentConfirmedAtCounts": dict(sorted(confirmations.items())),
            "commerceConfirmedWithinMeasurement": inside,
            "paymentTimestampSource": "Commerce mirrored paymentConfirmedAt; not a direct Payment DB measurement",
            "financialReconciliation": "UNVERIFIED: inventory/reservation and financial source reconciliation required",
        }
        if checkpoint:
            checkpoint(value)
        if complete or error or time.monotonic() >= deadline:
            break
        # Throttle expensive buyer-wide scans; do not replace bounded observation with a quiet-period proof.
        delay = max(3, time.monotonic() - scan_started)
        time.sleep(min(delay, max(0, deadline - time.monotonic())))
    return value


def metric(summary, name, field):
    metrics = summary.get("metrics", {}) if isinstance(summary, dict) else {}
    value = metrics.get(name, {}) if isinstance(metrics, dict) else {}
    values = value.get("values", value) if isinstance(value, dict) else {}
    result = values.get(field) if isinstance(values, dict) else None
    return result if type(result) in (int, float) and math.isfinite(result) and result >= 0 else None


def read_k6_summary(directory, phase, report):
    name = "k6-summary.json" if phase == "measurement" else "k6-warmup-summary.json"
    try:
        value = json.loads((directory / name).read_text())
        if not isinstance(value, dict):
            raise ValueError("invalid summary")
        return value
    except (ValueError, OSError) as error:
        report.setdefault("k6SummaryErrors", {})[phase] = type(error).__name__
        return {}


def check_coverage(ledger, summary, phase):
    count = metric(summary, "http_reqs", "count")
    counts = ledger["counts"][phase]
    if count is None or count > min(counts["started"], counts["completed"]):
        ledger["ledgerComplete"] = False
        ledger["unknown"].append({"phase": phase, "reason": "k6_ledger_coverage_unverified",
                                 "httpRequests": count, "started": counts["started"],
                                 "completed": counts["completed"]})


def injection_passed(plan, ledger, k6, code, phase="measurement"):
    count = plan["plannedOrders"] if phase == "measurement" else plan["warmupOrders"]
    counts = ledger["counts"][phase]
    window = ledger["windows"].get(phase)
    first = ledger["firstStartedAt"].get(phase)
    last = ledger["lastStartedAt"].get(phase)
    seconds = plan.get("measurementSeconds", 60) if phase == "measurement" else plan["warmupSeconds"]
    return (code == 0 and window is not None
            and window["end"] - window["start"] == seconds
            and first is not None and window["start"] <= first <= last < window["end"]
            and ledger["ledgerComplete"] and not ledger["unknown"]
            and counts["started"] == counts["completed"] == counts["status:201"] == count
            and len(ledger["accepted"][phase]) == count
            and metric(k6, "http_reqs", "count") == count
            and metric(k6, "dropped_iterations", "count") == 0)


def verdict(plan, ledger, k6, code, observed):
    return (injection_passed(plan, ledger, k6, code) and observed["drainComplete"]
            and not observed["missingAcceptedOrderIds"]
            and not observed["ordersWithout201Receipt"]
            and observed["measurementStates"].get("PAID", 0) == plan["plannedOrders"])


def parser():
    result = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    result.add_argument("--base-url")
    result.add_argument("--tps", "--rate", dest="rate", metavar="TPS", type=int, default=200,
                        help="초당 주문 요청 수: 1..1000 (기본 200). 결제 완료 TPS와 구분")
    result.add_argument("--seconds", type=int, default=60)
    result.add_argument("--warmup-seconds", type=int, default=0)
    result.add_argument("--pre-allocated-vus", type=int,
                        help="사전 VU 수 (기본 max(400, TPS×2))")
    result.add_argument("--max-vus", type=int, help="최대 VU 수 (기본 max(1200, TPS×6))")
    result.add_argument("--smoke-timeout", type=int, default=90)
    result.add_argument("--drain-seconds", type=int, default=300)
    result.add_argument("--prepare-only", action="store_true")
    result.add_argument("--output-dir", type=Path)
    result.add_argument("--resume", type=Path, help="기존 원장을 읽어 새 recovery 하위 폴더에 관측. 추가 주문 없음")
    result.add_argument("--performance-metadata", type=Path,
                        help="기존 performance.py metadata. 배포 조건은 덮어쓰지 않고 loadRun 참조만 추가")
    return result


def main(argv=None):
    arguments = parser()
    args = arguments.parse_args(argv)
    if args.pre_allocated_vus is None:
        args.pre_allocated_vus = max(400, args.rate * 2)
    if args.max_vus is None:
        args.max_vus = max(1200, args.rate * 6)
    try:
        original = args.resume.resolve() if args.resume else None
        if original and args.resume.is_symlink():
            raise ValueError("symlink 실행 폴더로 복구하지 않습니다.")
        resumed_plan = json.loads((original / "plan.json").read_text()) if original else None
        if original and (args.prepare_only or args.output_dir or args.base_url):
            raise ValueError("--resume은 --base-url/--prepare-only/--output-dir와 함께 쓰지 않습니다.")
        target = base_url(resumed_plan["baseUrl"] if original else args.base_url or "")
        metadata = json.loads(args.performance_metadata.read_text()) if args.performance_metadata else None
        if metadata is not None and (not isinstance(metadata, dict) or "loadRun" in metadata):
            raise ValueError("performance metadata는 loadRun이 없는 기존 JSON 객체여야 합니다.")
    except (ValueError, OSError, KeyError, TypeError) as error:
        arguments.error(str(error))
    if (not 1 <= args.rate <= 1000 or not 1 <= args.seconds <= 900 or not 0 <= args.warmup_seconds <= 300
            or not 1 <= args.pre_allocated_vus <= args.max_vus <= 10000
            or not 1 <= args.smoke_timeout <= 300 or not 0 <= args.drain_seconds <= 900):
        arguments.error("tps=1..1000, seconds=1..900, warmup=0..300, VU=1..10000, smoke=1..300초, drain=0..900초 범위입니다.")
    run_id = resumed_plan["runId"] if original else uuid.uuid4().hex
    requested = (original / ("recovery-" + uuid.uuid4().hex) if original else
                 args.output_dir or ROOT / "tests/load/work" / ("orders-" + run_id)).absolute()
    if requested.is_symlink():
        arguments.error("기존 symlink를 결과 폴더로 사용할 수 없습니다.")
    directory = requested.resolve()
    try:
        directory.mkdir(parents=True, mode=0o700)
    except OSError:
        arguments.error("새 결과 폴더를 만들 수 없습니다. 기존 결과 폴더를 덮어쓰지 않습니다.")
    api, owned, sessions = Api(target), [], {}
    plan, buyer, seller = resumed_plan or {}, None, None
    ledger_root = original or directory
    report = {"startedAt": now(), "baseUrl": target, "runId": run_id, "k6Image": K6_IMAGE,
              "rate": args.rate, "durationSeconds": args.seconds, "passed": False,
              "loadExecuted": False,
              "preAllocatedVUs": args.pre_allocated_vus, "maxVUs": args.max_vus}
    report.update(warmupSeconds=args.warmup_seconds, operation="recovery" if original else "load",
                  drainBudgetSeconds=args.drain_seconds,
                  phase="preparation", injectionPassed=False, drainComplete=False,
                  performanceVerification={"status": "UNVERIFIED",
                      "required": ["same 900s Payment DB confirmed throughput", "backlog trend and system errors",
                                   "deployed API latency", "inventory/reservation and financial reconciliation"]},
                  generatorSourceSha256={name: file_sha(Path(__file__).parent / name)
                                         for name in ("order_load.py", "orders.js")},
                  generator={"python": platform.python_version(), "system": platform.system(),
                             "release": platform.release(), "machine": platform.machine()})
    if metadata is not None:
        report["deploymentConditions"] = {"metadataSha256": file_sha(args.performance_metadata),
                                          "verification": "DECLARED_UNVERIFIED"}
        report["performanceMetadataPath"] = "performance-metadata.json"
    code, observed, summary = None, None, {}
    exit_code = 1
    try:
        if not args.prepare_only and not original:
            if not shutil.which("docker"):
                raise RuntimeError("Docker Desktop이 필요합니다.")
            subprocess.run(["docker", "info", "--format", "{{.ServerVersion}}"], check=True, capture_output=True)
            image = subprocess.run(["docker", "image", "inspect", K6_IMAGE], capture_output=True, text=True)
            if image.returncode:
                subprocess.run(["docker", "pull", K6_IMAGE], check=True)
                image = subprocess.run(["docker", "image", "inspect", K6_IMAGE], check=True,
                                       capture_output=True, text=True)
            identity = json.loads(image.stdout)[0]
            report["generatorImage"] = {"id": identity["Id"], "repoDigests": identity.get("RepoDigests", [])}
        buyer = login(api, "BUYER", owned, sessions)
        seller = login(api, "SELLER", owned, sessions)
        # Borrowed sessions must cover every phase; own sessions may renew between phases.
        drains = 2 if args.warmup_seconds else 1
        budget = (args.drain_seconds + 180 if original else
                  args.smoke_timeout + args.warmup_seconds + args.seconds + args.drain_seconds * drains + 180)
        for kind in ("BUYER", "SELLER"):
            required = budget if not sessions[kind]["owned"] else args.smoke_timeout + 30
            session_for(api, kind, owned, sessions, required)
        if original:
            report.update(resumedFrom="..", plannedOrders=plan["plannedOrders"])
            report.update(rate=None, durationSeconds=None, warmupSeconds=None,
                          originalLoadPlan={"measurementSeconds": plan.get("measurementSeconds"),
                                            "warmupSeconds": plan.get("warmupSeconds")})
        else:
            total = args.rate * (args.seconds + args.warmup_seconds)
            plan = prepare(api, buyer, seller, run_id, total)
            plan.update(plannedOrders=args.rate * args.seconds, warmupOrders=args.rate * args.warmup_seconds,
                        measurementSeconds=args.seconds, warmupSeconds=args.warmup_seconds)
            write_json(directory / "plan.json", plan)
            body = prepare_order(api, buyer, seller, plan)
            write_json(directory / "order.json", body)
            plan["smokeAttempted"] = True
            write_json(directory / "plan.json", plan)
            plan["smokeOrderId"] = smoke(api, buyer, body, run_id, args.smoke_timeout, plan,
                                        lambda: write_json(directory / "plan.json", plan))
            plan["smokePaid"] = True
            write_json(directory / "plan.json", plan)
            print("사전 주문 PAID 확인. 결과 폴더: " + str(directory), flush=True)
        if args.prepare_only and not original:
            report.update(prepared=True, loadExecuted=False, productId=plan["productId"])
            exit_code = 0
        elif not original:
            report.update(loadExecuted=True, plannedOrders=plan["plannedOrders"])
            for phase in (["warmup"] if args.warmup_seconds else []) + ["measurement"]:
                report["phase"] = phase
                duration = args.warmup_seconds if phase == "warmup" else args.seconds
                buyer = session_for(api, "BUYER", owned, sessions, duration + 30)
                phase_code = inject(target, buyer, plan, directory, args, phase)
                report.setdefault("k6PhaseExitCodes", {})[phase] = phase_code
                if phase == "measurement":
                    code = phase_code
                if phase_code != 0:
                    raise RuntimeError(phase + " k6 실패. 추가 주입을 중단하고 원장을 대조합니다.")
                if phase == "warmup":
                    # Finish warmup business before the measured window, avoiding carry-in completions.
                    buyer = session_for(api, "BUYER", owned, sessions, args.drain_seconds + 30)
                    warm_ledger = ledger_from(directory)
                    warm_summary = read_k6_summary(directory, "warmup", report)
                    check_coverage(warm_ledger, warm_summary, "warmup")
                    report["warmupInjectionPassed"] = injection_passed(
                        plan, warm_ledger, warm_summary, phase_code, "warmup")
                    warm = drain(api, buyer, plan, warm_ledger, args.drain_seconds,
                                 lambda value: write_json(directory / "warmup-states.json", value))
                    if (not report["warmupInjectionPassed"] or warm["recoveryRequired"]
                            or not warm["drainComplete"]
                            or warm["states"].get("PAID", 0) != plan["warmupOrders"]):
                        raise RuntimeError("예열 거래가 전부 PAID로 배출되지 않아 본 측정을 시작하지 않습니다.")
    except (ApiError, RuntimeError, ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        report["failurePhase"] = report["phase"]
        report["failure"] = str(error) if isinstance(error, (ApiError, RuntimeError)) else type(error).__name__
        print("시험 중단: " + report["failure"], file=sys.stderr)
        if isinstance(error, ApiError):
            report["apiFailure"] = error.evidence()
            if plan and not original and error.code == "COMMON_CAPACITY_EXCEEDED":
                plan["smokeRejected"] = True
                write_json(directory / "plan.json", plan)
    except KeyboardInterrupt:
        report["failurePhase"] = report["phase"]
        report["failure"] = "사용자 중단. 이미 접수된 거래와 원장을 보존합니다."
        exit_code = 130
    finally:
        cleanup_errors = []
        if plan and buyer and not (args.prepare_only and exit_code == 0):
            try:
                report["phaseBeforeRecovery"] = report["phase"]
                report["phase"] = "drain"
                ledger = ledger_from(ledger_root)
                if not report["loadExecuted"] and not original:
                    ledger = read_ledger([])
                report.update(recordedResponses=ledger["counts"]["measurement"]["completed"],
                              clientErrors=ledger["clientErrors"], measurementWindow=ledger["windows"].get("measurement"),
                              firstStartedAt=ledger["firstStartedAt"], lastStartedAt=ledger["lastStartedAt"])
                for phase in ("warmup", "measurement"):
                    if (ledger["counts"][phase]["started"] or phase in report.get("k6PhaseExitCodes", {})
                            or report["phaseBeforeRecovery"] == phase):
                        phase_summary = read_k6_summary(ledger_root, phase, report)
                        check_coverage(ledger, phase_summary, phase)
                        if phase == "measurement":
                            summary = phase_summary
                interrupted_phase = report["phaseBeforeRecovery"]
                expected = plan.get("warmupOrders", 0) if interrupted_phase == "warmup" else plan["plannedOrders"]
                if (report["loadExecuted"] and "failure" in report
                        and interrupted_phase in ledger["counts"]
                        and ledger["counts"][interrupted_phase]["started"] < expected):
                    ledger["ledgerComplete"] = False
                    ledger["unknown"].append({"phase": report["phaseBeforeRecovery"],
                                              "reason": "injection_interrupted_without_complete_coverage"})
                report.update(k6ExitCode=code, droppedIterations=metric(summary, "dropped_iterations", "count"))
                buyer = session_for(api, "BUYER", owned, sessions, args.drain_seconds + 30)
                observed = drain(api, buyer, plan, ledger, args.drain_seconds,
                                 lambda value: write_json(directory / "order-states.json", value))
                report.update(drainComplete=observed["drainComplete"],
                              unresolvedRequests=observed["unresolvedRequests"],
                              recoveryRequired=observed["recoveryRequired"], orderStates=observed["states"])
                report["injectionPassed"] = injection_passed(plan, ledger, summary, code)
                if observed["observationError"]:
                    report["drainFailureEvidence"] = observed["observationError"]
                passed = (not original and "failure" not in report
                          and verdict(plan, ledger, summary, code, observed))
                report.update(passed=passed, recoveryComplete=not observed["recoveryRequired"],
                              meaning="API 주입/PAID 관측만 판정. 900초 업무 성능·금융 정합성은 UNVERIFIED.")
                if "failure" not in report:
                    exit_code = 0 if (not observed["recoveryRequired"] if original else passed) else 1
            except KeyboardInterrupt:
                report.update(recoveryRequired=True, drainInterrupted=True, passed=False)
                exit_code = 130
            except (ApiError, RuntimeError, ValueError, KeyError, OSError) as error:
                report.update(recoveryRequired=True, drainFailure=type(error).__name__, passed=False)
                if isinstance(error, ApiError):
                    report["drainFailureEvidence"] = error.evidence()
        if plan and seller and not (args.prepare_only and exit_code == 0):
            try:
                seller = session_for(api, "SELLER", owned, sessions, 30)
                path = "/api/sellers/me/products/" + plan["productId"]
                product = api.call("GET", path, token=seller)
                if product.get("status") != "STOPPED":
                    api.call("PUT", path, {"name": product["name"], "unitPrice": product["unitPrice"],
                                          "status": "STOPPED", "expectedVersion": product["version"]},
                             seller, run_id + "-stop-product")
                report["inventoryObservation"] = api.call("GET", path + "/inventory", token=seller)
            except (ApiError, RuntimeError, KeyError):
                cleanup_errors.append("시험 상품 판매 중지 미확인")
        for token in owned:
            try:
                api.call("DELETE", "/api/sessions/current", token=token)
            except ApiError as error:
                # Expiry is fixed and authoritative; never treat a lookup failure as expiry.
                expiry = sessions.get("_issued", {}).get(token)
                if error.status != 401 or expiry is None or time.time() < expiry:
                    cleanup_errors.append("자체 발급 세션 로그아웃 미확인")
        if cleanup_errors:
            report["cleanupErrors"] = cleanup_errors
            report["passed"] = False
            exit_code = 1
        report["endedAt"] = now()
        report["phase"] = "finished"
        report["sessionExpirations"] = {kind: sessions[kind]["expiresAt"]
                                       for kind in ("BUYER", "SELLER") if kind in sessions}
        report["sessionChecks"] = sessions.get("_checks", [])
        body_path = ledger_root / "order.json"
        if body_path.is_file():
            try:
                body = json.loads(body_path.read_text())
                report["workload"] = {"buyerCount": 1, "productCount": 1,
                    **{key: body.get(key) for key in ("quantity", "externalMethod", "mileageAmount", "eventId", "buyerCouponId")}}
            except (OSError, ValueError, AttributeError) as error:
                report["workloadUnverified"] = type(error).__name__
        report["evidence"] = {}
        for name in ("plan.json", "requests.jsonl", "k6-summary.json"):
            if (ledger_root / name).is_file():
                try:
                    report["evidence"][name] = {"path": os.path.relpath(ledger_root / name, directory),
                                               "sha256": file_sha(ledger_root / name)}
                except OSError:
                    report.setdefault("evidenceErrors", []).append(name)
                    report["passed"] = False
                    exit_code = 1
        write_json(directory / "summary.json", report)
        if metadata is not None:
            linked = {**metadata, "loadRun": {"runId": run_id, "measurementWindow": report.get("measurementWindow"),
                       "injectionPassed": report["injectionPassed"], "drainComplete": report["drainComplete"],
                       "recoveryRequired": report.get("recoveryRequired", False),
                       "ledger": report["evidence"].get("requests.jsonl"),
                       "clock": "generator UTC; verify actual DB windows and clock uncertainty separately",
                       "summary": {"path": "summary.json",
                                   "sha256": file_sha(directory / "summary.json")}}}
            write_json(directory / "performance-metadata.json", linked)
        print("요약: " + str(directory / "summary.json"), flush=True)
    return exit_code


def file_sha(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


if __name__ == "__main__":
    sys.exit(main())
