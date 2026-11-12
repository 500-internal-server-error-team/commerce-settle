"""Capture matching Prometheus windows, then compare workload/conditions before reporting deltas."""
import argparse
import base64
import json
import math
import os
from pathlib import Path
import re
import time
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, HTTPRedirectHandler, build_opener

OPERATIONS = ("payment.confirmed", "refund.completed", "order.settlement.applied",
              "payment.settlement.applied")
IMAGES = ("gateway", "commerce", "payment", "settlement", "finance-mock")
CONDITIONS = ("workload", "datasetSha256", "arrivalRate", "mix", "scenario", "generator",
              "resources", "runtime", "warmupSeconds")


def validate_metadata(metadata):
    if not isinstance(metadata.get("revision"), str) or not re.fullmatch(r"[a-f0-9]{40}", metadata["revision"]):
        raise ValueError("metadata.revision must be the full source SHA")
    if not metadata.get("imageDigests") or not isinstance(metadata["imageDigests"], dict):
        raise ValueError("metadata.imageDigests must identify the actual deployed images")
    if not set(IMAGES).issubset(metadata["imageDigests"]):
        raise ValueError("imageDigests must identify all five service images")
    if any(not re.fullmatch(r"sha256:[a-f0-9]{64}", str(value)) for value in metadata["imageDigests"].values()):
        raise ValueError("imageDigests must contain immutable sha256 image digests")
    conditions = metadata.get("conditions", {})
    if any(key not in conditions or conditions[key] is None for key in CONDITIONS):
        raise ValueError("metadata.conditions must include " + ",".join(CONDITIONS))
    if not re.fullmatch(r"[a-f0-9]{64}", str(conditions["datasetSha256"])):
        raise ValueError("datasetSha256 must identify the initial fixture")
    if any(not isinstance(conditions[key],str) or not conditions[key].strip()
           for key in ("workload","scenario","generator")):
        raise ValueError("workload,scenario,generator must be recorded")
    if any(not isinstance(conditions[key],dict) or not conditions[key] for key in ("resources","runtime")):
        raise ValueError("resources and runtime must describe the measured environment")
    for key in ("arrivalRate","warmupSeconds"):
        value=conditions[key]
        if isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or value<0:
            raise ValueError("invalid numeric condition: "+key)
    if conditions["arrivalRate"]<=0 or not isinstance(conditions["mix"],list) or not conditions["mix"]:
        raise ValueError("arrivalRate and request mix must be positive/nonempty")
    operations = metadata.get("operations", list(OPERATIONS))
    if not operations or len(set(operations)) != len(operations) or any(x not in OPERATIONS for x in operations):
        raise ValueError("operations must select distinct supported business measurements")
    http = metadata.get("http", {})
    if set(http) != {"application", "uri", "method"} or any(not isinstance(x, str) or not x for x in http.values()):
        raise ValueError("http must select one application, uri template and method")
    return operations


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def query(base, expression, at):
    parsed = urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("use an HTTP(S) Prometheus URL without embedded credentials")
    request = Request(base.rstrip("/") + "/api/v1/query?" + urlencode({"query": expression, "time": at}))
    user, password = os.environ.get("PERFORMANCE_AUTH_USER"), os.environ.get("PERFORMANCE_AUTH_PASSWORD")
    if user or password:
        if not user or not password:
            raise ValueError("set both PERFORMANCE_AUTH_USER and PERFORMANCE_AUTH_PASSWORD")
        if parsed.scheme!="https" and parsed.hostname not in ("127.0.0.1","localhost","::1"):
            raise ValueError("use HTTPS for authenticated remote Prometheus")
        request.add_header("Authorization", "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode())
    with build_opener(NoRedirect()).open(request, timeout=15) as response:
        payload = json.load(response)
    if payload.get("status") != "success" or payload.get("data", {}).get("resultType") != "vector":
        raise ValueError("Prometheus did not return a successful instant vector")
    return payload["data"]["result"]


def business_values(rows):
    result = {}
    for row in rows:
        operation = row["metric"]["operation"]
        if operation in result:
            raise ValueError("duplicate business series; single-source rules are required")
        value = float(row["value"][1])
        result[operation] = value if math.isfinite(value) else None
    return result


def one(rows):
    if not rows:
        return None
    if len(rows) != 1:
        raise ValueError("HTTP selector did not identify one aggregate series")
    value = float(rows[0]["value"][1])
    return value if math.isfinite(value) else None


def capture(metadata, window, at, fetch):
    operations = validate_metadata(metadata)
    if window not in (60, 300, 900):
        raise ValueError("window must be 60, 300 or 900 seconds")
    selector = '{window="' + str(window) + '"}'
    sources = business_values(fetch("business:source" + selector))
    for operation in operations:
        observed = sources.get(operation)
        if observed is None or not 0 <= at - observed <= 45:
            raise ValueError("missing/stale/future DB observation: " + operation)
    metrics = {name: business_values(fetch("business:" + name + selector))
               for name in ("completions", "duration_count", "duration_missing")}
    quantiles = {str(q): business_values(fetch(f"histogram_quantile({q}, business:duration_bucket" + selector + ")"))
                 for q in (.5, .95, .99)}
    business = {}
    for operation in operations:
        count = metrics["completions"].get(operation)
        samples = metrics["duration_count"].get(operation)
        missing = metrics["duration_missing"].get(operation)
        if any(x is None or x < 0 for x in (count, samples, missing)) or samples + missing != count:
            raise ValueError("incomplete business snapshot: " + operation)
        if samples and any(quantiles[q].get(operation) is None for q in ("0.5", "0.95", "0.99")):
            raise ValueError("business histogram unavailable: " + operation)
        business[operation] = {"completed": count, "tps": count / window,
                               "latencySamples": samples, "missingDuration": missing,
                               "dbWindowStart": sources[operation]-window,
                               "dbWindowEnd": sources[operation],
                               "observationLagSeconds": at-sources[operation],
                               "p50Seconds": quantiles["0.5"].get(operation) if samples else None,
                               "p95Seconds": quantiles["0.95"].get(operation) if samples else None,
                               "p99Seconds": quantiles["0.99"].get(operation) if samples >= 10000 else None}
    labels = ",".join(key + "=" + json.dumps(value) for key, value in sorted(metadata["http"].items()))
    http = "http_server_requests_seconds_"
    duration = f"[{window}s]"
    count = one(fetch(f"sum(increase({http}count{{{labels}}}{duration}))"))
    if count is None or count < 0:
        raise ValueError("HTTP request histogram/count not observed for selector")
    http_values = {"samples": count}
    for quantile in (.95, .99):
        value = one(fetch(f"histogram_quantile({quantile}, sum by(le) (increase({http}bucket{{{labels}}}{duration})))"))
        if count > 0 and value is None:
            raise ValueError("HTTP count exists but histogram is unavailable")
        http_values[f"p{int(100*quantile)}Seconds"] = value if count and (quantile != .99 or count >= 10000) else None
    return {"format": 1, "metadata": metadata, "windowSeconds": window,
            "measurementStart": at-window, "measurementEnd": at,
            "business": business, "http": http_values}


def compare(before, after):
    for report in (before, after):
        if report.get("format") != 1:
            raise ValueError("unsupported report format")
        validate_metadata(report["metadata"])
    for key in ("conditions", "http", "operations"):
        if before["metadata"].get(key) != after["metadata"].get(key):
            raise ValueError("comparison conditions differ: " + key)
    if before["windowSeconds"] != after["windowSeconds"]:
        raise ValueError("comparison windows differ")
    if set(before["business"]) != set(after["business"]):
        raise ValueError("comparison business measurements differ")
    deltas, unavailable = {}, []
    def delta(name, old, new):
        if old is None or new is None:
            unavailable.append(name)
            return
        if not all(math.isfinite(x) for x in (old,new)):
            raise ValueError("non-finite comparison value")
        deltas[name] = {"before": old, "after": new, "difference": new-old,
                        "percent": (new/old-1)*100 if old else None}
    for operation, new in after["business"].items():
        old = before["business"][operation]
        delta(operation + ".tps", old["tps"], new["tps"])
        if old["missingDuration"] or new["missingDuration"]:
            unavailable.append(operation + ".durationCoverage")
            continue
        for key in ("p50Seconds", "p95Seconds", "p99Seconds"):
            delta(operation + "." + key, old[key], new[key])
    for key in ("p95Seconds", "p99Seconds"):
        delta("http." + key, before["http"][key], after["http"][key])
    return {"complete": not unavailable, "unavailable": unavailable, "deltas": deltas,
            "beforeRevision": before["metadata"]["revision"], "afterRevision": after["metadata"]["revision"],
            "beforeImages": before["metadata"]["imageDigests"], "afterImages": after["metadata"]["imageDigests"],
            "dbWindows": {phase: {op: {key: value[key] for key in ("dbWindowStart", "dbWindowEnd", "observationLagSeconds")}
                                   for op,value in report["business"].items()}
                          for phase,report in (("before",before),("after",after))},
            "beforeVariant": before["metadata"].get("variant"), "afterVariant": after["metadata"].get("variant"),
            "conditions": before["metadata"]["conditions"], "windowSeconds": before["windowSeconds"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    collect = commands.add_parser("capture")
    collect.add_argument("--prometheus", required=True)
    collect.add_argument("--metadata", type=Path, required=True)
    collect.add_argument("--window", type=int, choices=(60,300,900), default=900)
    collect.add_argument("--at", type=float, default=None)
    collect.add_argument("--output", type=Path, required=True)
    diff = commands.add_parser("compare")
    diff.add_argument("before", type=Path)
    diff.add_argument("after", type=Path)
    args = parser.parse_args(argv)
    if args.command == "capture":
        at = args.at if args.at is not None else time.time()
        result = capture(json.loads(args.metadata.read_text()), args.window, at,
                         lambda expression: query(args.prometheus, expression, at))
        with args.output.open("x") as file:
            json.dump(result, file, indent=2, ensure_ascii=False, allow_nan=False)
            file.write("\n")
    else:
        result = compare(json.loads(args.before.read_text()), json.loads(args.after.read_text()))
        print(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False))
        return 0 if result["complete"] else 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
