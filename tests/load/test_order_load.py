"""공개 API 준비 계약, 실패 시 추가 주입 차단과 결과 판정을 검사한다."""

import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
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
            return {"token": body["account"] + "-" * 40}
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
                patch.dict(os.environ, {"BUYER_TOKEN": "borrowed-buyer", "SELLER_TOKEN": "borrowed-seller"}), \
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
                         "snapshotComplete": True, "states": {"PAID": 2}}

    def test_zero_errors_does_not_hide_drops_missing_or_duplicate_orders(self):
        self.assertTrue(load.verdict(self.plan, self.records, self.summary, 0, self.observed))
        self.summary["metrics"]["dropped_iterations"]["count"] = 1
        self.assertFalse(load.verdict(self.plan, self.records, self.summary, 0, self.observed))
        self.summary["metrics"]["dropped_iterations"]["count"] = 0
        self.records[1]["orderId"] = "0"
        self.assertFalse(load.verdict(self.plan, self.records, self.summary, 0, self.observed))

    def test_pending_or_missing_metrics_is_not_success(self):
        self.observed["states"] = {"PAID": 1, "PAYMENT_PENDING": 1}
        self.assertFalse(load.verdict(self.plan, self.records, self.summary, 0, self.observed))

    def test_partial_snapshot_does_not_claim_all_orders_checked(self):
        self.observed["snapshotComplete"] = False
        self.assertFalse(load.verdict(self.plan, self.records, self.summary, 0, self.observed))
        class SlowPages:
            def call(self, *args, **kwargs):
                return {"items": [], "totalPages": 100}
        value, complete = load.orders_for_product(SlowPages(), "buyer", "product", 0)
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
        self.assertFalse(load.verdict(self.plan, self.records, self.summary, 0, self.observed))

    def test_drain_excludes_smoke_and_reports_unacknowledged_created_order(self):
        class Orders:
            def call(self, method, path, **kwargs):
                return {"totalPages": 1, "items": [
                    {"id": ident, "productId": "product", "status": "PAID",
                     "paymentConfirmedAt": "2026-11-12T10:00:00+09:00"}
                    for ident in ("smoke", "0", "1", "unknown")]}
        value = load.drain(Orders(), "buyer", {"productId": "product", "smokeOrderId": "smoke"},
                           self.records, 0)
        self.assertEqual(value["states"], {"PAID": 3})
        self.assertEqual(value["ordersWithout201Receipt"], ["unknown"])
        self.assertEqual(sum(value["paymentConfirmedAtCounts"].values()), 3)
        self.assertIn("미수행", value["financialReconciliation"])

    def test_drain_waits_for_order_created_without_201_receipt(self):
        pending = {"unknown": {"id": "unknown", "status": "PAYMENT_PENDING"}}
        paid = {"unknown": {"id": "unknown", "status": "PAID"}}
        with patch.object(load, "orders_for_product", side_effect=[(pending, True), (paid, True)]), \
                patch.object(load.time, "sleep") as sleep:
            value = load.drain(None, "buyer", {"productId": "product", "smokeOrderId": "smoke"}, [], 30)
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
        cleanup.assert_called_once_with("cs-orders-run", "run")

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


if __name__ == "__main__":
    unittest.main()
