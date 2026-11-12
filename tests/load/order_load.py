"""Mac의 Docker k6 -> 공개 HTTPS 주문 API. 로그인·시험 전용 상품/재고·견적·사전 PAID를 준비한다.

python3 -B tests/load/order_load.py --base-url https://SERVICE_DOMAIN
서버 기동/인증 복구·공유 DB 초기화는 하지 않는다. 결과는 tests/load/work/orders-<runId>에 보존한다.
"""

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
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
    def __init__(self, method, path, status, code=None):
        self.status, self.code = status, code
        super().__init__(f"{method} {path.split('?')[0]}: HTTP {status}, code={code or 'UNKNOWN'}")


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
            code = None
            try:
                value = json.loads(error.read(65536))
                candidate = value.get("code")
                if isinstance(candidate, str) and re.fullmatch(r"[A-Z0-9_]+", candidate):
                    code = candidate
            except (ValueError, AttributeError):
                pass
            raise ApiError(method, path, error.code, code) from None
        except (URLError, TimeoutError, OSError):
            raise ApiError(method, path, 0) from None


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    path.chmod(0o600)


def login(api, kind, owned):
    supplied = os.environ.get(kind + "_TOKEN")
    if supplied:
        return supplied
    session = api.call("POST", "/swagger-ui/fixture-sessions",
                       {"account": kind + "_A"})
    token = session.get("token")
    if not isinstance(token, str) or len(token) < 32:
        raise RuntimeError("로그인 응답에 유효한 token이 없습니다.")
    owned.append(token)
    return token


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


def smoke(api, buyer, body, run_id, timeout):
    # 명시적인 접수 gate 거절만 같은 키로 최대 두 번 더 확인한다.
    for attempt in range(3):
        try:
            order = api.call("POST", "/api/orders", body, buyer, run_id + "-smoke")
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


def inject(base, buyer, plan, directory, args):
    target, network = docker_target(base)
    name = "cs-orders-" + plan["runId"]
    environment = {**os.environ, "BASE_URL": target, "BUYER_TOKEN": buyer,
                   "RUN_ID": plan["runId"], "RATE": str(args.rate),
                   "ORDER_FILE": "/work/order.json",
                   "DURATION_SECONDS": str(args.seconds),
                   "PRE_ALLOCATED_VUS": str(args.pre_allocated_vus), "MAX_VUS": str(args.max_vus)}
    command = ["docker", "run", "--rm", "--name", name,
               "--label", "commerce-settle.load-run=" + plan["runId"], *network,
               "-v", str(Path(__file__).parent) + ":/scripts:ro",
               "-v", str(directory) + ":/work"]
    for key in ("BASE_URL", "BUYER_TOKEN", "RUN_ID", "RATE", "ORDER_FILE", "DURATION_SECONDS",
                "PRE_ALLOCATED_VUS", "MAX_VUS"):
        command += ["-e", key]
    command += [K6_IMAGE, "run", "--quiet", "--log-format=raw", "--log-output=stdout",
                "--summary-export", "/work/k6-summary.json", "/scripts/orders.js"]
    records = []
    process = None
    try:
        with (directory / "requests.jsonl").open("x", encoding="utf-8") as ledger:
            process = subprocess.Popen(command, env=environment, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True)
            for line in process.stdout:
                if line.startswith(("ORDER_START ", "ORDER ")):
                    started = line.startswith("ORDER_START ")
                    row = json.loads(line.split(" ", 1)[1])
                    row["event"] = "started" if started else "completed"
                    ledger.write(json.dumps(row, ensure_ascii=False) + "\n")
                    ledger.flush()
                    if not started:
                        records.append(row)
                else:
                    print(line.rstrip(), flush=True)
            code = process.wait()
        return code, records
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        cleanup_container(name, plan["runId"])


def orders_for_product(api, buyer, product, deadline):
    result = {}
    page = 0
    while True:
        if page and time.monotonic() >= deadline:
            return result, False
        value = api.call("GET", f"/api/orders?page={page}&size=100", token=buyer)
        for order in value["items"]:
            if order["productId"] == product:
                result[order["id"]] = order
        page += 1
        if page >= value["totalPages"]:
            return result, True


def drain(api, buyer, plan, records, seconds):
    accepted = {row["orderId"] for row in records if row["status"] == 201 and row["orderId"]}
    deadline = time.monotonic() + seconds
    while True:
        orders, snapshot_complete = orders_for_product(api, buyer, plan["productId"], deadline)
        orders.pop(plan["smokeOrderId"], None)
        complete = (snapshot_complete and accepted <= orders.keys()
                    and all(order["status"] in TERMINAL for order in orders.values()))
        if complete or time.monotonic() >= deadline:
            break
        time.sleep(3)
    confirmations = Counter(order["paymentConfirmedAt"][:19] for order in orders.values()
                            if order.get("paymentConfirmedAt"))
    return {
        "observedAt": now(), "acceptedOrders": len(accepted), "observedOrders": len(orders),
        "snapshotComplete": snapshot_complete,
        "missingAcceptedOrderIds": sorted(accepted - orders.keys()),
        "states": dict(Counter(order["status"] for order in orders.values())),
        "unresolvedAcceptedOrders": sum(orders[k]["status"] not in TERMINAL for k in accepted & orders.keys()),
        "ordersWithout201Receipt": sorted(orders.keys() - accepted),
        "paymentConfirmedAtCounts": dict(sorted(confirmations.items())),
        "paymentTimestampSource": "Commerce 주문 응답에 반영된 paymentConfirmedAt; Payment DB 직접 측정과 구분",
        "financialReconciliation": "미수행: Settlement·대역 원장·금액 대조는 별도 실행",
    }


def metric(summary, name, field):
    value = summary.get("metrics", {}).get(name, {})
    return value.get("values", value).get(field)


def verdict(plan, records, k6, code, observed):
    count = plan["plannedOrders"]
    return (code == 0 and len(records) == count
            and len({r["iteration"] for r in records}) == count
            and len({r["orderId"] for r in records if r["orderId"]}) == count
            and all(r["status"] == 201 and r["orderId"] for r in records)
            and metric(k6, "http_reqs", "count") == count
            and metric(k6, "dropped_iterations", "count") == 0
            and observed["snapshotComplete"]
            and not observed["missingAcceptedOrderIds"]
            and not observed["ordersWithout201Receipt"]
            and observed["states"].get("PAID", 0) == count)


def parser():
    result = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    result.add_argument("--base-url", required=True)
    result.add_argument("--tps", "--rate", dest="rate", metavar="TPS", type=int, default=200,
                        help="초당 주문 요청 수: 1..1000 (기본 200). 결제 완료 TPS와 구분")
    result.add_argument("--seconds", type=int, default=60)
    result.add_argument("--pre-allocated-vus", type=int,
                        help="사전 VU 수 (기본 max(400, TPS×2))")
    result.add_argument("--max-vus", type=int, help="최대 VU 수 (기본 max(1200, TPS×6))")
    result.add_argument("--smoke-timeout", type=int, default=90)
    result.add_argument("--drain-seconds", type=int, default=300)
    result.add_argument("--prepare-only", action="store_true")
    result.add_argument("--output-dir", type=Path)
    return result


def main(argv=None):
    arguments = parser()
    args = arguments.parse_args(argv)
    if args.pre_allocated_vus is None:
        args.pre_allocated_vus = max(400, args.rate * 2)
    if args.max_vus is None:
        args.max_vus = max(1200, args.rate * 6)
    try:
        target = base_url(args.base_url)
    except ValueError as error:
        arguments.error(str(error))
    if (not 1 <= args.rate <= 1000 or not 1 <= args.seconds <= 60
            or not 1 <= args.pre_allocated_vus <= args.max_vus <= 10000
            or not 1 <= args.smoke_timeout <= 300 or not 0 <= args.drain_seconds <= 900):
        arguments.error("tps=1..1000, seconds=1..60, VU=1..10000, smoke=1..300초, drain=0..900초 범위입니다.")
    if not args.prepare_only:
        if not shutil.which("docker"):
            arguments.error("Docker Desktop이 필요합니다.")
        subprocess.run(["docker", "info", "--format", "{{.ServerVersion}}"], check=True, capture_output=True)
        if subprocess.run(["docker", "image", "inspect", K6_IMAGE], capture_output=True).returncode:
            subprocess.run(["docker", "pull", K6_IMAGE], check=True)
    run_id = uuid.uuid4().hex
    requested = (args.output_dir or ROOT / "tests/load/work" / ("orders-" + run_id)).absolute()
    if requested.is_symlink():
        arguments.error("기존 symlink를 결과 폴더로 사용할 수 없습니다.")
    directory = requested.resolve()
    try:
        directory.mkdir(parents=True, mode=0o700)
    except OSError:
        arguments.error("새 결과 폴더를 만들 수 없습니다. 기존 결과 폴더를 덮어쓰지 않습니다.")
    api, owned = Api(target), []
    plan, seller = {}, None
    report = {"startedAt": now(), "baseUrl": target, "runId": run_id, "k6Image": K6_IMAGE,
              "rate": args.rate, "durationSeconds": args.seconds, "passed": False,
              "loadExecuted": False,
              "preAllocatedVUs": args.pre_allocated_vus, "maxVUs": args.max_vus}
    exit_code = 1
    try:
        buyer = login(api, "BUYER", owned)
        seller = login(api, "SELLER", owned)
        plan = prepare(api, buyer, seller, run_id, args.rate * args.seconds)
        write_json(directory / "plan.json", plan)
        body = prepare_order(api, buyer, seller, plan)
        write_json(directory / "order.json", body)
        plan["smokeOrderId"] = smoke(api, buyer, body, run_id, args.smoke_timeout)
        write_json(directory / "plan.json", plan)
        print("사전 주문 PAID 확인. 결과 폴더: " + str(directory), flush=True)
        if args.prepare_only:
            report.update(prepared=True, loadExecuted=False, productId=plan["productId"])
            exit_code = 0
        else:
            report["measurementStartedAt"] = now()
            report.update(loadExecuted=True, plannedOrders=plan["plannedOrders"])
            code, records = inject(target, buyer, plan, directory, args)
            report["measurementEndedAt"] = now()
            summary_path = directory / "k6-summary.json"
            summary = json.loads(summary_path.read_text()) if summary_path.is_file() else {}
            report.update(k6ExitCode=code, recordedResponses=len(records),
                          droppedIterations=metric(summary, "dropped_iterations", "count"))
            observed = drain(api, buyer, plan, records, args.drain_seconds)
            write_json(directory / "order-states.json", observed)
            passed = verdict(plan, records, summary, code, observed)
            report.update(passed=passed,
                          orderStates=observed["states"],
                          meaning="주문 도착률·201·PAID 반영 검사. 금융 전체 정합성·지속 가능 TPS 판정과 구분.")
            exit_code = 0 if passed else 1
    except (ApiError, RuntimeError, ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        report["failure"] = str(error) if isinstance(error, (ApiError, RuntimeError)) else type(error).__name__
        print("시험 중단: " + report["failure"], file=sys.stderr)
    except KeyboardInterrupt:
        report["failure"] = "사용자 중단. 이미 접수된 거래와 원장을 보존합니다."
        exit_code = 130
    finally:
        cleanup_errors = []
        if plan and seller and not (args.prepare_only and exit_code == 0):
            try:
                path = "/api/sellers/me/products/" + plan["productId"]
                product = api.call("GET", path, token=seller)
                api.call("PUT", path, {"name": product["name"], "unitPrice": product["unitPrice"],
                                      "status": "STOPPED", "expectedVersion": product["version"]},
                         seller, run_id + "-stop-product")
            except (ApiError, KeyError):
                cleanup_errors.append("시험 상품 판매 중지 미확인")
        for token in owned:
            try:
                api.call("DELETE", "/api/sessions/current", token=token)
            except ApiError:
                cleanup_errors.append("자체 발급 세션 로그아웃 미확인")
        if cleanup_errors:
            report["cleanupErrors"] = cleanup_errors
            report["passed"] = False
            exit_code = 1
        report["endedAt"] = now()
        write_json(directory / "summary.json", report)
        print("요약: " + str(directory / "summary.json"), flush=True)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
