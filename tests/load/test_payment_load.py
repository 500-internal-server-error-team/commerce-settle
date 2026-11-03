"""부하 도구의 대조 쿼리와 운영 재처리 계약을 외부 서비스 없이 검증한다."""

import argparse
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import unittest
import tempfile
from unittest.mock import MagicMock, mock_open, patch


SOURCE = Path(os.environ.get("PAYMENT_LOAD_SOURCE", Path(__file__).with_name("payment_load.py")))
SPEC = importlib.util.spec_from_file_location("payment_load", SOURCE)
load = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(load)


class BoolAnd:
    def __init__(self):
        self.value = True

    def step(self, value):
        self.value = self.value and bool(value)

    def finalize(self):
        return "t" if self.value else "f"


class ReconciliationFixture(unittest.TestCase):
    def setUp(self):
        # 동일 JOIN/FILTER 쿼리를 SQLite 메모리 DB에서 실행한다. PostgreSQL 통합 시험은 아니다.
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.db.create_aggregate("bool_and", 1, BoolAnd)
        self.db.executescript("""
            ATTACH DATABASE ':memory:' AS payment;
            ATTACH DATABASE ':memory:' AS finance_mock;
            CREATE TABLE payment.payments (order_id TEXT, id TEXT, current_cycle_id TEXT,
                buyer_id TEXT, status TEXT, total_amount INT, mileage_amount INT, external_amount INT);
            CREATE TABLE payment.payment_attempts (payment_id TEXT, provider_id TEXT,
                business_key TEXT, amount INT, status TEXT);
            CREATE TABLE payment.mileage_wallets (buyer_id TEXT, balance INT, reserved_amount INT);
            CREATE TABLE payment.mileage_entries (payment_id TEXT, kind TEXT);
            CREATE TABLE payment.outbox (aggregate_id TEXT, event_type TEXT, status TEXT);
            CREATE TABLE payment.inbox (status TEXT);
            CREATE TABLE payment.payment_jobs (status TEXT);
            CREATE TABLE payment.inbox_conflicts (id TEXT);
            CREATE TABLE finance_mock.mock_transactions (provider_id TEXT, business_key TEXT,
                amount INT, status TEXT, account_id TEXT, operation TEXT);
            CREATE TABLE finance_mock.mock_accounts (id TEXT, balance INT, reserved_amount INT,
                opening_balance INT, provider_id TEXT, account_ref TEXT);
        """)

    def payment(self, mileage=0, status="CONFIRMED"):
        self.expected = {"order": ("mixed" if mileage else "card", 10000, mileage)}
        self.db.execute("INSERT INTO payment.payments VALUES (?,?,?,?,?,?,?,?)",
                        ("order", "payment", "cycle", "buyer", status, 10000, mileage, 10000-mileage))
        self.db.execute("INSERT INTO payment.payment_attempts VALUES (?,?,?,?,?)",
                        ("payment", "PG_A", "cycle", 10000-mileage,
                         "APPROVED" if status == "CONFIRMED" else "DECLINED"))
        self.db.execute("INSERT INTO finance_mock.mock_transactions VALUES (?,?,?,?,?,?)",
                        ("PG_A", "cycle", 10000-mileage,
                         "SUCCEEDED" if status == "CONFIRMED" else "DECLINED", None, "APPROVE"))
        if mileage:
            balance = load.WALLET_BALANCE - (mileage if status == "CONFIRMED" else 0)
            self.db.execute("INSERT INTO payment.mileage_wallets VALUES (?,?,0)", ("buyer", balance))
            for kind in ("RESERVE", "DEBIT" if status == "CONFIRMED" else "RELEASE"):
                self.db.execute("INSERT INTO payment.mileage_entries VALUES ('payment',?)", (kind,))
        for event in ("PaymentAccepted", "PaymentConfirmed" if status == "CONFIRMED" else "PaymentDeclined"):
            self.db.execute("INSERT INTO payment.outbox VALUES ('payment',?,'PUBLISHED')", (event,))
        self.db.execute("INSERT INTO payment.inbox VALUES ('APPLIED')")
        self.db.execute("INSERT INTO payment.payment_jobs VALUES ('DONE')")

    def query(self, database, sql):
        sql = sql.replace("account_id::text", "CAST(account_id AS TEXT)")
        return [["" if v is None else str(v) for v in row] for row in self.db.execute(sql)]

    def reconcile(self):
        output = MagicMock()
        with patch.object(load, "psql", self.query), patch.object(load, "ledger", return_value=self.expected), \
                patch.object(load, "WORK", output), contextlib.redirect_stdout(io.StringIO()):
            try:
                load.cmd_reconcile(argparse.Namespace(runs="fixture", output="report.json"))
            except SystemExit:
                pass
        return json.loads(output.__truediv__.return_value.write_text.call_args.args[0])


class ReconciliationRegressionTest(ReconciliationFixture):
    def test_detects_missing_entire_mileage_journal(self):
        self.payment(mileage=1000)
        self.db.execute("DELETE FROM payment.mileage_entries")
        self.assertIn("마일리지 원장 불일치", self.reconcile()["problems"])

    def test_detects_declined_payment_without_mock_transaction(self):
        self.payment(status="DECLINED")
        self.db.execute("DELETE FROM finance_mock.mock_transactions")
        self.assertIn("대역 거래 누락", self.reconcile()["problems"])


class ReconciliationNormalTest(ReconciliationFixture):
    def test_accepts_card_payment_without_mileage_journal(self):
        self.payment()
        self.assertEqual({}, self.reconcile()["problems"])

    def test_accepts_complete_mixed_payment_journal(self):
        self.payment(mileage=1000)
        self.assertEqual({}, self.reconcile()["problems"])

    def test_accepts_declined_payment_with_released_mileage(self):
        self.payment(mileage=1000, status="DECLINED")
        self.assertEqual({}, self.reconcile()["problems"])

    def test_detects_missing_one_mileage_entry(self):
        self.payment(mileage=1000)
        self.db.execute("DELETE FROM payment.mileage_entries WHERE kind='DEBIT'")
        self.assertIn("마일리지 원장 불일치", self.reconcile()["problems"])


class ReplayRegressionTest(unittest.TestCase):
    def test_replays_same_job_again_after_new_exhaustion(self):
        accepted = {}

        def request(*args):
            key = next(v for v in args if v.startswith("Idempotency-Key:"))
            body = args[args.index("-d")+1]
            if key in accepted and accepted[key] != body:
                return '{}\n409'
            accepted[key] = body
            return '{}\n202'

        with patch.object(load, "psql", side_effect=[[["job", "10"]], [["job", "22"]]]), \
                patch.object(load, "operator_credential", return_value="test"), \
                patch.object(load, "compose", side_effect=request), contextlib.redirect_stdout(io.StringIO()):
            load.cmd_replay(argparse.Namespace(reason="LOAD_TEST_RECOVERY"))
            load.cmd_replay(argparse.Namespace(reason="LOAD_TEST_RECOVERY"))
        self.assertEqual(2, len(accepted))

    def test_http_failure_exits_without_reporting_success(self):
        for status in ("409", "500"):
            with self.subTest(status=status), patch.object(load, "psql", return_value=[["job", "10"]]), \
                    patch.object(load, "operator_credential", return_value="test"), \
                    patch.object(load, "compose", return_value='{}\n'+status), \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                with self.assertRaises(SystemExit):
                    load.cmd_replay(argparse.Namespace(reason="LOAD_TEST_RECOVERY"))
                self.assertNotIn("replayed 1 jobs", output.getvalue())


class ReplayNormalTest(unittest.TestCase):
    def test_accepted_replay_reports_success(self):
        with patch.object(load, "psql", return_value=[["job", "10"]]), \
                patch.object(load, "operator_credential", return_value="test"), \
                patch.object(load, "compose", return_value='{}\n202'), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            load.cmd_replay(argparse.Namespace(reason="LOAD_TEST_RECOVERY"))
        self.assertIn("replayed 1 jobs", output.getvalue())


class RestartRegressionTest(unittest.TestCase):
    def ramp(self, fail_on=None, skip_restart=False, stages="100:60"):
        class InlineThread:
            def __init__(self, target, daemon):
                self.target = target

            def start(self):
                if skip_restart and self.target.__name__ == "kill_worker":
                    return
                try:
                    self.target()
                except BaseException:
                    # 실제 보조 스레드처럼 예외를 호출자에게 직접 전달하지 않는다.
                    pass

            def is_alive(self):
                return False

            def join(self):
                pass

        def compose(*args, **kwargs):
            if args[0] == fail_on:
                raise SystemExit(f"{fail_on} failed")
            return ""

        sample = {k: 0 for k in ("received", "confirmed", "declined", "unfinished", "waiting",
                                  "kafkaLag", "outboxPending", "jobsReview", "inboxReview")}
        sample["at"] = "2026-11-03T00:00:00Z"
        work = MagicMock()
        args = argparse.Namespace(stages=stages, run="restart", mix="card", offset=0,
                                  interval=1, drain=1, kill_after=25, scenario="normal")
        with patch("threading.Thread", InlineThread), patch("builtins.open", mock_open()), \
                patch.object(load, "time") as clock, patch.object(load, "WORK", work), \
                patch.object(load, "produce"), patch.object(load, "remember_run"), \
                patch.object(load, "snapshot", return_value=sample), \
                patch.object(load, "compose", side_effect=compose), \
                patch.object(load, "compose_env", return_value={}), \
                contextlib.redirect_stdout(io.StringIO()):
            clock.monotonic.side_effect = range(100)
            load.cmd_ramp(args)
        return json.loads(work.__truediv__.return_value.__truediv__.return_value.write_text.call_args.args[0])

    def test_kill_failure_is_propagated(self):
        with self.assertRaisesRegex(SystemExit, "kill failed"):
            self.ramp(fail_on="kill")

    def test_restart_failure_is_propagated(self):
        with self.assertRaisesRegex(SystemExit, "up failed"):
            self.ramp(fail_on="up")

    def test_missing_restart_completion_is_rejected(self):
        with self.assertRaisesRegex(SystemExit, "restart did not complete"):
            self.ramp(skip_restart=True)

    def test_rejects_fault_after_injection_ends(self):
        with self.assertRaisesRegex(SystemExit, "earlier than the end"):
            self.ramp(stages="100:10")

    def test_successful_restart_is_recorded(self):
        fault = self.ramp()["fault"]
        self.assertIn("killedAt", fault)
        self.assertIn("restartedAt", fault)


class StopInjectionTest(unittest.TestCase):
    def test_stop_during_delay_does_not_inject_the_next_request(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stop = root / 'stop'
            process = MagicMock()
            process.returncode = 0
            marks = []
            with patch.object(load.subprocess, 'Popen', return_value=process), \
                    patch.object(load, 'compose_env', return_value={}), \
                    patch.object(load.time, 'monotonic', return_value=0), \
                    patch.object(load.time, 'sleep', side_effect=lambda _: stop.write_text('stop')):
                end = load.produce([(1, 3)], ['card'], root, 7, marks, stop)
            self.assertEqual(end, 8)
            self.assertEqual(marks[0]['sent'], 1)
            self.assertEqual(process.stdin.write.call_count, 1)
            process.stdin.close.assert_called_once()
            process.wait.assert_called_once()

    def test_stop_before_injection_closes_the_producer_and_records_zero_sent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stop = root / 'stop'
            stop.write_text('stop')
            process = MagicMock()
            process.returncode = 0
            marks = []
            with patch.object(load.subprocess, 'Popen', return_value=process), patch.object(load, 'compose_env', return_value={}):
                end = load.produce([(100, 30)], ['card'], root, 7, marks, stop)
            self.assertEqual(end, 7)
            self.assertEqual(marks[0]['sent'], 0)
            self.assertTrue(marks[0]['stopped'])
            process.stdin.write.assert_not_called()
            process.stdin.close.assert_called_once()
            process.wait.assert_called_once()


if __name__ == "__main__":
    unittest.main()
