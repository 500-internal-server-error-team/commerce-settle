"""Running ECS services: order load -> matching performance capture -> financial reconciliation.

Never starts services or deploys. Resume only drains the original order ledger and rechecks evidence.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
import uuid

import order_load
import performance

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import deploy_ecs_apps as deployment
import reconcile
from render_ecs_apps import SERVICES, module_for

OPERATIONS = ["payment.confirmed", "order.settlement.applied", "payment.settlement.applied"]
CREDENTIALS = ("SETTLEMENT_OPERATOR_CREDENTIAL", "PAYMENT_SETTLEMENT_CREDENTIAL",
               "FINANCE_MOCK_SETTLEMENT_CREDENTIAL")


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def failure_detail(error):
    if isinstance(error, HTTPError):
        return "HTTP " + str(error.code)
    if isinstance(error, order_load.ApiError):
        return str(error) + ("; " + error.transport if error.transport else "")
    if isinstance(error, URLError):
        return type(error.reason).__name__
    if isinstance(error, (ValueError, RuntimeError)):
        return str(error)
    return type(error).__name__


def observe_deployment(record, conditions, rate, warmup, aws):
    if record.get("status") != "success":
        raise ValueError("Use a successful deployment.json from the running release")
    services = deployment.describe(aws, record["cluster"], SERVICES)
    resources, runtime = {}, {}
    for name in SERVICES:
        arn = record["registered"][name]
        deployment.require_stable(services[name], arn)
        task = aws("ecs", "describe-task-definition", "--task-definition", arn)["taskDefinition"]
        container = next(c for c in task["containerDefinitions"] if c["name"] == name)
        if container["image"] != record["images"][module_for(name)]:
            raise ValueError("Deployment image differs from current task: " + name)
        tasks = aws("ecs", "list-tasks", "--cluster", record["cluster"], "--service-name",
                    deployment.service_name(name))["taskArns"]
        if not tasks:
            raise ValueError("No running task: " + name)
        running = aws("ecs", "describe-tasks", "--cluster", record["cluster"], "--tasks", *tasks)
        expected = record["images"][module_for(name)].split("@", 1)[1]
        if (running.get("failures") or len(running["tasks"]) != 1
                or running["tasks"][0]["taskDefinitionArn"] != arn
                or running["tasks"][0]["lastStatus"] != "RUNNING"
                or not any(c.get("name") == name and c.get("imageDigest") == expected
                           for c in running["tasks"][0]["containers"])):
            raise ValueError("Running image/revision unverified: " + name)
        resources[name] = {k: container[k] for k in ("cpu", "memory", "memoryReservation") if k in container}
        # Only known non-secret capacity/scenario values; never persist task environments or SSM values.
        allowed = {"PAYMENT_SCENARIO_VERSION", "PAYMENT_APPROVAL_GATEWAY"}
        allowed.update(k for k in (v["name"] for v in container.get("environment", []))
                       if k.endswith(("_DB_POOL_SIZE", "_CONCURRENCY")))
        runtime[name] = {"settings": {
            v["name"]: v["value"] for v in container.get("environment", []) if v["name"] in allowed}}
    metadata = {"revision": record["commit"], "imageDigests": {
        name: image.split("@", 1)[1] for name, image in record["images"].items()},
        "conditions": {**conditions, "arrivalRate": rate, "warmupSeconds": warmup,
                       "resources": resources, "runtime": runtime,
                       "generator": "order_load.py/" + order_load.file_sha(ROOT / "tests/load/order_load.py")},
        "operations": OPERATIONS,
        "http": {"application": "commerce", "uri": "/api/orders", "method": "POST"},
        "deploymentObservedAt": order_load.now(),
        "deploymentTasks": {name: record["registered"][name] for name in SERVICES}}
    performance.validate_metadata(metadata)
    return metadata


def monitor(prometheus, window):
    at = time.time()
    up = performance.query(prometheus, 'up{job="commerce-settle"}', at)
    if len(up) < 6 or any(float(row["value"][1]) != 1 for row in up):
        raise RuntimeError("Prometheus: six service targets must be UP")
    if performance.query(prometheus, 'ALERTS{alertstate="firing",severity="critical"}', at):
        raise RuntimeError("Prometheus: critical alert is firing; stop additional injection")
    sources = performance.business_values(performance.query(
        prometheus, 'business:source{window="' + str(window) + '"}', at))
    if any(sources.get(op) is None or not 0 <= at - sources[op] <= 45 for op in OPERATIONS):
        raise RuntimeError("Prometheus: selected DB business observations are missing/stale/future")
    return {"observedAt": at, "serviceTargets": len(up), "criticalAlerts": 0,
            "businessSources": {op: sources[op] for op in OPERATIONS}}


def preflight(args):
    for name in CREDENTIALS:
        if not os.environ.get(name):
            raise ValueError("Set existing " + name + " in the environment")
    api, owned = order_load.Api(args.base_url), []
    def probe(label, target, action):
        try:
            return action()
        except Exception as error:
            reason = failure_detail(error)
            raise RuntimeError(label + " (" + target + "): " + reason +
                               "; check running service, route and existing credentials") from None
    try:
        for kind, path in (("BUYER", "/api/buyers/me/addresses?size=1"),
                           ("SELLER", "/api/sellers/me/products?size=1")):
            token = probe(kind + " login", api.base, lambda: order_load.login(api, kind, owned))
            probe(kind + " API", api.base, lambda: api.call("GET", path, token=token))
    finally:
        for token in owned:
            api.call("DELETE", "/api/sessions/current", token=token)
    # A nonexistent ID checks operator auth without creating a reconciliation before load.
    def settlement_auth():
        try:
            reconcile.request(args.settlement_url,
                              "/internal/settlement-reconciliations/00000000-0000-4000-8000-000000000000",
                              os.environ[CREDENTIALS[0]])
        except HTTPError as error:
            if error.code != 404:
                raise
    probe("Settlement operator", args.settlement_url, settlement_auth)
    cutoff = datetime.fromtimestamp(time.time() - 601, timezone.utc).isoformat()
    probe("Payment reconciliation", args.payment_url, lambda: reconcile.request(args.payment_url,
          "/internal/payment-reconciliation-checks?" + reconcile.urlencode({"cutoffAt": cutoff, "size": 1}), os.environ[CREDENTIALS[1]]))
    probe("Finance reconciliation", args.finance_url, lambda: reconcile.request(args.finance_url,
          "/mock/reconciliation-integrity", os.environ[CREDENTIALS[2]]))
    return probe("Prometheus", args.prometheus, lambda: monitor(args.prometheus, args.seconds))


def run_load(command, args, directory):
    observations = []
    process = subprocess.Popen(command, start_new_session=True)
    failure = None
    try:
        while True:
            try:
                code = process.wait(timeout=15)
                break
            except subprocess.TimeoutExpired:
                if failure is None:
                    try:
                        observations.append(monitor(args.prometheus, args.seconds))
                    except Exception as error:
                        failure = failure_detail(error)
                        process.send_signal(signal.SIGINT)  # order_load owns container stop, drain and cleanup.
    except KeyboardInterrupt:
        failure = "Interrupted; original transactions retained"
        process.send_signal(signal.SIGINT)
        code = process.wait()
    finally:
        order_load.write_json(directory / "monitor.json", {"samples": observations, "failure": failure})
    return code, failure


def assess(load, capture):
    result = {"injection": "PASS" if load.get("injectionPassed") else "FAIL",
              "orderDrain": "PASS" if load.get("drainComplete") else "FAIL",
              "paymentConfirmed": "UNVERIFIED", "settlementFirstApplied": "UNVERIFIED",
              "sustained200Tps": "UNVERIFIED", "payout": "NOT_TESTED"}
    result["confirmedOrdersAfterDrain"] = load.get("confirmedOrders")
    if not capture:
        return result
    window = load.get("measurementWindow") or {}
    same = all(abs(row["dbWindowStart"] - window.get("start", 0)) <= 1
               and abs(row["dbWindowEnd"] - window.get("end", 0)) <= 1
               for row in capture["business"].values())
    result.update(sameDbWindow=same, population="environment aggregate; ledger run is separate",
                  observedBusiness=capture["business"], http=capture["http"],
                  performanceReason="200 TPS acceptance also needs isolated population, clock evidence, backlog trend and inventory/financial checks")
    return result


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--base-url")
    p.add_argument("--deployment-record", type=Path)
    p.add_argument("--conditions", type=Path, help="Existing conditions object: datasetSha256, workload, mix, scenario")
    p.add_argument("--prometheus")
    p.add_argument("--settlement-url", default="http://127.0.0.1:8084")
    p.add_argument("--payment-url", default="http://127.0.0.1:8082")
    p.add_argument("--finance-url", default="http://127.0.0.1:8085")
    p.add_argument("--app-ssh", default=os.environ.get("RECONCILE_SSH", os.environ.get("LOAD_APP_SSH")))
    p.add_argument("--tps", type=int, default=2)
    p.add_argument("--seconds", type=int, choices=(60, 300, 900), default=60)
    p.add_argument("--warmup-seconds", type=int, default=0)
    p.add_argument("--drain-seconds", type=int, default=300)
    p.add_argument("--allow-pg-declines", action="store_true")
    p.add_argument("--output-dir", type=Path)
    p.add_argument("--resume", type=Path)
    p.add_argument("--check-only", action="store_true")
    p.add_argument("--reconcile-timeout", type=int, default=900,
                   help="Includes the unchanged ten-minute cutoff safety margin")
    p.add_argument("--compare", type=Path, help="Optional original performance.json baseline")
    return p


def main(argv=None):
    p = parser()
    args = p.parse_args(argv)
    if not 1 <= args.tps <= 2000 or not 0 <= args.warmup_seconds <= 300 or not 1 <= args.drain_seconds <= 900 or args.reconcile_timeout < 1:
        p.error("Check rate, warmup, drain and reconciliation time budgets")
    if args.tps >= 200 and args.seconds == 900 and not args.warmup_seconds and not args.resume:
        p.error("200 TPS/900s requires warmup")
    if args.resume and args.resume.is_symlink():
        p.error("Do not resume through a symlink")
    original = args.resume.resolve() if args.resume else None
    if original and args.check_only:
        p.error("--check-only checks a new connection; use --resume alone to recover original transactions")
    if original and (args.output_dir or args.base_url or args.conditions or args.deployment_record):
        p.error("Resume keeps original targets/conditions; omit new run inputs")
    if original:
        saved = read(original / "execution.json")
        for key, value in saved["arguments"].items():
            setattr(args, key, value)
    elif not all((args.base_url, args.deployment_record, args.conditions, args.prometheus)):
        p.error("Supply --base-url, --deployment-record, --conditions and --prometheus")
    for key in ("base_url", "prometheus", "settlement_url", "payment_url", "finance_url"):
        try:
            setattr(args, key, order_load.base_url(getattr(args, key)))
        except ValueError as error:
            p.error(key + ": " + str(error))
    directory = (original / ("recovery-" + uuid.uuid4().hex) if original else
                 args.output_dir or ROOT / "tests/load/work" / ("experiment-" + uuid.uuid4().hex)).absolute()
    directory.mkdir(parents=True, mode=0o700)
    report = {"startedAt": order_load.now(), "completed": False, "phase": "preflight", "stages": {}}
    execution = read(original / "execution.json") if original else {
        "arguments": {k: getattr(args, k) for k in ("base_url", "prometheus", "settlement_url", "payment_url", "finance_url", "app_ssh")}}
    if original and execution.get("comparison"):
        args.compare = Path(execution["comparison"]["path"])
    if args.app_ssh:
        os.environ["RECONCILE_SSH"] = args.app_ssh
    else:
        os.environ.pop("RECONCILE_SSH", None)
    load = capture = None
    try:
        if args.compare and not original:
            execution["comparison"] = {"path": str(args.compare.resolve()),
                                       "sha256": order_load.file_sha(args.compare)}
        try:
            report["preflight"] = preflight(args)
        except Exception:
            if not original:
                raise
            report["stages"]["preflight"] = "FAIL"
        if original:
            metadata = read(original / "metadata.json")
        else:
            record = read(args.deployment_record)
            metadata = observe_deployment(record, read(args.conditions), args.tps, args.warmup_seconds,
                                          deployment.Aws(record["region"]))
        order_load.write_json(directory / "metadata.json", metadata)
        order_load.write_json(directory / "execution.json", execution)
        report["stages"].setdefault("preflight", "PASS")
        if args.check_only:
            report["connectionVerified"] = True
            return 0
        report["phase"] = "load"
        load_root = original / "load" if original else directory / "load"
        command = [sys.executable, "-B", str(ROOT / "tests/load/order_load.py")]
        if original:
            before = set(load_root.glob("recovery-*"))
            command += ["--resume", str(load_root), "--drain-seconds", str(args.drain_seconds)]
        else:
            command += ["--base-url", args.base_url, "--output-dir", str(load_root),
                        "--tps", str(args.tps), "--seconds", str(args.seconds),
                        "--warmup-seconds", str(args.warmup_seconds), "--drain-seconds", str(args.drain_seconds),
                        "--performance-metadata", str(directory / "metadata.json")]
            if args.allow_pg_declines:
                command.append("--allow-pg-declines")
        try:
            code, failure = run_load(command, args, directory)
            load_result = next(iter(set(load_root.glob("recovery-*")) - before)) if original else load_root
            load = read(load_result / "summary.json")
            report["stages"]["load"] = "PASS" if code == 0 and not failure else "FAIL"
        except Exception as error:
            report["stages"]["load"] = "FAIL"
            report["loadFailure"] = failure_detail(error)
        original_load = read(load_root / "summary.json") if (load_root / "summary.json").exists() else {}
        if original:
            report["stages"]["recovery"] = report["stages"]["load"]
            report["stages"]["load"] = read(original / "result.json")["stages"].get("load", "FAIL")
        execution.setdefault("cutoffAt", order_load.now())
        order_load.write_json(directory / "execution.json", execution)
        # Capture is always attempted after an interrupted load when its original window is available.
        report["phase"] = "capture"
        window = original_load.get("measurementWindow")
        if window:
            try:
                seconds = round(window["end"] - window["start"])
                linked = read(load_root / "performance-metadata.json")
                capture = performance.capture(linked, seconds, window["end"],
                    lambda expr: performance.query(args.prometheus, expr, window["end"]))
                order_load.write_json(directory / "performance.json", capture)
                report["stages"]["capture"] = "PASS"
            except Exception as error:
                report["stages"]["capture"] = "FAIL"
                report["captureFailure"] = failure_detail(error)
                print("Capture failed at " + args.prometheus + ": " + failure_detail(error) +
                      "; check Prometheus authentication/recording rules, then resume the original experiment", file=sys.stderr)
        else:
            report["stages"]["capture"] = "NOT_RUN"
        report["phase"] = "reconciliation"
        deadline = time.monotonic() + args.reconcile_timeout
        ready = order_load.timestamp(execution["cutoffAt"]) + 601
        while time.time() < ready:
            if time.monotonic() >= deadline:
                raise TimeoutError("Financial cutoff safety margin pending; resume the original experiment")
            time.sleep(min(1, ready - time.time()))
        financial = argparse.Namespace(settlement_url=args.settlement_url, payment_url=args.payment_url,
            finance_url=args.finance_url, cutoff_at=execution["cutoffAt"], run_id=None,
            timeout=max(1, int(deadline - time.monotonic())))
        result = reconcile.verify(financial,
            on_run=lambda value: order_load.write_json(directory / "reconciliation-run.json", value),
            on_result=lambda value: order_load.write_json(directory / "reconciliation.json", value))
        report["stages"]["reconciliation"] = "PASS" if result == 0 else "FAIL"
        if args.compare and capture:
            expected = execution.get("comparison")
            if expected and order_load.file_sha(args.compare) != expected["sha256"]:
                raise ValueError("Original comparison baseline changed; preserve the failed comparison obligation")
            comparison = performance.compare(read(args.compare), capture)
            order_load.write_json(directory / "comparison.json", comparison)
            report["stages"]["comparison"] = "PASS" if comparison["complete"] else "UNVERIFIED"
        report["completed"] = all(report["stages"].get(k) == "PASS" for k in ("load", "capture", "reconciliation")) and not any(
            value in ("FAIL", "UNVERIFIED") for value in report["stages"].values())
        return 0 if report["completed"] else 1
    except (Exception, KeyboardInterrupt) as error:
        detail = failure_detail(error)
        report["failure"] = {"phase": report["phase"], "type": type(error).__name__, "detail": detail,
                             "targets": execution["arguments"]}
        print("Stopped at " + report["phase"] + ": " + type(error).__name__ +
              " (" + detail + "). Check selected targets in result.json, SSH identity, credentials and Prometheus; start/configure manually, then rerun. See " + str(directory), file=sys.stderr)
        return 1
    finally:
        report["verdicts"] = assess(load or {}, capture)
        report["endedAt"] = order_load.now()
        report["evidence"] = {str(path.relative_to(directory)): order_load.file_sha(path)
                              for path in directory.rglob("*.json") if path.name != "result.json"}
        order_load.write_json(directory / "result.json", report)
        print("Result: " + str(directory / "result.json"), flush=True)


if __name__ == "__main__":
    sys.exit(main())
