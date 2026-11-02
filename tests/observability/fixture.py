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
                ]
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
