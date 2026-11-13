import gzip
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import signal
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import Mock, patch
import uuid

import evidence
import performance


TRACE = "1" * 32
SECOND = "2" * 32


def span(identity="a" * 16, **extra):
    return {"traceId": TRACE, "id": identity, "name": "payment.approval.process", "timestamp": 1234,
            "duration": 20, "localEndpoint": {"serviceName": "payment"},
            "tags": {"Authorization": "secret-token", "sql.query": "secret-query", "operation": "approve"}, **extra}


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.run_id = uuid.uuid4().hex
        self.run = self.root / ("orders-" + self.run_id)
        self.run.mkdir()
        (self.run / "plan.json").write_text(json.dumps({"runId": self.run_id}))

    def archive(self, **options):
        value = evidence.Archive(self.run, reserve=0, **options)
        self.addCleanup(value.close)
        return value

    def resources(self):
        stamp = datetime.fromtimestamp(time.time() - .1, timezone.utc).isoformat()
        paths = []
        source = evidence.digest(Path(evidence.__file__).with_name("resource_sample.py").read_bytes())
        for role in ("app", "data"):
            row = {"runId": self.run_id, "hostRole": role, "hostId": role + "-host", "ts": stamp,
                   "endedAt": stamp, "collectorSha256": source, "docker": [], "host": {"id": 90},
                   "disk": {"nvme0n1": {"%util": 1}}}
            if role == "data":
                row["postgres"] = {"observedAt": stamp, "computeQueryId": "on",
                    "databases": ["commerce", "payment", "settlement", "finance_mock"],
                    "activity": [], "locks": []}
            path = self.root / (role + ".jsonl")
            path.write_text(json.dumps(row) + "\n")
            paths.append(path)
        return paths

    def resolved(self, **changes):
        value = {"runId": self.run_id, "recoveryRequired": False, "recoveryComplete": True, "drainComplete": True, **changes}
        (self.run / "summary.json").write_text(json.dumps(value))

    def complete(self, archive):
        archive.put_trace(TRACE, [span()])
        self.assertTrue(archive.finish(True))
        self.resolved()

    def test_duplicate_late_and_shared_spans_preserve_identity_without_secrets(self):
        archive = self.archive()
        archive.put_trace(TRACE, [span()])
        original = (archive.path / ("trace-" + TRACE + ".json.gz")).read_bytes()
        archive.put_trace(TRACE, [span()])
        self.assertEqual(original, (archive.path / ("trace-" + TRACE + ".json.gz")).read_bytes())
        archive.put_trace(TRACE, [span("b" * 16, parentId="a" * 16), span(kind="SERVER")])
        rows = json.loads(evidence.unpack((archive.path / ("trace-" + TRACE + ".json.gz")).read_bytes()))
        self.assertEqual(len(rows), 3)
        self.assertNotIn("secret", json.dumps(rows))
        with self.assertRaises(ValueError):
            archive.put_trace(SECOND, [span()])

    def test_resume_keeps_previous_data_and_fixed_budget(self):
        first = self.archive()
        first.put_trace(TRACE, [span()])
        first.close()
        with self.assertRaises(ValueError):
            self.archive(resume=True, maximum=2 * evidence.DEFAULT_BUDGET)
        recovered = self.archive(resume=True)
        recovered.put_trace(TRACE, [span("b" * 16, parentId="a" * 16)])
        rows = json.loads(evidence.unpack((recovered.path / ("trace-" + TRACE + ".json.gz")).read_bytes()))
        self.assertEqual(len(rows), 2)
        self.assertTrue(recovered.finish(True))

    def test_completed_payload_is_recovered_after_manifest_commit_failure(self):
        first = self.archive()
        original = first._atomic
        def failure(name, raw):
            original(name, raw)
            if name.startswith("trace-"):
                raise OSError("commit interrupted")
        with patch.object(first, "_atomic", side_effect=failure), self.assertRaises(OSError):
            first.put_trace(TRACE, [span()])
        first.close()
        recovered = self.archive(resume=True)
        self.assertIn("trace-" + TRACE + ".json.gz", recovered.meta["files"])
        self.assertEqual(recovered.meta["failures"][-1]["kind"], "interrupted-publication")

    def test_byte_and_disk_budgets_preserve_existing_failed_evidence(self):
        archive = self.archive(maximum=128 * 1024, total=128 * 1024)
        archive.put_trace(TRACE, [span()])
        path = archive.path / ("trace-" + TRACE + ".json.gz")
        before = path.read_bytes()
        with self.assertRaises(OSError):
            archive.sample({"noise": os.urandom(90 * 1024).hex()})
        self.assertEqual(path.read_bytes(), before)
        self.assertEqual(archive.meta["state"], "failed")
        with patch.object(evidence.shutil, "disk_usage", return_value=types.SimpleNamespace(free=0)), self.assertRaises(OSError):
            archive.sample({"tiny": True})
        self.assertEqual(path.read_bytes(), before)
        with self.assertRaises(ValueError):
            archive.review()

    def test_project_total_budget_and_trace_count_are_enforced(self):
        first = self.archive(maximum=128 * 1024, total=128 * 1024, max_traces=1)
        first.sample({"noise": os.urandom(30 * 1024).hex()})
        first.expect(TRACE)
        with self.assertRaises(ValueError):
            first.expect(SECOND)
        other = self.root / ("orders-" + "f" * 32)
        other.mkdir()
        (other / "plan.json").write_text(json.dumps({"runId": "f" * 32}))
        second = evidence.Archive(other, maximum=128 * 1024, total=128 * 1024, reserve=0)
        self.addCleanup(second.close)
        with self.assertRaises(OSError):
            second.sample({"noise": os.urandom(30 * 1024).hex()})
        self.assertTrue((first.path / "sample-00000000.json.gz").is_file())

    def test_failed_write_missing_trace_and_empty_capture_cannot_be_complete(self):
        archive = self.archive()
        self.assertFalse(archive.finish(True))
        archive.expect(TRACE)
        self.assertFalse(archive.finish(True))
        original = archive._atomic
        def failure(name, raw):
            if name.startswith("trace-"):
                raise PermissionError("read-only output")
            original(name, raw)
        with patch.object(archive, "_atomic", side_effect=failure), self.assertRaises(PermissionError):
            archive.put_trace(TRACE, [span()])
        self.assertIsNotNone(archive.meta["pending"])

    def test_symlinks_hardlinks_foreign_files_and_wrong_digest_are_not_deleted(self):
        archive = self.archive()
        self.complete(archive)
        archive.review()
        sha = evidence.digest((archive.path / "owner.json").read_bytes())
        with self.assertRaises(ValueError):
            archive.cleanup("0" * 64)
        foreign = self.root / "user.txt"
        foreign.write_text("preserve user bytes")
        link = archive.path / "foreign"
        link.symlink_to(foreign)
        with self.assertRaises(ValueError):
            archive.cleanup(sha)
        self.assertEqual(foreign.read_text(), "preserve user bytes")
        link.unlink()
        os.link(foreign, link)
        with self.assertRaises(ValueError):
            archive.cleanup(sha)
        link.unlink()
        self.assertEqual(foreign.read_text(), "preserve user bytes")

    def test_review_requires_resolved_transactions_and_cleanup_is_exact(self):
        archive = self.archive()
        self.complete(archive)
        self.resolved(recoveryRequired=True)
        with self.assertRaises(ValueError):
            archive.review()
        self.resolved()
        archive.review()
        sha = evidence.digest((archive.path / "owner.json").read_bytes())
        archive.cleanup(sha)
        self.assertFalse(archive.path.exists())
        self.assertTrue((self.run / "plan.json").is_file())
        self.assertTrue((self.run / "summary.json").is_file())

    def test_cleanup_interruption_can_resume_with_original_review_digest(self):
        archive = self.archive()
        self.complete(archive)
        archive.review()
        sha = evidence.digest((archive.path / "owner.json").read_bytes())
        original = Path.unlink
        def failure(path, *args, **kwargs):
            if path.name.startswith("trace-"):
                raise PermissionError("interrupted cleanup")
            return original(path, *args, **kwargs)
        with patch.object(Path, "unlink", failure), self.assertRaises(PermissionError):
            archive.cleanup(sha)
        archive.close()
        reopened = evidence.Archive(self.run, reading=True)
        self.addCleanup(reopened.close)
        reopened.cleanup(sha)
        self.assertFalse(reopened.path.exists())

    def test_same_run_cannot_be_written_by_two_collectors(self):
        self.archive()
        with self.assertRaises(BlockingIOError):
            self.archive(resume=True)

    def test_decompression_bomb_is_bounded(self):
        with self.assertRaises(ValueError):
            evidence.unpack(gzip.compress(b"x" * (8 * 1024 * 1024 + 1)))

    def query(self, base, expression, at, **kwargs):
        def rows(count, metric=None, value="0"):
            return [{"metric": {"slot": str(i), **(metric or {})}, "value": [at, value]} for i in range(count)]
        if expression.startswith("business:source"):
            return [{"metric": {"operation": op}, "value": [at, str(at)]} for op in performance.OPERATIONS]
        if expression == "runtime:current":
            return rows(1, {"instance": "observer:9109"}, str(at))
        if expression in ("financial:queue_value", "financial:due_queue_value"):
            return rows(8)
        if expression == "financial:group_value":
            return rows(3)
        if expression == "financial:store_value":
            return rows(2, value=".1")
        if expression in ("commerce_settle_docker_host_memory_available_ratio", "commerce_settle_docker_host_cpu_usage_ratio"):
            return rows(1, {"instance": "observer:9109"}, ".5")
        if expression == "commerce_settle_container_memory_usage_ratio":
            return [{"metric": {"service": service}, "value": [at, ".5"]} for service in
                    ["gateway", "commerce", "payment-api", "payment-worker", "settlement", "finance-mock", "postgres", "kafka", "redis"]]
        if expression in ("commerce_settle_container_cpu_usage_ratio", "jdbc_query_seconds_sum", "hikaricp_connections_active"):
            return rows(1)
        return []

    def test_missing_stale_future_and_wrong_runtime_sources_block_before_thread_start(self):
        archive = self.archive()
        resources = self.resources()
        session = evidence.EvidenceSession(archive, "http://prom", "http://zip", runtime_targets=["observer:9109"], resource_files=resources)
        for bad in ([], [{"metric": {"operation": op}, "value": [0, "-1000"]} for op in performance.OPERATIONS],
                    [{"metric": {"operation": op}, "value": [0, "9999999999999"]} for op in performance.OPERATIONS]):
            with self.subTest(bad=bool(bad)), patch.object(performance, "query", return_value=bad), self.assertRaises(ValueError):
                session.start()
            self.assertIsNone(session.thread.ident)
        with self.assertRaises(ValueError):
            evidence.EvidenceSession(archive, "http://prom", "http://zip", runtime_targets=["other:9109"], resource_files=resources)

    def test_missing_required_host_cannot_be_masked_by_another_healthy_host(self):
        archive = self.archive()
        session = evidence.EvidenceSession(archive, "http://prom", "http://zip",
                                           runtime_targets=["observer:9109", "data:9109"], resource_files=self.resources())
        with patch.object(performance, "query", side_effect=self.query), self.assertRaises(ValueError):
            session.start()
        self.assertIsNone(session.thread.ident)

    def test_monitor_failure_interrupts_only_the_bound_load_process(self):
        archive = self.archive()
        session = evidence.EvidenceSession(archive, "http://prom", "http://zip", runtime_targets=["observer:9109"], resource_files=self.resources(), interval=.01)
        own, foreign, interrupted = Mock(), Mock(), threading.Event()
        own.poll.return_value = None
        own.send_signal.side_effect = lambda value: interrupted.set()
        session.bind(own)
        with patch.object(performance, "query", side_effect=self.query):
            session.start()
            with patch.object(session, "capture", side_effect=ValueError("missing observation")):
                self.assertTrue(interrupted.wait(2))
            self.assertFalse(session.finish(False))
        own.send_signal.assert_called_once_with(signal.SIGINT)
        foreign.send_signal.assert_not_called()

    def test_startup_capture_failure_never_becomes_a_completed_archive(self):
        archive = self.archive()
        archive.put_trace(TRACE, [span()])
        session = evidence.EvidenceSession(archive, "http://prom", "http://zip",
            runtime_targets=["observer:9109"], resource_files=self.resources())
        with patch.object(performance, "query", return_value=[]), self.assertRaises(ValueError):
            session.start()
        self.assertFalse(session.finish(True))
        self.assertEqual(archive.meta["state"], "failed")
        self.assertTrue(archive.meta["observationGaps"])

    def test_reuses_existing_business_window_validation_and_does_not_certify_200_tps(self):
        from test_performance import metadata
        metadata = metadata(); metadata["operations"] = ["payment.confirmed"]
        archive = self.archive()
        session = evidence.EvidenceSession(archive, "http://prom", "http://zip",
            runtime_targets=["observer:9109"], resource_files=self.resources(), metadata=metadata)
        def query(base, expression, at, **kwargs):
            if expression.startswith("business:") and "source" not in expression:
                value = "0" if "duration_missing" in expression else "10"
                return [{"metric": {"operation": op}, "value": [at, value]} for op in performance.OPERATIONS]
            if expression.startswith("histogram_quantile"):
                return ([{"metric": {"operation": op}, "value": [at, ".5"]} for op in performance.OPERATIONS]
                        if "business:" in expression else [{"metric": {}, "value": [at, ".5"]}])
            if expression.startswith("sum("):
                return [{"metric": {}, "value": [at, "10"]}]
            return self.query(base, expression, at, **kwargs)
        with patch.object(performance, "query", side_effect=query):
            session.start()
            row = json.loads(evidence.unpack((archive.path / "sample-00000000.json.gz").read_bytes()))
            result = row["businessSnapshot"]["business"]["payment.confirmed"]
            self.assertAlmostEqual(result["tps"], 10 / 900)
            self.assertIsNone(result["p99Seconds"])
            self.assertEqual(result["dbWindowEnd"] - result["dbWindowStart"], 900)
            self.assertFalse(session.finish(False))

    def test_first_http_scrape_waits_within_budget_and_never_becomes_false_success(self):
        from test_performance import metadata
        archive = self.archive()
        session = evidence.EvidenceSession(archive, "http://prom", "http://zip",
            runtime_targets=["observer:9109"], resource_files=self.resources(), metadata=metadata())
        with patch.object(session, "capture", side_effect=[performance.ObservationNotReady("first scrape"), None]), \
                patch.object(evidence.time, "sleep") as waiting:
            session.start()
            waiting.assert_called_once()
            self.assertTrue(session.started)
            self.assertIsNone(session.failure)
            self.assertFalse(session.finish(False))
        other = self.root / ("orders-" + uuid.uuid4().hex); other.mkdir()
        (other / "plan.json").write_text((self.run / "plan.json").read_text())
        archive = evidence.Archive(other, reserve=0)
        self.addCleanup(archive.close)
        session = evidence.EvidenceSession(archive, "http://prom", "http://zip",
            runtime_targets=["observer:9109"], resource_files=self.resources(), metadata=metadata())
        with patch.object(session, "capture", side_effect=performance.ObservationNotReady("no history")), \
                patch.object(evidence.time, "monotonic", side_effect=[100, 146]), self.assertRaises(performance.ObservationNotReady):
            session.start()
        self.assertFalse(session.finish(True))

    def test_critical_capacity_or_alert_preserves_sample_and_blocks_injection(self):
        for metric, changed in (("commerce_settle_container_memory_usage_ratio", ".99"),
                                ("commerce_settle_docker_host_memory_available_ratio", ".01"),
                                ("financial:store_value", ".75"),
                                ('ALERTS{alertstate="firing",severity="critical"}', "1")):
            with self.subTest(metric=metric):
                directory = self.root / ("orders-" + uuid.uuid4().hex)
                directory.mkdir()
                (directory / "plan.json").write_text((self.run / "plan.json").read_text())
                archive = evidence.Archive(directory, reserve=0)
                self.addCleanup(archive.close)
                session = evidence.EvidenceSession(archive, "http://prom", "http://zip",
                    runtime_targets=["observer:9109"], resource_files=self.resources())
                def query(base, expression, at, **kwargs):
                    result = self.query(base, expression, at, **kwargs)
                    if expression == metric:
                        if not result:
                            return [{"metric": {"alertname": "test"}, "value": [at, changed]}]
                        for row in result:
                            row["value"][1] = changed
                            if expression == "commerce_settle_container_memory_usage_ratio":
                                row["metric"]["basis"] = "limit"
                    return result
                with patch.object(performance, "query", side_effect=query), self.assertRaises(ValueError):
                    session.start()
                self.assertIsNone(session.thread.ident)
                row = json.loads(evidence.unpack((archive.path / "sample-00000000.json.gz").read_bytes()))
                self.assertTrue(row["rejectionReasons"])
                self.assertFalse(session.finish(True))

    def test_resource_read_accepts_newly_written_sample_but_rejects_future_and_wrong_hosts(self):
        paths = self.resources()
        self.assertEqual(evidence.resource_record(paths[0], self.run_id, time.time() - 1)["hostRole"], "app")
        row = json.loads(paths[0].read_text())
        row["ts"] = row["endedAt"] = datetime.fromtimestamp(time.time() + 30, timezone.utc).isoformat()
        paths[0].write_text(json.dumps(row) + "\n")
        with self.assertRaises(ValueError):
            evidence.resource_record(paths[0], self.run_id, time.time())
        paths = self.resources()
        row = json.loads(paths[1].read_text()); row["hostId"] = "app-host"
        paths[1].write_text(json.dumps(row) + "\n")
        session = evidence.EvidenceSession(self.archive(), "http://prom", "http://zip",
            runtime_targets=["observer:9109"], resource_files=paths)
        with self.assertRaises(ValueError):
            session.start()

    def test_actual_http_trace_capture_and_private_manifest_readback(self):
        payload = [span()]
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != "/api/v2/trace/" + TRACE:
                    self.send_error(404)
                    return
                raw = json.dumps(payload).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)
            def log_message(self, *_):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            base = "http://127.0.0.1:" + str(server.server_port)
            archive = self.archive()
            archive.put_trace(TRACE, evidence.get_trace(base, TRACE))
            self.assertEqual(evidence.get_trace(base, SECOND), [])
            self.assertTrue(archive.finish(True))
            archive.close()
            reopened = evidence.Archive(self.run, reading=True)
            self.addCleanup(reopened.close)
            self.assertEqual(reopened.meta["runId"], self.run_id)
            raw = (reopened.path / ("trace-" + TRACE + ".json.gz")).read_bytes()
            self.assertNotIn("secret", evidence.unpack(raw).decode())
            self.assertEqual((reopened.path / "owner.json").stat().st_mode & 0o777, 0o600)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)

    def test_response_byte_and_trickle_read_budgets_are_bounded(self):
        with self.assertRaises(ValueError):
            performance.bounded_body(io.BytesIO(b"12345"), 4, 1)
        clock = iter([0, 0, 2])
        response = Mock(read1=Mock(return_value=b"x"))
        with patch.object(performance.time, "monotonic", side_effect=lambda: next(clock)), self.assertRaises(TimeoutError):
            performance.bounded_body(response, 100, 1)


if __name__ == "__main__":
    unittest.main()
