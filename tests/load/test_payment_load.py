"""부하 도구의 대조 쿼리와 운영 재처리 계약을 외부 서비스 없이 검증한다."""

import argparse
import csv
import contextlib
from datetime import datetime, timezone
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import unittest
import tempfile
from unittest.mock import MagicMock, mock_open, patch


SOURCE = Path(os.environ.get("PAYMENT_LOAD_SOURCE", Path(__file__).with_name("payment_load.py")))
SPEC = importlib.util.spec_from_file_location("payment_load", SOURCE)
load = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(load)


def completed_injection(stages, mix, directory, offset, marks, *_):
    marks.append({"stage": 0, "rate": 1, "seconds": 1, "sent": 0, "elapsed": 0,
                  "start": "2026-11-03T00:00:00Z", "end": "2026-11-03T00:00:00Z",
                  "endedMonotonic": load.time.monotonic()})


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
                opening_balance INT, provider_id TEXT, account_ref TEXT, purpose TEXT);
        """)

    def payment(self, mileage=0, status="CONFIRMED", opening_balance=load.WALLET_BALANCE):
        self.expected = {"order": ("mixed" if mileage else "card", 10000, mileage)}
        self.other_ledger = {}
        self.db.execute("INSERT INTO payment.payments VALUES (?,?,?,?,?,?,?,?)",
                        ("order", "payment", "cycle", load.buyer(0), status, 10000, mileage, 10000-mileage))
        self.db.execute("INSERT INTO payment.payment_attempts VALUES (?,?,?,?,?)",
                        ("payment", "PG_A", "cycle", 10000-mileage,
                         "APPROVED" if status == "CONFIRMED" else "DECLINED"))
        self.db.execute("INSERT INTO finance_mock.mock_transactions VALUES (?,?,?,?,?,?)",
                        ("PG_A", "cycle", 10000-mileage,
                         "SUCCEEDED" if status == "CONFIRMED" else "DECLINED", None, "APPROVE"))
        self.db.execute("INSERT INTO finance_mock.mock_accounts VALUES (?,?,0,?,'EASY_PAY','load-wallet-0','WALLET')",
                        ("wallet", opening_balance, opening_balance))
        balance = opening_balance - (mileage if status == "CONFIRMED" else 0)
        self.db.execute("INSERT INTO payment.mileage_wallets VALUES (?,?,0)", (load.buyer(0), balance))
        if mileage:
            for kind in ("RESERVE", "DEBIT" if status == "CONFIRMED" else "RELEASE"):
                self.db.execute("INSERT INTO payment.mileage_entries VALUES ('payment',?)", (kind,))
        for event in ("PaymentAccepted", "PaymentConfirmed" if status == "CONFIRMED" else "PaymentDeclined"):
            self.db.execute("INSERT INTO payment.outbox VALUES ('payment',?,'PUBLISHED')", (event,))
        self.db.execute("INSERT INTO payment.inbox VALUES ('APPLIED')")
        self.db.execute("INSERT INTO payment.payment_jobs VALUES ('DONE')")

    def other_run(self, namespace="r2", mileage=1000, balance_error=0, order="order2"):
        """같은 스택에서 다른 실행 번호로 실행한 다른 실행의 결제·지갑. 그 실행만의 불일치를 balance_error로 만든다."""
        payment, cycle, buyer_id = f"{order}-payment", f"{order}-cycle", load.buyer(0, namespace)
        self.other_ledger[order] = ("mixed", 10000, mileage)
        self.stack_runs = ["fixture", "other"]
        self.db.execute("INSERT INTO payment.payments VALUES (?,?,?,?,'CONFIRMED',10000,?,?)",
                        (order, payment, cycle, buyer_id, mileage, 10000 - mileage))
        self.db.execute("INSERT INTO payment.payment_attempts VALUES (?,'PG_A',?,?,'APPROVED')",
                        (payment, cycle, 10000 - mileage))
        self.db.execute("INSERT INTO finance_mock.mock_transactions VALUES ('PG_A',?,?,'SUCCEEDED',NULL,'APPROVE')",
                        (cycle, 10000 - mileage))
        self.db.execute("INSERT INTO finance_mock.mock_accounts VALUES (?,?,0,?,'EASY_PAY',?,'WALLET')",
                        (f"{order}-wallet", load.WALLET_BALANCE, load.WALLET_BALANCE, load.wallet_ref(0, namespace)))
        self.db.execute("INSERT INTO payment.mileage_wallets VALUES (?,?,0)",
                        (buyer_id, load.WALLET_BALANCE - mileage + balance_error))
        for kind in ("RESERVE", "DEBIT"):
            self.db.execute("INSERT INTO payment.mileage_entries VALUES (?,?)", (payment, kind))
        for event in ("PaymentAccepted", "PaymentConfirmed"):
            self.db.execute("INSERT INTO payment.outbox VALUES (?,?,'PUBLISHED')", (payment, event))

    def query(self, database, sql):
        sql = sql.replace("account_id::text", "CAST(account_id AS TEXT)")
        return [["" if v is None else str(v) for v in row] for row in self.db.execute(sql)]

    def ledger(self, runs):
        rows = {}
        for run in runs:
            rows.update(self.expected if run == "fixture" else self.other_ledger)
        return rows

    def reconcile(self, runs="fixture"):
        output = MagicMock()
        stack = MagicMock()
        stack.exists.return_value = bool(getattr(self, "stack_runs", None))
        stack.read_text.return_value = "\n".join(getattr(self, "stack_runs", []))
        with patch.object(load, "psql", self.query), patch.object(load, "ledger", self.ledger), \
                patch.object(load, "STACK_RUNS", stack), \
                patch.object(load, "WORK", output), contextlib.redirect_stdout(io.StringIO()):
            try:
                load.cmd_reconcile(argparse.Namespace(runs=runs, output="report.json"))
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


class ReconciliationScopeTest(ReconciliationFixture):
    def test_ignores_payments_and_wallets_of_another_run_in_the_same_stack(self):
        self.payment(mileage=1000)
        self.other_run(namespace="r2")
        self.assertEqual({}, self.reconcile()["problems"])

    def test_does_not_count_problems_that_belong_to_another_run(self):
        self.payment()
        self.other_run(namespace="r2", balance_error=1)
        report = self.reconcile()
        self.assertEqual({}, report["problems"])
        self.assertEqual({"CONFIRMED": 1}, report["payments"])

    def test_accepts_retried_attempts_of_another_run(self):
        # 다른 실행 결제의 이전 거절 시도와 현재 승인 시도는 둘 다 그 결제에 연결된 대역 거래다.
        self.payment()
        self.other_run(namespace="r2")
        self.db.execute("INSERT INTO payment.payment_attempts VALUES "
                        "('order2-payment','PG_A','order2-old-cycle',9000,'DECLINED')")
        self.db.execute("INSERT INTO finance_mock.mock_transactions VALUES "
                        "('PG_A','order2-old-cycle',9000,'DECLINED',NULL,'APPROVE')")
        self.assertEqual({}, self.reconcile()["problems"])
        self.assertIn("승인 시도 중복", self.reconcile(runs="other")["problems"])

    def test_whole_stack_reconcile_detects_the_problem_of_another_run(self):
        # 실행 하나만 대조할 때 빼 둔 다른 실행의 불일치는 그 실행이나 스택 전체를 대조할 때 잡혀야 한다.
        self.payment()
        self.other_run(namespace="r2")
        self.db.execute("DELETE FROM payment.outbox WHERE aggregate_id = 'order2-payment'")
        self.assertEqual({}, self.reconcile()["problems"])
        self.assertIn("결과 이벤트 누락", self.reconcile(runs=None)["problems"])
        self.assertIn("결과 이벤트 누락", self.reconcile(runs="other")["problems"])

    def test_same_namespace_wallet_error_is_detected_from_either_run(self):
        # 같은 실행 번호의 두 실행은 지갑을 함께 쓰므로, 어느 실행을 대조해도 잔액 오류가 잡힌다.
        self.payment()
        self.other_run(namespace="", mileage=1000, balance_error=1)
        self.db.execute("DELETE FROM finance_mock.mock_accounts WHERE id = 'order2-wallet'")
        self.db.execute("DELETE FROM payment.mileage_wallets WHERE balance = ?", (load.WALLET_BALANCE,))
        self.assertIn("마일리지 잔액 불일치", self.reconcile()["problems"])

    def test_rejects_an_order_recorded_in_two_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for run in ("fixture", "other"):
                (root / run).mkdir()
                (root / run / "requests.csv").write_text("order,card,10000,0\n", encoding="utf-8")
            (root / "runs").write_text("fixture\nother\n", encoding="utf-8")
            with patch.object(load, "WORK", root), patch.object(load, "STACK_RUNS", root / "runs"), \
                    patch.object(load, "psql") as psql, self.assertRaisesRegex(SystemExit, "duplicate orderId"):
                load.cmd_reconcile(argparse.Namespace(runs="fixture", output="report.json"))
            psql.assert_not_called()

    def test_still_detects_a_payment_that_no_run_injected(self):
        self.payment()
        self.other_run(namespace="r2")
        self.other_ledger.clear()
        self.assertIn("원장에 없는 결제", self.reconcile()["problems"])

    def test_counts_mileage_of_another_run_with_the_same_namespace(self):
        # 같은 실행 번호의 두 실행은 같은 구매자 지갑을 쓰므로 잔액에는 두 실행의 차감이 함께 남는다.
        self.payment(mileage=1000)
        self.other_run(namespace="", mileage=500)
        self.db.execute("DELETE FROM finance_mock.mock_accounts WHERE id = 'order2-wallet'")
        self.db.execute("DELETE FROM payment.mileage_wallets WHERE balance = ?", (load.WALLET_BALANCE - 500,))
        self.assertIn("마일리지 잔액 불일치", self.reconcile()["problems"])
        self.db.execute("UPDATE payment.mileage_wallets SET balance = ?", (load.WALLET_BALANCE - 1500,))
        self.assertEqual({}, self.reconcile()["problems"])


class ReconciliationNormalTest(ReconciliationFixture):
    def test_accepts_card_payment_without_mileage_journal(self):
        self.payment()
        self.assertEqual({}, self.reconcile()["problems"])

    def test_accepts_complete_mixed_payment_journal(self):
        self.payment(mileage=1000)
        self.assertEqual({}, self.reconcile()["problems"])

    def test_accepts_previous_initial_balance_without_changing_the_wallet(self):
        self.payment(mileage=1000, opening_balance=100_000_000)
        self.assertEqual({}, self.reconcile()["problems"])
        self.assertEqual(99_999_000, self.db.execute("SELECT balance FROM payment.mileage_wallets").fetchone()[0])

    def test_detects_balance_difference_from_the_stored_initial_balance(self):
        self.payment(mileage=1000, opening_balance=100_000_000)
        self.db.execute("UPDATE payment.mileage_wallets SET balance=balance+1")
        self.assertIn("마일리지 잔액 불일치", self.reconcile()["problems"])

    def test_rejects_missing_initial_balance_without_using_the_current_default(self):
        self.payment(mileage=1000)
        self.db.execute("DELETE FROM finance_mock.mock_accounts")
        self.assertIn("마일리지 초기 잔액 누락", self.reconcile()["problems"])

    def test_does_not_use_payout_funds_as_the_mileage_initial_balance(self):
        self.payment(mileage=1000)
        self.db.execute("UPDATE finance_mock.mock_accounts SET purpose='PAYOUT'")
        self.assertIn("마일리지 초기 잔액 누락", self.reconcile()["problems"])

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
    def ramp(self, fail_on=None, skip_restart=False, stages="100:60", worker_env="", calls=None,
             scenario="normal"):
        class InlineThread:
            def __init__(self, target, daemon=False):
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
            if calls is not None:
                calls.append((args, kwargs))
            if args[0] == fail_on:
                raise SystemExit(f"{fail_on} failed")
            return worker_env if args[0] == "exec" else ""

        sample = {k: 0 for k in ("received", "confirmed", "declined", "unfinished", "waiting",
                                  "kafkaLag", "outboxPending", "jobsReview", "inboxReview")}
        sample["at"] = "2026-11-03T00:00:00Z"
        work = MagicMock()
        args = argparse.Namespace(stages=stages, run="restart", mix="card", offset=0,
                                  interval=1, drain=1, kill_after=25, scenario=scenario)
        with patch("threading.Thread", InlineThread), patch("threading.Event") as event, patch("builtins.open", mock_open()), \
                patch.object(load, "time") as clock, patch.object(load, "WORK", work), \
                patch.object(load, "produce", side_effect=completed_injection), patch.object(load, "latency", return_value={}), patch.object(load, "remember_run"), \
                patch.object(load, "snapshot", return_value=sample), \
                patch.object(load, "compose", side_effect=compose), \
                patch.object(load, "compose_env", return_value={}), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            event.return_value.wait.return_value = False
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
    def test_failure_before_injection_leaves_an_empty_reconcilable_ledger(self):
        for failure in ("snapshot", "producer"):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                args = argparse.Namespace(stages="1:1", rate=1, seconds=1, run="failed", mix="card", offset=0,
                                          interval=.01, drain=.01, kill_after=0, scenario=None, stop_file=None)
                with patch.object(load, "WORK", root), patch.object(load, "STACK_RUNS", root / "runs"):
                    with patch.object(load, "snapshot", side_effect=SystemExit("snapshot unavailable")), \
                            patch.object(load.subprocess, "Popen", side_effect=OSError("producer unavailable")) as producer, \
                            self.assertRaises((SystemExit, OSError)):
                        (load.cmd_ramp if failure == "snapshot" else load.cmd_inject)(args)
                    self.assertEqual(producer.call_count, 0 if failure == "snapshot" else 1)
                    def psql(database, sql):
                        return [("0", "0", "0")] if "SELECT (SELECT count(*) FROM payment.inbox" in sql else []
                    with patch.object(load, "psql", side_effect=psql), contextlib.redirect_stdout(io.StringIO()):
                        load.cmd_reconcile(argparse.Namespace(runs=None, output="reconcile.json"))
                    report = json.loads((root / "reconcile.json").read_text())
                    self.assertEqual(report["requests"], 0)
                    self.assertEqual(report["problems"], {})

    def test_registration_preserves_existing_ledger_and_rejects_unwritable_ledger(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "existing"
            run.mkdir()
            ledger = run / "requests.csv"
            ledger.write_text("original,card,10,0,0,now\n")
            with patch.object(load, "WORK", root), patch.object(load, "STACK_RUNS", root / "runs"):
                load.remember_run("existing")
                self.assertEqual(ledger.read_text(), "original,card,10,0,0,now\n")
                blocked = root / "blocked/requests.csv"
                blocked.mkdir(parents=True)
                with self.assertRaises(IsADirectoryError):
                    load.remember_run("blocked")
                self.assertEqual((root / "runs").read_text(), "existing\n")

    def test_snapshot_failure_stops_the_producer_and_flushes_the_ledger(self):
        import threading
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sent = threading.Event()
            process = MagicMock()
            process.returncode = 0
            process.stdin.write.side_effect = lambda _: sent.set()
            sample = {k: 0 for k in ("received", "confirmed", "declined", "unfinished", "waiting",
                                      "kafkaLag", "outboxPending", "jobsReview", "inboxReview")}
            sample['at'] = '2026-11-04T00:00:00Z'
            calls = 0
            def snapshot():
                nonlocal calls
                calls += 1
                if calls > 1:
                    self.assertTrue(sent.wait(2))
                    raise SystemExit('snapshot unavailable')
                return sample
            args = argparse.Namespace(stages='10:30', run='failure', mix='card', offset=0,
                                      interval=.01, drain=1, kill_after=25, scenario=None, stop_file=None)
            with patch.object(load, 'WORK', root), patch.object(load, 'STACK_RUNS', root / 'runs'), \
                    patch.object(load.subprocess, 'Popen', return_value=process), \
                    patch.object(load, 'compose_env', return_value={}), \
                    patch.object(load, 'snapshot', side_effect=snapshot), \
                    patch.object(load, 'compose') as compose, \
                    self.assertRaisesRegex(SystemExit, 'snapshot unavailable'):
                load.cmd_ramp(args)
            self.assertGreater(process.stdin.write.call_count, 0)
            with (root / 'failure/requests.csv').open() as ledger:
                self.assertEqual(len(list(csv.reader(ledger))), process.stdin.write.call_count)
            process.stdin.close.assert_called_once()
            process.wait.assert_called_once_with(timeout=10)
            compose.assert_not_called()

    def test_restart_finishing_after_drain_deadline_still_fails(self):
        import threading
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            entered = threading.Event()
            release = threading.Event()
            sample = {k: 0 for k in ("received", "confirmed", "declined", "unfinished", "waiting",
                                      "kafkaLag", "outboxPending", "jobsReview", "inboxReview")}
            sample.update(at='2026-11-04T00:00:00Z', unfinished=1)
            calls = 0
            def snapshot():
                nonlocal calls
                calls += 1
                if calls > 1:
                    self.assertTrue(entered.wait(2))
                return sample
            def compose(*args, **kwargs):
                if args[0] == 'up':
                    entered.set()
                    self.assertTrue(release.wait(2))
                return ''
            timer = threading.Timer(.3, release.set)
            args = argparse.Namespace(stages='1:1', run='deadline', mix='card', offset=0,
                                      interval=.01, drain=.01, kill_after=.01, scenario=None, stop_file=None)
            timer.start()
            try:
                with patch.object(load, 'WORK', root), patch.object(load, 'STACK_RUNS', root / 'runs'), \
                        patch.object(load, 'produce', side_effect=completed_injection), patch.object(load, 'compose_env', return_value={}), \
                        patch.object(load, 'snapshot', side_effect=snapshot), \
                        patch.object(load, 'compose', side_effect=compose), \
                        contextlib.redirect_stdout(io.StringIO()), \
                        self.assertRaisesRegex(SystemExit, 'before drain timeout'):
                    load.cmd_ramp(args)
                self.assertFalse((root / 'deadline/summary.json').exists())
            finally:
                release.set()
                timer.join()

    def test_producer_error_reported_during_join_cannot_create_success_summary(self):
        class DeferredThread:
            def __init__(self, target):
                self.target = target
            def start(self):
                pass
            def is_alive(self):
                return False
            def join(self):
                self.target()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sample = {k: 0 for k in ("received", "confirmed", "declined", "unfinished", "waiting",
                                      "kafkaLag", "outboxPending", "jobsReview", "inboxReview")}
            sample['at'] = '2026-11-04T00:00:00Z'
            args = argparse.Namespace(stages='1:1', run='late-failure', mix='card', offset=0,
                                      interval=0, drain=1, kill_after=0, scenario=None, stop_file=None)
            with patch('threading.Thread', DeferredThread), patch.object(load, 'WORK', root), \
                    patch.object(load, 'STACK_RUNS', root / 'runs'), \
                    patch.object(load, 'produce', side_effect=SystemExit('late producer failure')), \
                    patch.object(load, 'snapshot', return_value=sample), \
                    contextlib.redirect_stdout(io.StringIO()), \
                    self.assertRaisesRegex(SystemExit, 'late producer failure'):
                load.cmd_ramp(args)
            self.assertFalse((root / 'late-failure/summary.json').exists())

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


class WorkerSettingsTest(unittest.TestCase):
    def test_records_only_processing_settings_from_the_running_worker(self):
        env = "DB_PASSWORD=secret\nPAYMENT_REQUEST_CONCURRENCY=6\nPAYMENT_DB_POOL_SIZE=20\n"
        with patch.object(load, "compose", return_value=env) as compose:
            settings = load.worker_settings()
        compose.assert_called_once_with("exec", "-T", "payment-worker", "env", check=False)
        self.assertEqual(settings, {"PAYMENT_REQUEST_CONCURRENCY": "6",
                                    "PAYMENT_APPROVAL_CONCURRENCY": None,
                                    "PAYMENT_APPROVAL_RETRY_CONCURRENCY": None,
                                    "PAYMENT_SCENARIO_VERSION": None,
                                    "PAYMENT_DB_POOL_SIZE": "20"})

    def test_ramp_summary_contains_worker_settings(self):
        settings = {"PAYMENT_REQUEST_CONCURRENCY": "6"}
        with patch.object(load, "worker_settings", return_value=settings):
            summary = RestartRegressionTest().ramp()
        self.assertEqual(summary["workerSettings"], settings)

    def test_restart_keeps_custom_settings_of_the_running_worker(self):
        # enable 때만 9·6·30을 지정하고 ramp 셸에는 변수가 없는 상황(compose_env는 {}).
        worker_env = ("DB_PASSWORD=secret\nPAYMENT_REQUEST_CONCURRENCY=9\n"
                      "PAYMENT_APPROVAL_CONCURRENCY=6\nPAYMENT_APPROVAL_RETRY_CONCURRENCY=2\nPAYMENT_DB_POOL_SIZE=30\nPAYMENT_SCENARIO_VERSION=fixture-pg-planned-v1\n")
        calls = []
        summary = RestartRegressionTest().ramp(worker_env=worker_env, calls=calls, scenario=None)
        names = [args[0] for args, _ in calls]
        self.assertLess(names.index("exec"), names.index("kill"))
        up_env = next(kwargs["env"] for args, kwargs in calls if args[0] == "up")
        self.assertEqual(up_env["LOAD_PAYMENT_REQUEST_CONCURRENCY"], "9")
        self.assertEqual(up_env["LOAD_PAYMENT_APPROVAL_CONCURRENCY"], "6")
        self.assertEqual(up_env["LOAD_PAYMENT_APPROVAL_RETRY_CONCURRENCY"], "2")
        self.assertEqual(up_env["LOAD_PG_SCENARIO"], "fixture-pg-planned-v1")
        self.assertEqual(up_env["LOAD_PAYMENT_DB_POOL_SIZE"], "30")
        self.assertNotIn("LOAD_DB_PASSWORD", up_env)
        self.assertEqual(summary["fault"]["workerSettingsBeforeKill"], summary["workerSettings"])

    def test_summary_is_still_written_when_worker_cannot_be_read(self):
        # Worker가 죽으면 check=False인 exec는 실패해도 예외 없이 빈 출력을 돌려준다.
        summary = RestartRegressionTest().ramp()
        self.assertIsNone(summary["workerSettings"])
        self.assertIn("final", summary)


class InjectedAggregationTest(unittest.TestCase):
    """#254: Worker 중단 중 Kafka에서 기다리거나 drain에 확정된 요청과 drain의 최대 lag가 요약에서 빠지지 않는다."""
    START = 1_793_664_000.0  # 2026-11-03T00:00:00Z

    def iso(self, offset):
        return datetime.fromtimestamp(self.START + offset, timezone.utc).isoformat()

    def summary(self, ledger, outcomes, lags):
        snapshots = iter([{**dict.fromkeys(("received", "confirmed", "declined", "unfinished", "waiting",
                                            "outboxPending", "jobsReview", "inboxReview"), 0),
                           "at": "2026-11-03T00:00:00Z", "kafkaLag": lag} for lag in lags])
        calls = []

        def snapshot():
            calls.append(1)
            return next(snapshots)

        class StageThread:
            # 주입 결과를 먼저 기록한 뒤 종료를 알리는 실제 Thread 수명주기를 따른다.
            def __init__(self, target):
                self.target = target
                self.done = False

            def start(self):
                pass

            def is_alive(self):
                if len(calls) >= 2 and not self.done:
                    self.target()
                    self.done = True
                return not self.done

            def join(self):
                if not self.done:
                    self.target()
                    self.done = True

        def produce(stages, mix, directory, offset, marks, *_):
            with open(directory / "requests.csv", "a", newline="", encoding="utf-8") as out:
                for order, published in ledger:
                    csv.writer(out).writerow([order, "card", 10000, 0, 0, self.iso(published)])
            marks.append({"stage": 0, "rate": 100, "seconds": 60, "start": self.iso(0), "end": self.iso(60),
                          "sent": len(ledger), "stopped": False, "elapsed": 60, "maxBehindSeconds": 0,
                          "endedMonotonic": load.time.monotonic()})

        def psql(database, sql):
            if "percentile_cont" in sql:
                return [["1", "500.0", "500.0", "500.0", "500.0"]]
            return [[order, *("" if v is None else str(self.START + v) if isinstance(v, float) else v
                              for v in outcome)] for order, outcome in outcomes.items()]

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = argparse.Namespace(stages="100:60", run="restart", mix="card", offset=0, interval=0,
                                      drain=60, kill_after=0, scenario=None, stop_file=None)
            with patch("threading.Thread", StageThread), patch.object(load, "WORK", root), \
                    patch.object(load, "STACK_RUNS", root / "runs"), patch.object(load, "produce", produce), \
                    patch.object(load, "snapshot", side_effect=snapshot), patch.object(load, "psql", psql), \
                    patch.object(load, "worker_settings", return_value=None), \
                    contextlib.redirect_stdout(io.StringIO()):
                (root / "restart").mkdir()
                (root / "restart/requests.csv").write_text(f"old,card,10000,0,0,{self.iso(-100)}\n")
                load.cmd_ramp(args)
            return json.loads((root / "restart/summary.json").read_text(encoding="utf-8"))

    def test_counts_kafka_waiting_and_drain_confirmations_in_the_injected_stage(self):
        ledger = [("in-window", 1.0), ("kafka-wait", 2.0), ("drain", 3.0), ("not-received", 4.0)]
        outcomes = {"in-window": (1.1, "CONFIRMED", 1.6), "kafka-wait": (70.0, "CONFIRMED", 71.0),
                    "drain": (3.1, "CONFIRMED", 90.0), "old": (-99.0, "CONFIRMED", -98.0)}

        summary = self.summary(ledger, outcomes, lags=[0, 2690, 3489, 0])

        injected = summary["stages"][0]["injected"]
        self.assertEqual((4, 3, 3, 1, False), (injected["injected"], injected["received"], injected["confirmed"],
                                               injected["unresolved"], injected["complete"]))
        self.assertEqual({"count": 3, "p50Ms": 69000.0, "p95Ms": 85200.0, "p99Ms": None, "maxMs": 87000.0},
                         injected["publishToConfirmed"])
        self.assertEqual(68000.0, injected["publishToReceipt"]["maxMs"])
        self.assertEqual(86900.0, injected["receiptToConfirmed"]["maxMs"])
        self.assertEqual(injected, summary["injected"])
        self.assertEqual(1, summary["stages"][0]["latency"]["count"])

    def test_peak_kafka_lag_includes_drain_samples(self):
        summary = self.summary([("a", 1.0)], {"a": (1.1, "CONFIRMED", 1.6)}, lags=[0, 2690, 3489, 0])

        self.assertEqual(2690, summary["stages"][0]["maxKafkaLag"])
        self.assertEqual(3489, summary["peaks"]["kafkaLag"]["value"])
        self.assertEqual("drain", summary["peaks"]["kafkaLag"]["stage"])
        self.assertEqual(4, summary["aggregation"]["version"])

    def test_reobserves_when_producer_finishes_during_an_empty_snapshot(self):
        summary = self.summary([("late", 1.0)], {"late": (1.1, "CONFIRMED", 1.6)}, lags=[0, 0, 8, 0])
        self.assertEqual(8, summary["peaks"]["kafkaLag"]["value"])
        self.assertEqual("drain", summary["peaks"]["kafkaLag"]["stage"])
        self.assertTrue(summary["drainComplete"])

    def test_unreadable_consumer_group_lag_is_not_recorded_as_zero(self):
        rebalancing = "\nConsumer group 'payment.v1' is rebalancing.\n"
        row = [["2026-11-03 00:00:00+00", *["0"] * 8, str(self.START)]]
        with patch.object(load, "kafka", return_value=rebalancing), patch.object(load, "psql", return_value=row):
            sample = load.snapshot()

        self.assertIsNone(sample["kafkaLag"])


class NamespaceTest(unittest.TestCase):
    def test_empty_namespace_keeps_existing_identifiers(self):
        self.assertEqual(load.buyer(0, ""), "00000000-0000-4000-8000-100000000000")
        self.assertEqual(load.buyer(199, ""), "00000000-0000-4000-8000-1000000000c7")
        self.assertEqual(load.product_id(""), load.PRODUCT)
        self.assertEqual(load.wallet_ref(3, ""), "load-wallet-3")
        self.assertEqual(load.wallet_prefix(""), "load-wallet-")

    def test_named_namespaces_do_not_share_buyers_product_or_wallets(self):
        a, b = "r1", "r2"
        buyers_a = {load.buyer(n, a) for n in range(load.BUYER_POOL)}
        buyers_b = {load.buyer(n, b) for n in range(load.BUYER_POOL)}
        default = {load.buyer(n, "") for n in range(load.BUYER_POOL)}
        self.assertEqual(len(buyers_a), load.BUYER_POOL)
        self.assertFalse(buyers_a & buyers_b)
        self.assertFalse(buyers_a & default)
        self.assertNotEqual(load.product_id(a), load.product_id(b))
        self.assertNotIn(load.product_id(a), buyers_a)
        self.assertEqual(load.wallet_ref(5, a), "load-wallet-r1-5")
        self.assertTrue(load.wallet_ref(5, a).startswith(load.wallet_prefix(a)))

    def test_buyer_sql_uses_the_same_base_as_python(self):
        for namespace in ("", "r7"):
            base = load.namespace_base(namespace)
            self.assertIn(f"to_hex({base} + n)", load.buyer_sql("n", namespace))
            self.assertEqual(load.buyer(0, namespace)[-12:], f"{base:012x}")

    def test_rejects_invalid_namespace_at_import(self):
        env = dict(os.environ, LOAD_NAMESPACE="Bad_Name")
        with patch.dict(os.environ, env, clear=False):
            spec = importlib.util.spec_from_file_location("payment_load_bad", SOURCE)
            module = importlib.util.module_from_spec(spec)
            with self.assertRaises(SystemExit):
                spec.loader.exec_module(module)


class RemoteRoutingTest(unittest.TestCase):
    REMOTE_SETTINGS = dict(REMOTE=True, APP_SSH="ubuntu@13.125.2.200", INFRA_SSH="ubuntu@10.0.1.20",
                           SSH_JUMP="ubuntu@13.125.2.200", SSH_KEY="key.pem", REMOTE_DOCKER="docker")

    def test_local_mode_keeps_local_compose_paths(self):
        with patch.multiple(load, REMOTE=False):
            argv = load.command_for(["exec", "-T", "postgres", "psql"])
        self.assertEqual(argv[:4], ["docker", "compose", "-p", load.PROJECT])
        self.assertIn(str(load.ROOT / "compose.yml"), argv)

    def test_services_map_to_their_hosts(self):
        self.assertEqual(load.host_for(["payment-worker"]), "app")
        self.assertEqual(load.host_for(["payment-api"]), "app")
        self.assertEqual(load.host_for(["kafka"]), "infra")
        self.assertEqual(load.host_for(["postgres"]), "infra")

    def test_only_exec_runs_in_remote_mode(self):
        with patch.multiple(load, **self.REMOTE_SETTINGS):
            for args in (["up", "-d", "--build"], ["up", "-d", "--no-deps", "payment-worker"],
                         ["kill", "payment-worker"], ["exec", "-T", "unknown", "env"]):
                with self.subTest(args=args), self.assertRaises(SystemExit):
                    load.command_for(args)

    def test_infra_command_finds_ecs_container_through_jump_host(self):
        with patch.multiple(load, **self.REMOTE_SETTINGS):
            argv = load.command_for(["exec", "-T", "postgres", "psql", "-c", "SELECT 'a b'"])
        self.assertEqual(argv[0], "ssh")
        self.assertEqual(argv[argv.index("-J") + 1], "ubuntu@13.125.2.200")
        self.assertEqual(argv[argv.index("-i") + 1], "key.pem")
        self.assertEqual(argv[-2], "ubuntu@10.0.1.20")
        remote = argv[-1]
        self.assertIn("--filter label=com.amazonaws.ecs.container-name=postgres", remote)
        self.assertIn("docker exec -i", remote)
        self.assertIn("psql -c 'SELECT '\"'\"'a b'\"'\"''", remote)
        self.assertNotIn("compose", remote)

    def test_app_command_skips_jump_host(self):
        with patch.multiple(load, **self.REMOTE_SETTINGS):
            argv = load.command_for(["exec", "-T", "payment-worker", "env"])
        self.assertNotIn("-J", argv)
        self.assertEqual(argv[-2], "ubuntu@13.125.2.200")

    def test_missing_host_setting_is_refused(self):
        settings = dict(self.REMOTE_SETTINGS, APP_SSH="")
        with patch.multiple(load, **settings), self.assertRaises(SystemExit):
            load.command_for(["exec", "-T", "payment-worker", "env"])

    def test_sudo_docker_is_split_into_words(self):
        settings = dict(self.REMOTE_SETTINGS, REMOTE_DOCKER="sudo docker")
        with patch.multiple(load, **settings):
            remote = load.command_for(["exec", "-T", "kafka", "true"])[-1]
        self.assertIn("sudo docker ps -q", remote)
        self.assertIn("sudo docker exec -i", remote)

    @unittest.skipUnless(shutil.which("sh"), "sh not available")
    def test_remote_script_requires_exactly_one_container(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "docker"
            fake.write_text('#!/bin/sh\nif [ "$1" = ps ]; then for id in $FAKE_IDS; do echo "$id"; done; '
                            'else shift; echo "exec:$*"; fi\n', encoding="utf-8", newline="\n")
            fake.chmod(0o755)
            with patch.multiple(load, **self.REMOTE_SETTINGS):
                script = load.command_for(["exec", "-T", "kafka", "echo", "a b"])[-1]
            path = tmp + os.pathsep + os.environ.get("PATH", "")
            results = {}
            for ids in ("", "c1", "c1 c2"):
                done = subprocess.run(["sh", "-c", script], capture_output=True, text=True,
                                      env=dict(os.environ, PATH=path, FAKE_IDS=ids))
                results[ids] = (done.returncode, done.stdout.strip())
        self.assertEqual(results[""][0], 3)
        self.assertEqual(results["c1 c2"][0], 3)
        self.assertEqual(results["c1"], (0, "exec:-i c1 echo a b"))

    def test_remote_mode_skips_redis_run_id_and_rejects_worker_restart(self):
        with patch.multiple(load, **self.REMOTE_SETTINGS), patch.object(load, "compose") as compose:
            self.assertIsNone(load.redis_run_id())
            compose.assert_not_called()
            args = argparse.Namespace(stages="300:30", kill_after=5)
            with self.assertRaises(SystemExit):
                load.cmd_ramp(args)
            compose.assert_not_called()

    def test_kafka_bootstrap_uses_localhost_in_remote_mode(self):
        env = dict(os.environ, LOAD_INFRA_SSH="ubuntu@10.0.1.20")
        env.pop("LOAD_KAFKA_BOOTSTRAP", None)
        with patch.dict(os.environ, env, clear=True):
            spec = importlib.util.spec_from_file_location("payment_load_remote", SOURCE)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
        self.assertEqual(module.KAFKA_BOOTSTRAP, "localhost:9092")
        self.assertEqual(load.KAFKA_BOOTSTRAP, "kafka:9092")




class DrainBoundaryTest(unittest.TestCase):
    def sample(self, elapsed, **changes):
        return {"elapsed": elapsed, "observationStarted": elapsed, "observationEnded": elapsed,
                "stage": "drain", "unfinished": 0, "waiting": 0,
                "kafkaLag": 0, "outboxPending": 0, "jobsReview": 0, "inboxReview": 0, **changes}

    def test_first_zero_observation_keeps_time_before_first_drain_sample(self):
        result = load.drain_observation([{"endedMonotonic": 104, "end": "host-end"}],
                                        [self.sample(7)], 100)
        self.assertEqual(3, result["drainSeconds"])
        self.assertEqual(4, result["injectionEndedAt"])
        self.assertTrue(result["drainComplete"])

    def test_sampling_bounds_unknown_lag_and_reviews_do_not_become_zero(self):
        marks = [{"endedMonotonic": 104, "end": "host-end"}]
        result = load.drain_observation(marks, [self.sample(6, waiting=1), self.sample(9)], 100)
        self.assertEqual((2, 5), (result["drainLowerBoundSeconds"], result["drainSeconds"]))
        for key in ("kafkaLag", "jobsReview", "inboxReview"):
            result = load.drain_observation(marks, [self.sample(9, **{key: 1})], 100)
            self.assertFalse(result["drainComplete"])
            self.assertIsNone(result["drainSeconds"])
        result = load.drain_observation([{}], [self.sample(9)], 100)
        self.assertIsNone(result["drainSeconds"])

    def test_p99_is_unavailable_below_ten_thousand_samples(self):
        self.assertIsNone(load.distribution([1.0] * 9999)["p99Ms"])
        self.assertEqual(1.0, load.distribution([1.0] * 10000)["p99Ms"])

    def test_unknown_lag_is_not_evidence_of_nonzero_backlog(self):
        sample = {"elapsed": 6, "observationStarted": 6, "observationEnded": 6,
                  "stage": "drain", "unfinished": 0, "waiting": 0,
                  "kafkaLag": None, "outboxPending": 0, "jobsReview": 0, "inboxReview": 0}
        zero = {**sample, "elapsed": 8, "observationStarted": 8, "observationEnded": 8, "kafkaLag": 0}
        result = load.drain_observation([{"endedMonotonic": 104, "end": "end"}], [sample, zero], 100)
        self.assertEqual(0, result["drainLowerBoundSeconds"])
        self.assertEqual(4, result["drainSeconds"])

    def test_slow_snapshot_return_is_not_the_positive_observation_time(self):
        marks = [{"endedMonotonic": 10, "end": "end"}]
        positive = self.sample(16, unfinished=1, observationStarted=11, observationEnded=16)
        zero = self.sample(20, observationStarted=19, observationEnded=20)
        result = load.drain_observation(marks, [positive, zero], 0)
        # The real drain at 13 is three seconds after injection, within these bounds.
        self.assertEqual(1, result["drainLowerBoundSeconds"])
        self.assertEqual(10, result["drainSeconds"])
        self.assertLessEqual(result["drainLowerBoundSeconds"], 3)

    def test_zero_snapshot_started_before_injection_end_cannot_prove_drain(self):
        sample = self.sample(12, observationStarted=9, observationEnded=12)
        result = load.drain_observation([{"endedMonotonic": 10, "end": "end"}], [sample], 0)
        self.assertFalse(result["drainComplete"])
        self.assertIsNone(result["drainSeconds"])


if __name__ == "__main__":
    unittest.main()
