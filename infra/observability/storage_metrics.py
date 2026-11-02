"""Observe the mounted data filesystems, without Docker socket or database credentials."""

import json
import math
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import threading
import time


class StorageMetrics:
    def __init__(self, paths):
        if not isinstance(paths, dict) or set(paths) != {"kafka", "postgres"} or any(
            not isinstance(values, list) or not values
            or any(not isinstance(value, str) or not Path(value).is_absolute() for value in values)
            for values in paths.values()
        ):
            raise ValueError("Explicit absolute data filesystem paths are required for both stores")
        self.paths = paths
        self._snapshot = {store: (math.nan, 0) for store in paths}
        self._lock = threading.Lock()

    def refresh(self):
        current = {}
        for store, paths in self.paths.items():
            try:
                observations = []
                for path in paths:
                    filesystem = os.statvfs(path)
                    if filesystem.f_blocks <= 0:
                        raise ValueError("Data filesystem has no capacity")
                    ratio = 1 - filesystem.f_bavail / filesystem.f_blocks
                    if not 0 <= ratio <= 1:
                        raise ValueError("Invalid filesystem observation")
                    observations.append((ratio, time.time()))
                current[store] = (max(value[0] for value in observations), min(value[1] for value in observations))
            except (OSError, ValueError):
                current[store] = (math.nan, 0)
        with self._lock:
            self._snapshot = current

    def render(self):
        with self._lock:
            current = self._snapshot
        lines = ["# TYPE commerce_settle_data_filesystem_usage_ratio gauge",
                 "# TYPE commerce_settle_admission_observed_timestamp_seconds gauge"]
        for store, (ratio, observed) in current.items():
            value = "NaN" if math.isnan(ratio) else repr(ratio)
            lines.append(f'commerce_settle_data_filesystem_usage_ratio{{store="{store}"}} {value}')
            lines.append(f'commerce_settle_admission_observed_timestamp_seconds{{signal="STORAGE",resource="{store}"}} {observed}')
        return ("\n".join(lines) + "\n").encode()


def main():
    metrics = StorageMetrics(json.loads(os.environ["DATA_FILESYSTEM_PATHS_JSON"]))

    def observe():
        while True:
            metrics.refresh()
            time.sleep(15)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/metrics":
                self.send_error(404)
                return
            body = metrics.render()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            pass

    threading.Thread(target=observe, daemon=True, name="data-filesystem-observer").start()
    HTTPServer(("0.0.0.0", 9108), Handler).serve_forever()


if __name__ == "__main__":
    main()
