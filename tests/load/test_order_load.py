"""공개 API 준비 계약, 실패 시 추가 주입 차단과 결과 판정을 검사한다."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from unittest.mock import patch


HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("order_load", HERE / "order_load.py")
load = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)
SCHEMAS = json.loads((HERE.parents[1] / "docs/openapi.json").read_text())["components"]["schemas"]


class ApiFixture:
    """기존 OpenAPI의 키·초기 재고·상태 전이를 지키는 준비용 대역."""

    def __init__(self, base):
        self.base = base
        self.calls = []
        self.logouts = []
        self.paid = True

    def call(self, method, path, body=None, token=None, key=None):
        self.calls.append((method, path, body, token, key))
        if path == "/swagger-ui/fixture-sessions":
            return {"token": body["account"] + "-" * 40,
                    "expiresAt": (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat(),
                    "account": {"id": body["account"]}}
        if path == "/api/buyers/me/addresses?size=100":
            return {"items": [{"id": "address"}]}
        if path == "/api/sellers/me/products":
            assert set(body) == set(SCHEMAS["ProductCreate"]["required"])
            return {"id": "product"}
        if path.endswith("/inventory"):
            return {"availableQuantity": 0, "reservedQuantity": 0, "eventAllocatedQuantity": 0,
                    "soldQuantity": 0, "version": 7}
        if path.endswith("/inventory-adjustments"):
            assert set(body) == set(SCHEMAS["InventoryAdjustment"]["required"])
            assert body["expectedVersion"] == 7
            return {}
        if path == "/api/order-quotes":
            assert set(body) == set(SCHEMAS["OrderQuoteRequest"]["required"])
            return {"productVersion": 9, "totalAmount": 1200}
        if path == "/api/orders" and method == "POST":
            assert set(body) == set(SCHEMAS["OrderCreate"]["required"])
            return {"id": "smoke", "status": "PAID" if self.paid else "DECLINED"}
        if path.startswith("/api/orders?"):
            return {"totalPages": 1, "items": [{"id": "smoke", "productId": "product",
                    "status": "PAID" if self.paid else "DECLINED", "paymentConfirmedAt": None}]}
        if path == "/api/sellers/me/products/product":
            if method == "GET":
                return {"name": "owned-product", "unitPrice": 1200, "version": 9}
            assert body["status"] == "STOPPED"
            return {}
        if path == "/api/sessions/current":
            self.logouts.append(token)
            return {}
        raise AssertionError((method, path))


class PreparationTest(unittest.TestCase):
    def test_uses_owner_api_current_versions_and_sufficient_new_inventory(self):
        api = ApiFixture("https://load.test")
        plan = load.prepare(api, "buyer", "seller", "run", 12000)
        body = load.prepare_order(api, "buyer", "seller", plan)
        self.assertEqual(plan["stockPrepared"], 13201)
        self.assertEqual(body["expectedProductVersion"], 9)
        self.assertEqual(body["expectedTotalAmount"], 1200)
        self.assertIsNone(body["eventId"])
        self.assertIsNone(body["buyerCouponId"])
        self.assertEqual(body["mileageAmount"], 0)
        self.assertTrue(all(not path.startswith("/internal") for _, path, *_ in api.calls))

    def test_does_not_overwrite_nonempty_inventory(self):
        api = ApiFixture("https://load.test")
        plan = load.prepare(api, "buyer", "seller", "run", 1)
        original = api.call

        def changed(method, path, *args, **kwargs):
            result = original(method, path, *args, **kwargs)
            if path.endswith("/inventory"):
                result["reservedQuantity"] = 1
            return result
        api.call = changed
        with self.assertRaisesRegex(RuntimeError, "비어 있지"):
            load.prepare_order(api, "buyer", "seller", plan)
        self.assertFalse(any(path.endswith("/inventory-adjustments") for _, path, *_ in api.calls))

    def test_failed_smoke_never_injects_and_logs_out_owned_sessions(self):
        fixture = ApiFixture("https://load.test")
        fixture.paid = False
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True), \
                patch.object(load, "Api", return_value=fixture), patch.object(load, "inject") as inject, \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            target = Path(tmp) / "result"
            result = load.main(["--base-url", fixture.base, "--prepare-only", "--output-dir", str(target)])
            self.assertEqual(result, 1)
            inject.assert_not_called()
            self.assertEqual(len(fixture.logouts), 2)
            self.assertTrue(any(method == "PUT" for method, *_ in fixture.calls))
            raw = (target / "summary.json").read_text()
            self.assertNotIn("BUYER_A" + "-" * 40, raw)
            self.assertFalse(json.loads(raw)["passed"])

    def test_prepare_only_keeps_new_product_and_does_not_logout_borrowed_tokens(self):
        fixture = ApiFixture("https://load.test")
        with tempfile.TemporaryDirectory() as tmp, \
                patch.dict(os.environ, {"BUYER_TOKEN": "borrowed-buyer", "SELLER_TOKEN": "borrowed-seller",
                    "BUYER_TOKEN_EXPIRES_AT": (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat(),
                    "SELLER_TOKEN_EXPIRES_AT": (datetime.now(timezone.utc) + timedelta(minutes=30)).isoformat()}), \
                patch.object(load, "Api", return_value=fixture), contextlib.redirect_stdout(io.StringIO()):
            target = Path(tmp) / "result"
            self.assertEqual(load.main(["--base-url", fixture.base, "--prepare-only",
                                        "--output-dir", str(target)]), 0)
            self.assertFalse(fixture.logouts)
            self.assertFalse(any(method == "PUT" for method, *_ in fixture.calls))
            report = json.loads((target / "summary.json").read_text())
            self.assertTrue(report["prepared"])
            self.assertFalse(report["loadExecuted"])
            self.assertFalse(report["passed"])
            self.assertEqual(target.stat().st_mode & 0o777, 0o700)

    def test_only_explicit_gate_rejection_retries_preflight(self):
        api = ApiFixture("https://load.test")
        requests = []

        def reject(method, path, body=None, token=None, key=None):
            requests.append(key)
            raise load.ApiError(method, path, 503, "COMMON_CAPACITY_EXCEEDED")
        api.call = reject
        with patch.object(load.time, "sleep") as sleep, self.assertRaises(load.ApiError):
            load.smoke(api, "buyer", {}, "run", 1)
        self.assertEqual(requests, ["run-smoke"] * 3)
        self.assertEqual(sleep.call_count, 2)
        api.call = lambda method, path, *args: (_ for _ in ()).throw(
            load.ApiError(method, path, 503, "COMMON_TEMPORARILY_UNAVAILABLE"))
        with patch.object(load.time, "sleep") as sleep, self.assertRaises(load.ApiError):
            load.smoke(api, "buyer", {}, "run", 1)
        sleep.assert_not_called()


class ResultTest(unittest.TestCase):
    def setUp(self):
        self.plan = {"plannedOrders": 2}
        self.records = [{"iteration": n, "status": 201, "orderId": str(n)} for n in range(2)]
        self.summary = {"metrics": {"http_reqs": {"count": 2}, "dropped_iterations": {"count": 0}}}
        self.observed = {"missingAcceptedOrderIds": [], "ordersWithout201Receipt": [],
                         "snapshotComplete": True, "drainComplete": True,
                         "measurementStates": {"PAID": 2}, "states": {"PAID": 2}}

    def ledger(self, records=None):
        records = self.records if records is None else records
        rows = [{"event": "phase", "phase": "measurement",
                 "measurementStart": "2026-11-12T00:00:00Z", "measurementEnd": "2026-11-12T00:01:00Z"}]
        for record in records:
            rows += [{"event": "started", "startedAt": "2026-11-12T00:00:00Z",
                      "iteration": record["iteration"]},
                     {"event": "completed", "startedAt": "2026-11-12T00:00:00Z", **record}]
        return load.read_ledger(map(json.dumps, rows))

    def test_zero_errors_does_not_hide_drops_missing_or_duplicate_orders(self):
        self.assertTrue(load.verdict(self.plan, self.ledger(), self.summary, 0, self.observed))
        self.summary["metrics"]["dropped_iterations"]["count"] = 1
        self.assertFalse(load.verdict(self.plan, self.ledger(), self.summary, 0, self.observed))
        self.summary["metrics"]["dropped_iterations"]["count"] = 0
        self.records[1]["orderId"] = "0"
        self.assertFalse(load.verdict(self.plan, self.ledger(), self.summary, 0, self.observed))

    def test_pending_or_missing_metrics_is_not_success(self):
        self.observed["states"] = {"PAID": 1, "PAYMENT_PENDING": 1}
        self.observed["measurementStates"] = self.observed["states"]
        self.assertFalse(load.verdict(self.plan, self.ledger(), self.summary, 0, self.observed))

    def test_wrong_duration_or_starts_outside_window_fail_injection(self):
        ledger = self.ledger()
        ledger["windows"]["measurement"]["end"] += 1
        self.assertFalse(load.injection_passed(self.plan, ledger, self.summary, 0))
        ledger = self.ledger()
        ledger["firstStartedAt"]["measurement"] -= 1
        self.assertFalse(load.injection_passed(self.plan, ledger, self.summary, 0))

    def test_partial_snapshot_does_not_claim_all_orders_checked(self):
        self.observed["snapshotComplete"] = False
        self.observed["drainComplete"] = False
        self.assertFalse(load.verdict(self.plan, self.ledger(), self.summary, 0, self.observed))
        class SlowPages:
            def call(self, *args, **kwargs):
                return {"items": [], "totalPages": 100}
        value, complete, error = load.orders_for_product(SlowPages(), "buyer", "product", 0)
        self.assertFalse(complete)
        self.assertEqual(value, {})

    def test_container_observation_failure_is_not_absence(self):
        from subprocess import CompletedProcess
        with patch.object(load.subprocess, "run", side_effect=[
                CompletedProcess([], 1, "", "daemon unavailable"),
                CompletedProcess([], 1, "", "daemon unavailable")]), self.assertRaises(RuntimeError):
            load.cleanup_container("own", "run")
        self.observed["states"] = {"PAID": 2}
        del self.summary["metrics"]["dropped_iterations"]
        self.assertFalse(load.verdict(self.plan, self.ledger(), self.summary, 0, self.observed))

    def test_drain_excludes_smoke_and_reports_unacknowledged_created_order(self):
        class Orders:
            def call(self, method, path, **kwargs):
                return {"totalPages": 1, "items": [
                    {"id": ident, "productId": "product", "status": "PAID",
                     "paymentConfirmedAt": "2026-11-12T10:00:00+09:00"}
                    for ident in ("smoke", "0", "1", "unknown")]}
        value = load.drain(Orders(), "buyer", {"productId": "product", "smokeOrderId": "smoke"},
                           self.ledger(), 0)
        self.assertEqual(value["states"], {"PAID": 3})
        self.assertEqual(value["ordersWithout201Receipt"], ["unknown"])
        self.assertNotIn("paymentConfirmedAtCounts", value)
        self.assertIn("UNVERIFIED", value["financialReconciliation"])

    def test_drain_waits_for_order_created_without_201_receipt(self):
        pending = {"unknown": {"id": "unknown", "status": "PAYMENT_PENDING"}}
        paid = {"unknown": {"id": "unknown", "status": "PAID"}}
        with patch.object(load, "orders_for_product", side_effect=[(pending, True, None), (paid, True, None)]), \
                patch.object(load.time, "sleep") as sleep:
            value = load.drain(None, "buyer", {"productId": "product", "smokeOrderId": "smoke"}, self.ledger([]), 30)
        sleep.assert_called_once_with(3)
        self.assertEqual(value["states"], {"PAID": 1})
        self.assertEqual(value["ordersWithout201Receipt"], ["unknown"])

    def test_interrupt_preserves_started_request_before_response(self):
        from types import SimpleNamespace
        from unittest.mock import Mock

        def output():
            yield 'ORDER_START {"iteration":0,"startedAt":"2026-11-12T00:00:00Z"}\n'
            raise KeyboardInterrupt
        process = Mock(stdout=output())
        process.poll.return_value = None
        args = SimpleNamespace(rate=1, seconds=1, pre_allocated_vus=1, max_vus=1)
        with tempfile.TemporaryDirectory() as tmp, patch.object(load.subprocess, "Popen", return_value=process), \
                patch.object(load, "cleanup_container") as cleanup:
            with self.assertRaises(KeyboardInterrupt):
                load.inject("https://load.test", "secret", {"runId": "run"}, Path(tmp), args)
            rows = [json.loads(line) for line in (Path(tmp) / "requests.jsonl").read_text().splitlines()]
        self.assertEqual(rows, [{"iteration": 0, "startedAt": "2026-11-12T00:00:00Z", "event": "started"}])
        process.terminate.assert_called_once()
        cleanup.assert_called_once_with("cs-orders-run-measurement", "run")

    def test_inject_uses_canonical_script_and_generated_container_input(self):
        from types import SimpleNamespace
        from unittest.mock import Mock

        process = Mock(stdout=[])
        process.wait.return_value = 0
        process.poll.return_value = 0
        args = SimpleNamespace(rate=2, seconds=3, pre_allocated_vus=4, max_vus=12)
        with tempfile.TemporaryDirectory() as tmp, \
                patch.dict(os.environ, {"ORDER_FILE": "/outside/unrelated.json"}), \
                patch.object(load.subprocess, "Popen", return_value=process) as start, \
                patch.object(load, "cleanup_container"):
            code = load.inject("https://load.test", "secret", {"runId": "run"}, Path(tmp), args)
        command = start.call_args.args[0]
        environment = start.call_args.kwargs["env"]
        self.assertEqual(command[-1], "/scripts/orders.js")
        self.assertTrue((HERE / "orders.js").is_file())
        self.assertIn(["-e", "ORDER_FILE"], [command[n:n + 2] for n in range(len(command) - 1)])
        self.assertEqual(environment["ORDER_FILE"], "/work/order.json")
        self.assertEqual(environment["RATE"], "2")
        self.assertEqual(environment["DURATION_SECONDS"], "3")
        self.assertEqual(code, 0)

    def test_tps_option_keeps_rate_alias(self):
        self.assertEqual(load.parser().parse_args(["--base-url", "https://load.test"]).rate, 200)
        for option in ("--tps", "--rate"):
            with self.subTest(option=option):
                self.assertEqual(load.parser().parse_args([
                    "--base-url", "https://load.test", option, "1000"]).rate, 1000)

    def test_maximum_tps_prepares_matching_inventory_and_vu_budget(self):
        fixture = ApiFixture("https://load.test")
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True), \
                patch.object(load, "Api", return_value=fixture), contextlib.redirect_stdout(io.StringIO()):
            target = Path(tmp) / "result"
            self.assertEqual(load.main(["--base-url", fixture.base, "--tps", "1000", "--seconds", "3",
                                        "--prepare-only", "--output-dir", str(target)]), 0)
            report = json.loads((target / "summary.json").read_text())
            plan = json.loads((target / "plan.json").read_text())
        self.assertEqual((report["rate"], report["preAllocatedVUs"], report["maxVUs"]), (1000, 2000, 6000))
        self.assertEqual((plan["plannedOrders"], plan["stockPrepared"]), (3000, 3301))
        self.assertFalse(report["loadExecuted"])

    def test_tps_outside_bounds_is_rejected_before_docker_or_api(self):
        for tps in (0, 1001):
            with self.subTest(tps=tps), patch.object(load.subprocess, "run") as run, \
                    patch.object(load, "Api") as api, contextlib.redirect_stderr(io.StringIO()), \
                    self.assertRaises(SystemExit):
                load.main(["--base-url", "https://load.test", "--tps", str(tps)])
            run.assert_not_called()
            api.assert_not_called()

    def test_https_origin_and_docker_localhost_mapping(self):
        for url in ["http://example.com", "https://u:p@example.com", "https://example.com/api",
                    "https://example.com?token=private", "https://example.com#x"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                load.base_url(url)
        self.assertEqual(load.base_url("https://load.test/"), "https://load.test")
        with patch.object(load.platform, "system", return_value="Darwin"):
            self.assertEqual(load.docker_target("http://127.0.0.1:1234"),
                             ("http://host.docker.internal:1234", []))
        with patch.object(load.platform, "system", return_value="Linux"):
            self.assertEqual(load.docker_target("http://127.0.0.1:1234"),
                             ("http://127.0.0.1:1234", ["--network", "host"]))

    def test_redirect_does_not_forward_authorization(self):
        self.assertIsNone(load.NoRedirect().redirect_request(
            None, None, 302, "", {}, "https://other.test"))


@contextlib.contextmanager
def fake_clock():
    current = [0.0]
    with patch.object(load.time, "monotonic", side_effect=lambda: current[0]), \
            patch.object(load.time, "sleep", side_effect=lambda seconds: current.__setitem__(0, current[0] + seconds)):
        yield current


class RecoveryTest(unittest.TestCase):
    def unknown(self):
        return load.read_ledger(map(json.dumps, [
            {"event": "started", "iteration": 0, "startedAt": "2026-11-13T00:00:00Z"},
            {"event": "completed", "iteration": 0, "startedAt": "2026-11-13T00:00:00Z",
             "status": 0, "orderId": None, "clientErrorCode": 1050},
        ]))

    def test_empty_then_pending_then_paid_does_not_erase_unknown_receipt(self):
        class Delayed:
            calls = 0

            def call(self, method, path, **kwargs):
                self.calls += 1
                assert method == "GET"
                return {"totalPages": 1, "items": [] if self.calls == 1 else [
                    {"id": "late", "productId": "p", "status": "PAYMENT_PENDING" if self.calls == 2 else "PAID"}]}
        api, checkpoints = Delayed(), []
        with fake_clock() as clock:
            value = load.drain(api, "buyer", {"productId": "p"}, self.unknown(), 6, checkpoints.append)
        self.assertEqual((api.calls, clock[0]), (3, 6))
        self.assertEqual(value["states"], {"PAID": 1})
        self.assertEqual(value["ordersWithout201Receipt"], ["late"])
        self.assertEqual(value["unresolvedAcceptedOrders"], 0)
        self.assertEqual(value["unresolvedRequests"][0]["reason"], "acceptance_unknown")
        self.assertTrue(value["recoveryRequired"])
        self.assertFalse(value["drainComplete"])
        self.assertEqual(value["stopReason"], "deadline")
        self.assertEqual(len(checkpoints), 3)

    def test_permanently_invisible_request_remains_unknown_after_budget(self):
        class Empty:
            def call(self, method, *args, **kwargs):
                assert method == "GET"
                return {"totalPages": 1, "items": []}
        with fake_clock():
            value = load.drain(Empty(), "buyer", {"productId": "p"}, self.unknown(), 6)
        self.assertEqual(value["queryCosts"]["queries"], 3)
        self.assertEqual(len(value["unresolvedRequests"]), 1)
        self.assertFalse(value["drainComplete"])

    def test_started_only_corrupt_tail_and_nonobject_rows_are_collection_failures(self):
        rows = [json.dumps({"event": "started", "iteration": 7, "startedAt": "2026-11-13T00:00:00Z"}),
                '{"event":', "null", "[]"]
        value = load.read_ledger(rows)
        self.assertFalse(value["ledgerComplete"])
        self.assertEqual(value["invalidRows"], 3)
        self.assertEqual(value["unknown"], [{"phase": "measurement", "iteration": 7, "reason": "response_missing"}])

    def test_page_failure_keeps_partial_snapshot_and_costs(self):
        class Fails:
            calls = 0

            def call(self, method, *args, **kwargs):
                assert method == "GET"
                self.calls += 1
                if self.calls == 2:
                    raise load.ApiError("GET", "/api/orders", 503, "COMMON_TEMPORARILY_UNAVAILABLE")
                return {"totalPages": 2, "items": [{"id": "late", "productId": "p", "status": "PAYMENT_PENDING"}]}
        captured = []
        value = load.drain(Fails(), "buyer", {"productId": "p"}, self.unknown(), 30, captured.append)
        self.assertEqual(value["states"], {"PAYMENT_PENDING": 1})
        self.assertFalse(value["snapshotComplete"])
        self.assertEqual(value["observationError"]["status"], 503)
        self.assertEqual(value["queryCosts"]["queries"], 2)
        self.assertEqual(captured[-1], value)

    def test_malformed_second_page_keeps_the_first_page(self):
        class InvalidPage:
            calls = 0
            def call(self, *args, **kwargs):
                self.calls += 1
                return {"totalPages": 2, "items": [
                    {"id": "late", "productId": "p", "status": "PAYMENT_PENDING"}] if self.calls == 1 else None}
        value = load.drain(InvalidPage(), "buyer", {"productId": "p"}, self.unknown(), 30)
        self.assertEqual(value["states"], {"PAYMENT_PENDING": 1})
        self.assertEqual(value["observationError"], {"errorType": "ValueError"})
        self.assertFalse(value["drainComplete"])

    def test_main_interrupt_and_exception_read_original_ledger_before_cleanup(self):
        import subprocess
        for failure in (KeyboardInterrupt, RuntimeError):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                fixture, target = ApiFixture("https://load.test"), Path(tmp) / "run"
                def interrupt(*args):
                    (target / "requests.jsonl").write_text(json.dumps({
                        "event": "started", "iteration": 0, "startedAt": "2026-11-13T00:00:00Z"}) + "\n")
                    raise failure
                docker = subprocess.CompletedProcess([], 0, '[{"Id":"sha256:fixture","RepoDigests":[]}]')
                with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                        patch.object(load.shutil, "which", return_value="docker"), \
                        patch.object(load.subprocess, "run", return_value=docker), \
                        patch.object(load, "inject", side_effect=interrupt), \
                        patch.object(load, "drain", wraps=load.drain) as drain, \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    code = load.main(["--base-url", fixture.base, "--tps", "1", "--seconds", "1",
                                      "--drain-seconds", "0", "--output-dir", str(target)])
                report = json.loads((target / "summary.json").read_text())
                self.assertEqual(code, 130 if failure is KeyboardInterrupt else 1)
                self.assertEqual(drain.call_count, 1)
                self.assertTrue(report["recoveryRequired"])
                self.assertTrue(any(row["reason"] == "response_missing" for row in report["unresolvedRequests"]))
                self.assertFalse(report["passed"])
                self.assertEqual(sum(method == "POST" and path == "/api/orders"
                                     for method, path, *_ in fixture.calls), 1)  # Smoke only.

    def test_resume_preserves_failed_run_and_never_creates_an_order(self):
        import hashlib
        with tempfile.TemporaryDirectory() as tmp:
            original = Path(tmp)
            values = {"plan.json": {"runId": "run", "baseUrl": "https://load.test",
                       "productId": "product", "plannedOrders": 1, "smokeOrderId": "smoke", "smokePaid": True},
                      "summary.json": {"passed": False, "failure": "original failure"}}
            for name, value in values.items():
                (original / name).write_text(json.dumps(value))
            (original / "requests.jsonl").write_text(json.dumps({
                "event": "started", "iteration": 0, "startedAt": "2026-11-13T00:00:00Z"}) + "\n")
            before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in original.iterdir()}
            fixture = ApiFixture("https://load.test")
            with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                    patch.object(load, "inject") as inject, contextlib.redirect_stdout(io.StringIO()):
                code = load.main(["--resume", str(original), "--drain-seconds", "0"])
            self.assertEqual(code, 1)
            inject.assert_not_called()
            self.assertFalse(any(method == "POST" and path == "/api/orders" for method, path, *_ in fixture.calls))
            for name, digest in before.items():
                self.assertEqual(hashlib.sha256((original / name).read_bytes()).hexdigest(), digest)
            recovered = json.loads(next(original.glob("recovery-*/summary.json")).read_text())
            self.assertTrue(recovered["recoveryRequired"])
            self.assertFalse(recovered["loadExecuted"])

    def test_cleanup_failure_preserves_primary_failure_and_drain_checkpoint(self):
        import subprocess
        class CleanupFails(ApiFixture):
            def call(self, method, path, *args, **kwargs):
                if method in {"PUT", "DELETE"}:
                    raise load.ApiError(method, path, 503)
                return super().call(method, path, *args, **kwargs)
        with tempfile.TemporaryDirectory() as tmp:
            fixture, target = CleanupFails("https://load.test"), Path(tmp) / "run"
            def fail(*args):
                (target / "requests.jsonl").write_text(json.dumps({
                    "event": "started", "iteration": 0, "startedAt": "2026-11-13T00:00:00Z"}) + "\n")
                (target / "k6-summary.json").write_text('{"metrics":')
                raise RuntimeError("primary injection failure")
            docker = subprocess.CompletedProcess([], 0, '[{"Id":"fixture"}]')
            with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                    patch.object(load.shutil, "which", return_value="docker"), \
                    patch.object(load.subprocess, "run", return_value=docker), \
                    patch.object(load, "inject", side_effect=fail), contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()):
                code = load.main(["--base-url", fixture.base, "--drain-seconds", "0", "--output-dir", str(target)])
            report = json.loads((target / "summary.json").read_text())
            self.assertEqual(code, 1)
            self.assertEqual(report["failure"], "primary injection failure")
            self.assertEqual(len(report["cleanupErrors"]), 3)
            self.assertEqual(report["k6SummaryErrors"]["measurement"], "JSONDecodeError")
            self.assertTrue((target / "order-states.json").is_file())

    def test_resume_accepts_borrowed_session_that_covers_only_recovery(self):
        class Known(ApiFixture):
            def call(self, method, path, *args, **kwargs):
                if path.startswith("/api/orders?"):
                    return {"totalPages": 1, "items": [{"id": "known", "productId": "product", "status": "PAID"}]}
                return super().call(method, path, *args, **kwargs)
        with tempfile.TemporaryDirectory() as tmp:
            original = Path(tmp)
            (original / "plan.json").write_text(json.dumps({"runId": "run", "baseUrl": "https://load.test",
                "productId": "product", "plannedOrders": 1, "measurementSeconds": 60}))
            rows = [{"event": event, "iteration": 0, "startedAt": "2026-11-13T00:00:00Z",
                     "status": 201, "orderId": "known"} for event in ("started", "completed")]
            (original / "requests.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
            load.write_json(original / "k6-summary.json", {"metrics": {"http_reqs": {"count": 1}}})
            expiry = (datetime.now(timezone.utc) + timedelta(seconds=600)).isoformat()
            fixture = Known("https://load.test")
            with patch.dict(os.environ, {"BUYER_TOKEN": "borrowed-buyer", "SELLER_TOKEN": "borrowed-seller",
                    "BUYER_TOKEN_EXPIRES_AT": expiry, "SELLER_TOKEN_EXPIRES_AT": expiry}, clear=True), \
                    patch.object(load, "Api", return_value=fixture), contextlib.redirect_stdout(io.StringIO()):
                code = load.main(["--resume", str(original), "--drain-seconds", "300"])
            self.assertEqual(code, 0)
            self.assertFalse(any(path == "/swagger-ui/fixture-sessions" for _, path, *_ in fixture.calls))

    def test_resume_checks_empty_and_phase_only_ledgers_against_execution_evidence(self):
        for phase_only in (False, True):
            for summary_present in (False, True):
                with self.subTest(phase_only=phase_only, summary_present=summary_present), \
                        tempfile.TemporaryDirectory() as tmp:
                    original = Path(tmp)
                    load.write_json(original / "plan.json", {"runId": "run", "baseUrl": "https://load.test",
                        "productId": "product", "plannedOrders": 2, "measurementSeconds": 60,
                        "smokeOrderId": "smoke", "smokeAttempted": True, "smokePaid": True})
                    rows = [{"event": "phase", "phase": "measurement",
                        "measurementStart": "2026-11-13T00:00:00Z",
                        "measurementEnd": "2026-11-13T00:01:00Z"}] if phase_only else []
                    (original / "requests.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
                    if summary_present:
                        load.write_json(original / "k6-summary.json", {"metrics": {"http_reqs": {"count": 2}}})
                    fixture = ApiFixture("https://load.test")
                    with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                            contextlib.redirect_stdout(io.StringIO()):
                        code = load.main(["--resume", str(original), "--drain-seconds", "0"])
                    report = json.loads(next(original.glob("recovery-*/summary.json")).read_text())
                    self.assertEqual(code, 1)
                    self.assertTrue(report["recoveryRequired"])
                    self.assertFalse(report["recoveryComplete"])
                    self.assertEqual(report["unresolvedRequests"][0]["reason"], "k6_ledger_coverage_unverified")
                    self.assertFalse(any(method == "POST" and path == "/api/orders"
                                         for method, path, *_ in fixture.calls))

    def test_resume_after_warmup_failure_does_not_require_unstarted_measurement(self):
        with tempfile.TemporaryDirectory() as tmp:
            original = Path(tmp)
            load.write_json(original / "plan.json", {"runId": "run", "baseUrl": "https://load.test",
                "productId": "product", "plannedOrders": 2, "warmupOrders": 1, "measurementSeconds": 60,
                "smokeOrderId": "smoke", "smokePaid": True})
            load.write_json(original / "summary.json", {"loadExecuted": True, "phaseBeforeRecovery": "warmup",
                                                       "failure": "warmup threshold failure"})
            rows = [{"event": event, "phase": "warmup", "iteration": 0,
                     "startedAt": "2026-11-13T00:00:00Z", "status": 201, "orderId": "known"}
                    for event in ("started", "completed")]
            (original / "requests.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
            load.write_json(original / "k6-warmup-summary.json", {"metrics": {"http_reqs": {"count": 1}}})
            fixture = ApiFixture("https://load.test")
            original_call = fixture.call
            def known(method, path, *args, **kwargs):
                if path.startswith("/api/orders?"):
                    return {"totalPages": 1, "items": [{"id": "known", "productId": "product", "status": "PAID"}]}
                return original_call(method, path, *args, **kwargs)
            fixture.call = known
            with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                    contextlib.redirect_stdout(io.StringIO()):
                code = load.main(["--resume", str(original), "--drain-seconds", "0"])
            report = json.loads(next(original.glob("recovery-*/summary.json")).read_text())
            self.assertEqual(code, 0)
            self.assertTrue(report["recoveryComplete"])
            self.assertNotIn("k6SummaryErrors", report)

    def test_interrupted_collection_warning_survives_resume_with_same_evidence(self):
        import subprocess
        for recorded in (0, 1):
            with self.subTest(recorded=recorded), tempfile.TemporaryDirectory() as tmp:
                target, fixture = Path(tmp) / "run", ApiFixture("https://load.test")
                original_call = fixture.call
                def orders(method, path, *args, **kwargs):
                    if path.startswith("/api/orders?"):
                        return {"totalPages": 1, "items": [{"id": ident, "productId": "product", "status": "PAID"}
                            for ident in (["smoke", "known"] if recorded else ["smoke"])]}
                    return original_call(method, path, *args, **kwargs)
                fixture.call = orders
                def interrupt(base, buyer, plan, directory, args, phase):
                    rows = [{"event": "phase", "phase": phase, "measurementStart": "2026-11-13T00:00:00Z",
                             "measurementEnd": "2026-11-13T00:00:02Z"}]
                    if recorded:
                        rows += [{"event": event, "phase": phase, "iteration": 0,
                            "startedAt": "2026-11-13T00:00:00Z", "status": 201, "orderId": "known"}
                            for event in ("started", "completed")]
                    (directory / "requests.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
                    load.write_json(directory / "k6-summary.json", {"metrics": {"http_reqs": {"count": recorded}}})
                    raise KeyboardInterrupt
                docker = subprocess.CompletedProcess([], 0, '[{"Id":"fixture"}]')
                with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                        patch.object(load.shutil, "which", return_value="docker"), \
                        patch.object(load.subprocess, "run", return_value=docker), \
                        patch.object(load, "inject", side_effect=interrupt) as inject, \
                        contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(load.main(["--base-url", fixture.base, "--tps", "1", "--seconds", "2",
                        "--drain-seconds", "0", "--output-dir", str(target)]), 130)
                    before = (target / "summary.json").read_bytes()
                    calls = inject.call_count
                    self.assertEqual(load.main(["--resume", str(target), "--drain-seconds", "0"]), 1)
                    self.assertEqual(inject.call_count, calls)
                report = json.loads(next(target.glob("recovery-*/summary.json")).read_text())
                self.assertTrue(report["recoveryRequired"])
                self.assertFalse(report["recoveryComplete"])
                self.assertTrue(any(row["reason"] == "injection_interrupted_without_complete_coverage"
                                    for row in report["unresolvedRequests"]))
                self.assertEqual((target / "summary.json").read_bytes(), before)

    def test_unverified_original_summary_cannot_clear_partial_collection(self):
        for source in (None, '{"loadExecuted":', '{}', '{"loadExecuted":true}',
                       '{"loadExecuted":true,"phaseBeforeRecovery":"measurement"}'):
            for recorded in (0, 1, 2):
                with self.subTest(source=source, recorded=recorded), tempfile.TemporaryDirectory() as tmp:
                    original, fixture = Path(tmp), ApiFixture("https://load.test")
                    load.write_json(original / "plan.json", {"runId": "run", "baseUrl": fixture.base,
                        "productId": "product", "plannedOrders": 2, "measurementSeconds": 60,
                        "smokeOrderId": "smoke", "smokePaid": True})
                    if source is not None:
                        (original / "summary.json").write_text(source)
                    rows = [{"event": "phase", "phase": "measurement",
                        "measurementStart": "2026-11-13T00:00:00Z", "measurementEnd": "2026-11-13T00:01:00Z"}]
                    for n in range(recorded):
                        rows += [{"event": event, "iteration": n, "startedAt": "2026-11-13T00:00:00Z",
                                  "status": 201, "orderId": str(n)} for event in ("started", "completed")]
                    (original / "requests.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
                    load.write_json(original / "k6-summary.json", {"metrics": {"http_reqs": {"count": recorded}}})
                    original_call = fixture.call
                    def orders(method, path, *args, **kwargs):
                        if path.startswith("/api/orders?"):
                            return {"totalPages": 1, "items": [{"id": str(n), "productId": "product", "status": "PAID"}
                                                               for n in range(recorded)]}
                        return original_call(method, path, *args, **kwargs)
                    fixture.call = orders
                    with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                            contextlib.redirect_stdout(io.StringIO()):
                        code = load.main(["--resume", str(original), "--drain-seconds", "0"])
                    report = json.loads(next(original.glob("recovery-*/summary.json")).read_text())
                    self.assertEqual(code, 0 if recorded == 2 else 1)
                    self.assertEqual(report["recoveryRequired"], recorded < 2)
                    self.assertEqual(report["recoveryComplete"], recorded == 2)

    def test_cleanup_parse_failures_keep_primary_failure_summary_and_remaining_logouts(self):
        class InvalidCleanup(ApiFixture):
            fail_inventory = False
            def call(self, method, path, *args, **kwargs):
                if path.endswith("/inventory") and self.fail_inventory:
                    raise json.JSONDecodeError("private response", "", 0)
                if path == "/api/sessions/current":
                    self.logouts.append(kwargs.get("token"))
                    if len(self.logouts) == 1:
                        raise json.JSONDecodeError("private response", "", 0)
                    return {}
                value = super().call(method, path, *args, **kwargs)
                if method == "POST" and path == "/api/orders":
                    self.fail_inventory = True
                return value
        with tempfile.TemporaryDirectory() as tmp:
            fixture, target = InvalidCleanup("https://load.test"), Path(tmp) / "run"
            fixture.paid = False
            with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = load.main(["--base-url", fixture.base, "--prepare-only", "--output-dir", str(target)])
            report = json.loads((target / "summary.json").read_text())
            self.assertEqual(code, 1)
            self.assertIn("사전 주문이 PAID", report["failure"])
            self.assertEqual(len(fixture.logouts), 2)
            self.assertEqual(len(report["cleanupErrors"]), 2)
            self.assertTrue(any("재고" in error for error in report["cleanupErrors"]))
            self.assertNotIn("private response", json.dumps(report))
            self.assertTrue((target / "order-states.json").is_file())

    def test_docker_preparation_failure_is_not_a_load_execution(self):
        with tempfile.TemporaryDirectory() as tmp, patch.object(load.shutil, "which", return_value=None), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            target = Path(tmp) / "run"
            code = load.main(["--base-url", "https://load.test", "--output-dir", str(target)])
            report = json.loads((target / "summary.json").read_text())
        self.assertEqual(code, 1)
        self.assertFalse(report["loadExecuted"])
        self.assertEqual(report["failurePhase"], "preparation")


class SessionTest(unittest.TestCase):
    def test_owned_session_renews_between_phases_without_extending_policy(self):
        class Sessions:
            issued = 0

            def call(self, *args, **kwargs):
                self.issued += 1
                return {"token": str(self.issued) + "x" * 40, "account": {"id": "same-buyer"},
                        "expiresAt": datetime.fromtimestamp(load.time.time() + 1800, timezone.utc).isoformat()}
        api, owned, sessions = Sessions(), [], {}
        with patch.dict(os.environ, {}, clear=True), patch.object(load.time, "time", return_value=1000):
            first = load.login(api, "BUYER", owned, sessions)
        with patch.object(load.time, "time", return_value=2000):
            second = load.session_for(api, "BUYER", owned, sessions, 900)
        self.assertNotEqual(first, second)
        self.assertEqual(len(owned), 2)
        self.assertEqual(sessions["BUYER"]["expiry"], 3800)
        self.assertEqual(sessions["_issued"][first], 2800)

    def test_borrowed_session_with_insufficient_budget_never_renews(self):
        sessions = {"BUYER": {"token": "borrowed", "owned": False, "expiry": 1000}}
        with patch.object(load.time, "time", return_value=900), patch.object(load, "login") as login, \
                self.assertRaisesRegex(RuntimeError, "만료"):
            load.session_for(None, "BUYER", [], sessions, 900)
        login.assert_not_called()

    def test_invalid_borrowed_expiry_is_preparation_failure_before_any_product(self):
        fixture = ApiFixture("https://load.test")
        with tempfile.TemporaryDirectory() as tmp, \
                patch.dict(os.environ, {"BUYER_TOKEN": "secret"}, clear=True), \
                patch.object(load, "Api", return_value=fixture), contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()):
            target = Path(tmp) / "run"
            code = load.main(["--base-url", fixture.base, "--prepare-only", "--output-dir", str(target)])
            report = json.loads((target / "summary.json").read_text())
        self.assertEqual(code, 1)
        self.assertFalse(report["loadExecuted"])
        self.assertFalse(fixture.calls)
        self.assertNotIn("secret", json.dumps(report))


class PhaseTest(unittest.TestCase):
    def test_missing_warmup_pair_blocks_measurement_even_when_all_server_orders_are_paid(self):
        import subprocess
        class Warmup(ApiFixture):
            def call(self, method, path, *args, **kwargs):
                if path.startswith("/api/orders?"):
                    return {"totalPages": 1, "items": [{"id": key, "productId": "product", "status": "PAID"}
                                                      for key in ("smoke", "warm-0", "unlogged")]}
                return super().call(method, path, *args, **kwargs)
        with tempfile.TemporaryDirectory() as tmp:
            target, fixture = Path(tmp) / "run", Warmup("https://load.test")
            def partial(base, buyer, plan, directory, args, phase):
                self.assertEqual(phase, "warmup")
                rows = [{"event": "phase", "phase": "warmup", "measurementStart": "2026-11-13T00:00:00Z",
                         "measurementEnd": "2026-11-13T00:00:02Z"}]
                rows += [{"event": event, "phase": "warmup", "iteration": 0,
                          "startedAt": "2026-11-13T00:00:00Z", "status": 201, "orderId": "warm-0"}
                         for event in ("started", "completed")]
                (directory / "requests.jsonl").write_text("".join(json.dumps(row) + "\n" for row in rows))
                load.write_json(directory / "k6-warmup-summary.json", {"metrics": {
                    "http_reqs": {"count": 2}, "dropped_iterations": {"count": 0}}})
                return 0
            docker = subprocess.CompletedProcess([], 0, '[{"Id":"fixture"}]')
            with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                    patch.object(load.shutil, "which", return_value="docker"), \
                    patch.object(load.subprocess, "run", return_value=docker), \
                    patch.object(load, "inject", side_effect=partial) as inject, \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = load.main(["--base-url", fixture.base, "--tps", "1", "--seconds", "900",
                    "--warmup-seconds", "2", "--drain-seconds", "0", "--output-dir", str(target)])
            report = json.loads((target / "summary.json").read_text())
            self.assertEqual(code, 1)
            self.assertEqual(inject.call_count, 1)
            self.assertFalse(report["warmupInjectionPassed"])
            self.assertNotIn("measurement", report["k6PhaseExitCodes"])
            self.assertTrue(report["recoveryRequired"])

    def test_k6_phase_keys_window_error_fields_and_boundary_cap_with_short_vm(self):
        import shutil
        import subprocess
        if not shutil.which("node"):
            self.skipTest("Optional k6 script VM check requires the repository frontend's Node.js runtime")
        body = load.prepare_order(ApiFixture("https://load.test"), "buyer", "seller",
                                  load.prepare(ApiFixture("https://load.test"), "buyer", "seller", "run", 1))
        script = r"""
const fs = require('fs'), vm = require('vm');
const source = fs.readFileSync(process.argv[1], 'utf8')
  .replace(/^import .*;\r?\n/gm, '')
  .replace('export const options =', 'const options =')
  .replace('export default function ()', 'function run()');
const results = [];
for (const phase of ['warmup', 'measurement']) {
  const logs = [], keys = [];
  const context = {
    logs, keys,
    __ENV: {BASE_URL:'https://fixture.test',BUYER_TOKEN:'fixture-secret',RUN_ID:'run',
      RATE:'200',DURATION_SECONDS:'900',LOAD_PHASE:phase},
    open: () => process.argv[2],
    exec: {scenario:{iterationInTest:0,startTime:1794528000000}},
    check: () => false,
    console:{log: value => logs.push(value)},
    http:{post:(url,body,params) => {
      keys.push(params.headers['Idempotency-Key']);
      return {status:0,error_code:1050,error:'fixture-secret https://fixture.test/secret',
              timings:{duration:5000},json:()=>({})};
    }},
  };
  results.push(JSON.parse(vm.runInNewContext(source +
    ';run();exec.scenario.iterationInTest=180000;run();JSON.stringify({options,logs,keys});', context)));
}
process.stdout.write(JSON.stringify(results));
"""
        result = subprocess.run(["node", "-e", script, str(HERE / "orders.js"), json.dumps(body)],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        warmup, measured = json.loads(result.stdout)
        self.assertEqual(warmup["keys"], ["run-warmup-0"])
        self.assertEqual(measured["keys"], ["run-load-0"])
        self.assertEqual(measured["options"]["scenarios"]["orders"]["duration"], "900s")
        window = json.loads(measured["logs"][0].split(" ", 1)[1])
        self.assertEqual(load.timestamp(window["measurementEnd"]) - load.timestamp(window["measurementStart"]), 900)
        row = json.loads(measured["logs"][-1].split(" ", 1)[1])
        self.assertEqual(row["clientErrorCode"], 1050)
        self.assertNotIn("fixture-secret", row["clientError"])
        self.assertNotIn("https://", row["clientError"])

    def test_900_second_measurement_separates_warmup_without_business_throughput_aggregation(self):
        import subprocess
        from urllib.parse import parse_qs, urlsplit

        class FullApi(ApiFixture):
            orders = {}

            def call(self, method, path, *args, **kwargs):
                if path.startswith("/api/orders?"):
                    page = int(parse_qs(urlsplit(path).query)["page"][0])
                    rows = list(self.orders.values())
                    return {"totalPages": max(1, (len(rows) + 99) // 100), "items": rows[page * 100:(page + 1) * 100]}
                value = super().call(method, path, *args, **kwargs)
                if method == "POST" and path == "/api/orders":
                    self.orders[value["id"]] = {**value, "productId": "product"}
                return value

        fixture = FullApi("https://load.test")
        def inject(base, buyer, plan, directory, args, phase):
            duration = args.warmup_seconds if phase == "warmup" else args.seconds
            start = datetime(2026, 11, 13, tzinfo=timezone.utc) - timedelta(seconds=10 if phase == "warmup" else 0)
            end = start + timedelta(seconds=duration)
            with (directory / "requests.jsonl").open("a") as output:
                output.write(json.dumps({"event": "phase", "phase": phase,
                    "measurementStart": start.isoformat(), "measurementEnd": end.isoformat()}) + "\n")
                for n in range(args.rate * duration):
                    ident = phase + "-" + str(n)
                    output.write(json.dumps({"phase": phase, "event": "started", "iteration": n,
                                             "startedAt": start.isoformat()}) + "\n")
                    output.write(json.dumps({"phase": phase, "event": "completed", "iteration": n,
                        "startedAt": start.isoformat(), "status": 201, "orderId": ident}) + "\n")
                    fixture.orders[ident] = {"id": ident, "productId": "product", "status": "PAID",
                        "paymentConfirmedAt": (end + timedelta(seconds=1)).isoformat()}
            name = "k6-summary.json" if phase == "measurement" else "k6-warmup-summary.json"
            load.write_json(directory / name, {"metrics": {"http_reqs": {"count": args.rate * duration},
                                                          "dropped_iterations": {"count": 0}}})
            return 0
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "run"
            metadata = Path(tmp) / "conditions.json"
            metadata.write_text(json.dumps({"revision": "a" * 40, "conditions": {"scenario": "fixture"}}))
            docker = subprocess.CompletedProcess([], 0, '[{"Id":"sha256:fixture","RepoDigests":[]}]')
            with patch.dict(os.environ, {}, clear=True), patch.object(load, "Api", return_value=fixture), \
                    patch.object(load.shutil, "which", return_value="docker"), \
                    patch.object(load.subprocess, "run", return_value=docker), patch.object(load, "inject", side_effect=inject), \
                    contextlib.redirect_stdout(io.StringIO()):
                code = load.main(["--base-url", fixture.base, "--tps", "1", "--seconds", "900",
                    "--warmup-seconds", "2", "--drain-seconds", "1", "--output-dir", str(target),
                    "--performance-metadata", str(metadata)])
            report = json.loads((target / "summary.json").read_text())
            states = json.loads((target / "order-states.json").read_text())
            linked = json.loads((target / "performance-metadata.json").read_text())
            self.assertEqual(code, 0, {key: report.get(key) for key in ("failure", "drainFailure", "drainComplete")})
            self.assertTrue(report["injectionPassed"])
            self.assertEqual(report["plannedOrders"], 900)
            self.assertEqual(states["measurementStates"], {"PAID": 900})
            self.assertEqual(states["states"], {"PAID": 902})
            self.assertNotIn("commerceConfirmedWithinMeasurement", states)
            self.assertNotIn("paymentConfirmedAtCounts", states)
            self.assertEqual(report["performanceVerification"]["status"], "UNVERIFIED")
            self.assertEqual(report["measurementWindow"]["end"] - report["measurementWindow"]["start"], 900)
            self.assertEqual(linked["revision"], "a" * 40)
            self.assertEqual(linked["loadRun"]["summary"]["sha256"], load.file_sha(target / "summary.json"))
            self.assertEqual(linked["loadRun"]["ledger"]["sha256"], load.file_sha(target / "requests.jsonl"))
            self.assertEqual(json.loads(metadata.read_text()), {"revision": "a" * 40, "conditions": {"scenario": "fixture"}})

    def test_900_second_inventory_includes_warmup_and_smoke(self):
        fixture = ApiFixture("https://load.test")
        with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {}, clear=True), \
                patch.object(load, "Api", return_value=fixture), contextlib.redirect_stdout(io.StringIO()):
            target = Path(tmp) / "run"
            code = load.main(["--base-url", fixture.base, "--tps", "200", "--seconds", "900",
                             "--warmup-seconds", "60", "--prepare-only", "--output-dir", str(target)])
            plan = json.loads((target / "plan.json").read_text())
        self.assertEqual(code, 0)
        self.assertEqual((plan["plannedOrders"], plan["warmupOrders"], plan["stockPrepared"]), (180000, 12000, 211201))

    def test_python_transport_failure_preserves_type_without_raw_secret(self):
        from urllib.error import URLError
        api = load.Api("https://load.test")
        with patch.object(api.opener, "open", side_effect=URLError(TimeoutError("secret transport detail"))), \
                self.assertRaises(load.ApiError) as caught:
            api.call("GET", "/api/orders")
        self.assertEqual(caught.exception.evidence()["transportErrorType"], "TimeoutError")
        self.assertNotIn("secret", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
