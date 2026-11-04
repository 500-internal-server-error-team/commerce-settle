"""Run/resume Settlement's durable job and each owner's read-only checks after load/recovery.

Credentials are read from existing environment settings; source DBs are never opened by this tool.
A pending, quarantined or unavailable result is not a successful zero-defect verification.
"""
import argparse
from collections import Counter
import json
import os
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen


def request(base, path, credential, body=None):
    if not credential:
        raise ValueError("Required service/operator credential is missing")
    headers = {"Authorization": "Bearer " + credential}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    with urlopen(Request(base.rstrip("/") + path, data=data, headers=headers), timeout=10) as response:
        return json.load(response)


def verify(args):
    operator = os.environ.get("SETTLEMENT_OPERATOR_CREDENTIAL")
    service = os.environ.get("PAYMENT_SETTLEMENT_CREDENTIAL")
    finance = os.environ.get("FINANCE_MOCK_SETTLEMENT_CREDENTIAL")
    path = "/internal/settlement-reconciliations"
    if args.run_id:
        run = request(args.settlement_url, path + "/" + args.run_id, operator)
        if args.cutoff_at and args.cutoff_at != run["cutoffAt"]:
            raise ValueError("Resume uses the original run's cutoff; omit --cutoff-at")
    else:
        if not args.cutoff_at:
            raise ValueError("Supply --cutoff-at or --run-id")
        run = request(args.settlement_url, path, operator, {"cutoffAt": args.cutoff_at})
    detail = path + "/" + run["id"]
    deadline = time.monotonic() + args.timeout
    while run["status"] not in ("COMPLETED", "REVIEW_REQUIRED", "WAITING", "UNVERIFIED"):
        if time.monotonic() >= deadline:
            raise TimeoutError("Reconciliation remains queued/running; resume with --run-id " + run["id"])
        time.sleep(1)
        run = request(args.settlement_url, detail, operator)
    summary = {"run": run, "results": request(args.settlement_url, detail + "/results?size=100", operator)}
    # Both directions use the exact run snapshot, including when recovering an old run.
    if not run["snapshotAt"]:
        raise RuntimeError("Source baseline is unavailable; run " + run["id"] + " remains unverified")
    summaries = {}
    failed = run["status"] != "COMPLETED"
    for direction in ("PAYMENT", "PROVIDER"):
        cursor = None
        seen = set()
        counts = Counter()
        delta = 0
        while True:
            query = {"cutoffAt": run["cutoffAt"], "snapshotAt": run["snapshotAt"], "size": 100, "direction": direction}
            if cursor:
                query["cursor"] = cursor
            page = request(args.payment_url, "/internal/payment-reconciliation-checks?" + urlencode(query), service)
            if page["cutoffAt"] != run["cutoffAt"] or page["snapshotAt"] != run["snapshotAt"]:
                # ISO strings may use another equivalent offset; compare absolute instants.
                from datetime import datetime
                for field in ("cutoffAt", "snapshotAt"):
                    if datetime.fromisoformat(page[field]) != datetime.fromisoformat(run[field]):
                        raise ValueError("Source changed the fixed boundary")
            for row in page["items"]:
                counts[row["status"]] += 1
                delta += row["difference"]
                failed |= row["status"] != "MATCH"
            if "currentChecks" in page:
                summaries[direction + "Current"] = {"checkedAt": page["checkedAt"], "checks": page["currentChecks"]}
                failed |= any(value != 0 for value in page["currentChecks"].values())
            cursor = page["nextCursor"]
            if not cursor:
                break
            if cursor in seen:
                raise ValueError("Repeated source cursor")
            seen.add(cursor)
        summaries[direction] = {"counts": dict(counts), "difference": delta}
    summary["paymentOwner"] = summaries
    summary["financeOwner"] = request(args.finance_url, "/mock/reconciliation-integrity", finance)
    failed |= any(value != 0 for value in summary["financeOwner"]["checks"].values())
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cutoff-at")
    parser.add_argument("--run-id")
    parser.add_argument("--settlement-url", required=True)
    parser.add_argument("--payment-url", required=True)
    parser.add_argument("--finance-url", required=True)
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("--timeout must be positive")
    return verify(args)


if __name__ == "__main__":
    raise SystemExit(main())
