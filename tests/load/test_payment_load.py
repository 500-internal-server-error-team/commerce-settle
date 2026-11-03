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
from unittest.mock import MagicMock, patch


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


if __name__ == "__main__":
    unittest.main()
