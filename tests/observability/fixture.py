"""Isolated metrics and notification receiver for the observability integration check."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time


LOCK = threading.Lock()
STATE = {"active": False, "notifications": []}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        with LOCK:
            if self.path == "/metrics":
                labels = 'application="payment",consumer="payment.v1"'
                active = int(STATE["active"])
                lines = [
                    f'payment_quarantine_observed_timestamp_seconds{{{labels}}} {time.time()}',
                    f'payment_quarantine_collection_success{{{labels}}} 1',
                    f'payment_quarantine_captured_recent{{{labels}}} {active}',
                    f'payment_quarantine_originals{{{labels}}} {active}',
                    f'payment_quarantine_delivery_records{{{labels},kind="DLT",status="REVIEW_REQUIRED"}} {active}',
                    f'payment_quarantine_delivery_overdue_seconds{{{labels},kind="DLT"}} {active * 1000}',
                    f'commerce_notification_observed_timestamp_seconds{{application="commerce"}} {time.time()}',
                    'commerce_notification_collection_success{application="commerce"} 1',
                    f'commerce_notification_overdue_seconds{{application="commerce"}} {active * 1000}',
                    f'commerce_notification_review_required{{application="commerce"}} {active}',
                    f'commerce_notification_retry_exhausted{{application="commerce"}} {active}',
                    f'commerce_notification_financial_action_recent{{application="commerce"}} {active}',
                    f'commerce_notification_kakao_reauthentication_required{{application="commerce"}} {active}',
                ]
                lines += [
                    f'commerce_settle_runtime_observed_timestamp_seconds {time.time()}',
                    f'commerce_settle_container_memory_usage_ratio{{service="payment-worker",basis="limit"}} {0.99 if active else 0.2}',
                    f'commerce_settle_container_memory_usage_ratio{{service="commerce",basis="limit"}} {0.92 if active else 0.2}',
                    'commerce_settle_docker_host_cpu_usage_ratio 0.6',
                    'commerce_settle_docker_host_memory_available_ratio 0.2',

                    f'commerce_settle_container_oom_killed{{service="payment-worker"}} {active}',
                    f'commerce_settle_container_last_exit_code{{service="payment-worker"}} {137 if active else 0}',
                    f'commerce_settle_financial_operation_age_seconds{{operation="refund.unknown"}} {901 if active else 0}',
                    f'commerce_settle_financial_operation_observed_timestamp_seconds{{operation="refund.unknown"}} {time.time()}',
                ]
                for operation, value in (("payout.total", 1), ("payout.ready", 2), ("payout.unapplied", 3)):
                    for metric in ("records", "age_seconds"):
                        lines.append(f'business:cohort_{metric}{{operation="{operation}",reason="pending"}} {value}')
                raw = ("\n".join(lines) + "\n").encode()
            elif self.path == "/notifications":
                raw = json.dumps(STATE["notifications"]).encode()
            else:
                self.send_error(404)
                return
        self.send_response(200)
        self.send_header(
            "Content-Type",
            "text/plain; version=0.0.4; charset=utf-8"
            if self.path == "/metrics" else "application/json",
        )
        self.end_headers()
        self.wfile.write(raw)

    def do_POST(self):
        raw = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))))
        with LOCK:
            if self.path == "/state":
                STATE["active"] = bool(raw["active"])
            elif self.path == "/alerts":
                STATE["notifications"].append(raw)
            else:
                self.send_error(404)
                return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b"{}")


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
