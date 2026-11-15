"""Run/resume Settlement's durable job and each owner's read-only checks after load/recovery.

Credentials are read from existing environment settings; source DBs are never opened by this tool.
A pending, quarantined or unavailable result is not a successful zero-defect verification.
"""
import argparse
from collections import Counter
import json
import os
import subprocess
import time
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, HTTPRedirectHandler, ProxyHandler, build_opener

CREDENTIALS = ("SETTLEMENT_OPERATOR_CREDENTIAL", "PAYMENT_SETTLEMENT_CREDENTIAL",
               "FINANCE_MOCK_SETTLEMENT_CREDENTIAL")


def host_credential(name, docker_command):
    """Resolve an existing caller credential on the app host; never return it over SSH."""
    import json
    import subprocess

    if name not in ("SETTLEMENT_OPERATOR_CREDENTIAL", "PAYMENT_SETTLEMENT_CREDENTIAL",
                    "FINANCE_MOCK_SETTLEMENT_CREDENTIAL"):
        raise ValueError("Unsupported reconciliation credential name")
    def docker(*args):
        return subprocess.run([*docker_command, *args], capture_output=True, text=True,
                              check=True, timeout=10).stdout
    ids = docker("ps", "-q", "--filter", "label=com.amazonaws.ecs.container-name=settlement").split()
    if len(ids) != 1:
        raise ValueError("Select exactly one running Settlement container for existing credentials")
    container = json.loads(docker("inspect", ids[0]))[0]
    environment = dict(value.split("=", 1) for value in container["Config"].get("Env", []) if "=" in value)
    if name == "SETTLEMENT_OPERATOR_CREDENTIAL":
        settings = json.loads(environment.get("SPRING_APPLICATION_JSON") or "{}")
        senders = settings.get("app", {}).get("internal-auth", {}).get("senders", {})
        operators = [value.get("current") for key, value in senders.items()
                     if key.strip("[]").startswith("operator:") and value.get("current")]
        if len(operators) != 1:
            raise ValueError("Settlement needs one existing operator in SPRING_APPLICATION_JSON, or an explicit SETTLEMENT_OPERATOR_CREDENTIAL")
        return operators[0]
    if not environment.get(name):
        raise ValueError("Selected Settlement has no existing " + name)
    return environment[name]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def local_request(base, path, credential, body=None):
    parsed = urlsplit(base)
    if (parsed.scheme not in ("https", "http") or not parsed.hostname
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path not in ("", "/")
            or parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1", "::1")):
        raise ValueError("Use HTTPS or loopback HTTP, without embedded credentials")
    headers = {"Authorization": "Bearer " + credential}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    handlers = [NoRedirect()]
    if parsed.hostname in ("localhost", "127.0.0.1", "::1"):
        handlers.append(ProxyHandler({}))
    try:
        with build_opener(*handlers).open(Request(base.rstrip("/") + path, data=data, headers=headers), timeout=10) as response:
            return json.load(response)
    except ValueError:
        # HTTP/header errors can include the complete Authorization value.
        raise ValueError("Invalid reconciliation HTTP request or response; check URL, credential format and JSON response") from None


def request(base, path, credential, body=None, *, credential_name=None):
    host = os.environ.get("RECONCILE_SSH")
    if not host:
        if not credential:
            raise ValueError("Required service/operator credential is missing")
        return local_request(base, path, credential, body)
    if not credential and credential_name not in CREDENTIALS:
        raise ValueError("Required service/operator credential is missing")
    if not credential and urlsplit(base).hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("Automatic host credentials require app-host loopback URLs; provide an explicit credential for another target")
    if host.startswith("-") or any(char.isspace() for char in host):
        raise ValueError("RECONCILE_SSH must be one SSH destination")
    # Reuse the existing app SSH identity. Payload/credentials travel over stdin, never argv or disk.
    command = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10"]
    if os.environ.get("LOAD_SSH_KEY"):
        command += ["-i", os.environ["LOAD_SSH_KEY"]]
    script = ("import json,sys\nfrom urllib.error import HTTPError\n"
              "exec(json.loads(sys.stdin.readline()))\nargs=json.load(sys.stdin)\n"
              "try:\n name,docker=args.pop(),args.pop()\n"
              " if not args[2]: args[2]=host_credential(name,docker)\n"
              " print(json.dumps({'ok':True,'value':local_request(*args)}))\n"
              "except HTTPError as e:\n print(json.dumps({'ok':False,'status':e.code}))\n"
              "except ValueError as e:\n print(json.dumps({'ok':False,'error':str(e)}))\n")
    import inspect
    import shlex
    source = ("import json\nfrom urllib.parse import urlsplit\n"
              "from urllib.request import Request, HTTPRedirectHandler, ProxyHandler, build_opener\n"
              + inspect.getsource(NoRedirect) + "\n" + inspect.getsource(local_request) + "\n" + inspect.getsource(host_credential))
    docker_command = shlex.split(os.environ.get("LOAD_REMOTE_DOCKER", "docker"))
    if not docker_command:
        raise ValueError("LOAD_REMOTE_DOCKER must select an existing Docker command")
    command += [host, "python3 -c " + shlex.quote(script)]
    result = subprocess.run(command, input=json.dumps(source) + "\n" + json.dumps([base, path, credential, body, docker_command, credential_name]),
                            text=True, capture_output=True, timeout=25)
    if result.returncode:
        raise RuntimeError("Reconciliation SSH/HTTP failed; check the app SSH identity, running service and credential")
    result = json.loads(result.stdout)
    if not result["ok"]:
        if "error" in result:
            raise ValueError(result["error"])
        raise HTTPError(base, result["status"], "Remote HTTP failed", {}, None)
    return result["value"]


def verify(args, *, on_run=None, on_result=None):
    operator = os.environ.get("SETTLEMENT_OPERATOR_CREDENTIAL")
    service = os.environ.get("PAYMENT_SETTLEMENT_CREDENTIAL")
    finance = os.environ.get("FINANCE_MOCK_SETTLEMENT_CREDENTIAL")
    path = "/internal/settlement-reconciliations"
    if args.run_id:
        run = request(args.settlement_url, path + "/" + args.run_id, operator, credential_name=CREDENTIALS[0])
        if args.cutoff_at and args.cutoff_at != run["cutoffAt"]:
            raise ValueError("Resume uses the original run's cutoff; omit --cutoff-at")
    else:
        if not args.cutoff_at:
            raise ValueError("Supply --cutoff-at or --run-id")
        run = request(args.settlement_url, path, operator, {"cutoffAt": args.cutoff_at}, credential_name=CREDENTIALS[0])
    if on_run:
        on_run(run)
    detail = path + "/" + run["id"]
    deadline = time.monotonic() + args.timeout
    while run["status"] not in ("COMPLETED", "REVIEW_REQUIRED", "WAITING", "UNVERIFIED"):
        if time.monotonic() >= deadline:
            raise TimeoutError("Reconciliation remains queued/running; resume with --run-id " + run["id"])
        time.sleep(1)
        run = request(args.settlement_url, detail, operator, credential_name=CREDENTIALS[0])
        if on_run:
            on_run(run)
    summary = {"run": run, "results": request(args.settlement_url, detail + "/results?size=100", operator, credential_name=CREDENTIALS[0])}
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
            page = request(args.payment_url, "/internal/payment-reconciliation-checks?" + urlencode(query), service, credential_name=CREDENTIALS[1])
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
    summary["financeOwner"] = request(args.finance_url, "/mock/reconciliation-integrity", finance, credential_name=CREDENTIALS[2])
    failed |= any(value != 0 for value in summary["financeOwner"]["checks"].values())
    if on_result:
        on_result(summary)
    else:
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
