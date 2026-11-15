"""Check production rules and an isolated metrics -> alert -> resolved notification flow."""

import base64
from copy import deepcopy
from smoke_financial_flow import assert_payout_trace
from contextlib import contextmanager
import json
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import tempfile
import time
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]


@contextmanager
def owned_settings(project):
    directory = Path(tempfile.mkdtemp(prefix=project + "-"))
    try:
        yield directory
    finally:
        if (directory / "cleanup-complete").exists():
            shutil.rmtree(directory)
        else:
            print(f"Cleanup unverified; retained project {project} settings at {directory}")


def http(url, data=None, authorization=None):
    headers = {"Content-Type": "application/json"}
    if authorization:
        headers["Authorization"] = authorization
    request = Request(url, data=None if data is None else json.dumps(data).encode(), headers=headers)
    with urlopen(request, timeout=5) as response:
        return json.load(response)


def until(action, description, seconds=100):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = action()
            if value:
                return value
        except (URLError, OSError, ValueError, KeyError):
            pass
        time.sleep(1)
    raise AssertionError(description)


def integration_rules(production):
    return re.sub(r"(?m)^    interval: [^\n]+$", "    interval: 1s", production)


def verify_dashboard_quantiles():
    for name in ("business-performance", "payment-worker", "overview"):
        dashboard = json.loads((ROOT / "infra/grafana/dashboards" / (name + ".json")).read_text())
        for panel in dashboard["panels"]:
            refs = [target.get("refId") for target in panel.get("targets", [])]
            assert all(isinstance(ref, str) and ref for ref in refs) and len(refs) == len(set(refs)), (name, panel["id"])
        if name == "overview":
            continue
        p99 = [target["expr"] for panel in dashboard["panels"] for target in panel.get("targets", [])
               if re.search(r"histogram_quantile\(0?\.99", target.get("expr", ""))]
        assert p99 and all(re.search(r">=\s*10000", expression) for expression in p99), name
        assert all("window" in expression for expression in p99), name


def verify_payout_trace_guards():
    trace = "1" * 32
    saved = {"jobId": "job", "payoutId": "payout", "traceparent": "00-" + trace + "-" + "a"*16 + "-01"}
    def span(identity, name, service, parent=None, kind=None, tags=None):
        return {"id": identity*16, "traceId": trace, "name": name, "parentId": parent*16 if parent else None,
                "localEndpoint": {"serviceName": service}, "kind": kind, "tags": tags or {}}
    spans = [span("a", "batch API", "settlement", kind="SERVER", tags={"method":"POST", "uri":"/internal/settlement-batches"}),
             span("b", "settlement.payout.process", "settlement", "a", tags={"job.id":"job", "payout.id":"payout"}),
             span("c", "settlement.payout.gateway", "settlement", "b"),
             span("d", "HTTP client", "settlement", "c", "CLIENT"),
             span("e", "HTTP server", "finance-mock", "d", "SERVER", {"method":"POST", "uri":"/mock/payouts"})]
    assert_payout_trace(spans, trace, saved)
    cases = [spans[1:]]
    for index, key, value in [(1,"parentId","f"*16), (4,"parentId",None), (3,"parentId","a"*16)]:
        broken = deepcopy(spans);broken[index][key] = value;cases.append(broken)
    broken = deepcopy(spans);broken[1]["tags"]["job.id"] = "unrelated";cases.append(broken)
    for broken in cases:
        try:
            assert_payout_trace(broken, trace, saved)
        except AssertionError:
            continue
        raise AssertionError("unrelated payout trace passed saved-parent/HTTP ancestry checks")


def verify_runtime_dashboards(query):
    def panels(name):
        dashboard = json.loads((ROOT / "infra/grafana/dashboards" / (name + ".json")).read_text())
        return {panel["id"]: panel for panel in dashboard["panels"]}

    host = panels("overview")[8]
    names = {row["metric"]["__name__"] for target in host["targets"] for row in query(target["expr"])}
    assert names == {"commerce_settle_docker_host_cpu_usage_ratio", "commerce_settle_docker_host_memory_available_ratio"}, names
    for number in (7, 9):
        panel = panels("overview")[number]
        rows = until(lambda: query(panel["targets"][0]["expr"]), "required runtime label series was not queryable")
        legend_names(panel, rows)
    business = panels("business-performance")
    for number, expected in ((15, {"payout.total", "payout.ready"}), (16, {"payout.total", "payout.ready"}),
                             (19, {"payout.unapplied"}), (22, {"payout.unapplied"})):
        actual = {row["metric"]["operation"] for target in business[number]["targets"] for row in query(target["expr"])}
        assert actual == expected, (number, actual)
    for number in (20, 21):
        panel = business[number]
        rows = until(lambda: query(panel["targets"][0]["expr"]), "required cohort label series was not queryable")
        legend_names(panel, rows)



def legend_names(panel, rows):
    template = panel["targets"][0]["legendFormat"]
    required = set(re.findall(r"{{([^}]+)}}", template))
    names = []
    assert rows, (panel["id"], "no returned series")
    for row in rows:
        labels = row["metric"]
        assert required <= labels.keys(), (panel["id"], required, labels)
        names.append(re.sub(r"{{([^}]+)}}", lambda match: labels[match[1]], template))
    assert len(names) == len(set(names)), (panel["id"], "ambiguous legends", names)
    return names


def verify_quarantine_dashboards(query, definitions, fixture):
    expected = {"payment-quarantine": ("결제", {"payment-api:9091", "payment-worker:9091"}),
                "settlement-quarantine": ("정산", {"settlement:9091"})}
    rendered = {}
    for uid, (service, instances) in expected.items():
        panels = {panel["id"]: panel for panel in definitions[uid]["panels"]}
        assert service in panels[8]["title"] and not any(
            other in panels[8]["title"] for other in ("결제", "정산") if other != service), (uid, panels[8]["title"])
        rendered[uid] = {}
        for number in (5, 6, 8):
            panel = panels[number]
            expression = panel["targets"][0]["expr"]
            def rows():
                return [row for row in query(expression) if row["metric"].get("instance") in instances]
            until(lambda: {row["metric"]["instance"] for row in rows()} == instances,
                  "quarantine health instances were not all scraped")
            actual = rows()
            rendered[uid][number] = legend_names(panel, actual)
            if uid == "payment-quarantine":
                broken = deepcopy(panel)
                broken["targets"][0]["legendFormat"] = "{{consumer}}" if number != 8 else "{{consumer}} {{kind}} {{status}}"
                try:
                    legend_names(broken, actual)
                except AssertionError:
                    pass
                else:
                    raise AssertionError("missing or ambiguous quarantine health labels passed")
    payment = definitions["payment-quarantine"]
    up = next(panel for panel in payment["panels"] if panel["id"] == 8)
    try:
        http(fixture + "/state", {"api_down": True})
        until(lambda: {row["metric"]["instance"]: float(row["value"][1]) for row in query(up["targets"][0]["expr"])}
              == {"payment-api:9091": 0, "payment-worker:9091": 1}, "API-only scrape failure was not isolated")
        legend_names(up, query(up["targets"][0]["expr"]))
    finally:
        http(fixture + "/state", {"api_down": False})
    until(lambda: {row["metric"]["instance"]: float(row["value"][1]) for row in query(up["targets"][0]["expr"])}
          == {"payment-api:9091": 1, "payment-worker:9091": 1}, "API scrape did not recover")
    print("PASS quarantine health legends and target names " + json.dumps(rendered, ensure_ascii=False))


def verify_jdbc_probe(port, application, role):
    """Consume real Spring/Hikari/JDBC metrics from the PostgreSQL integration test."""
    import math
    probe = f"http://127.0.0.1:{port}"
    project = "cs-jdbc-observation-" + secrets.token_hex(5)
    identity = {"application": application, "role": role, "instance": "probe:9091"}
    peer = {"application": "payment", "role": "api", "instance": "peer:9091"}

    def row(rows, expected=identity):
        matches = [item for item in rows if all(item["metric"].get(k) == v for k, v in expected.items())]
        assert len(matches) == 1, (expected, rows)
        value = float(matches[0]["value"][1])
        assert math.isfinite(value), matches
        return value

    def rejects(action):
        try:
            action()
        except AssertionError:
            return
        raise AssertionError("missing or unrelated DB pool series passed acceptance")

    with owned_settings(project) as directory:
        password = secrets.token_urlsafe(24)
        (directory / "prometheus.yml").write_text(
            "global:\n  scrape_interval: 1s\n"
            "scrape_configs:\n  - job_name: commerce-settle\n    static_configs:\n"
            f"      - targets: [host.docker.internal:{port}]\n"
            "        labels: {instance: 'probe:9091', __metrics_path__: /metrics}\n"
            f"      - targets: [host.docker.internal:{port}]\n"
            "        labels: {instance: 'peer:9091', __metrics_path__: /peer}\n"
            f"      - targets: [host.docker.internal:{port}]\n"
            "        labels: {instance: 'stale:9091', __metrics_path__: /stale-metrics}\n")
        compose = {"services": {
            "prometheus": {"image": "prom/prometheus:v3.2.1", "ports": ["127.0.0.1::9090"],
                "extra_hosts": ["host.docker.internal:host-gateway"],
                "volumes": [f"{directory}/prometheus.yml:/etc/prometheus/prometheus.yml:ro"]},
            "grafana": {"image": "grafana/grafana:11.6.0", "ports": ["127.0.0.1::3000"],
                "environment": {"GF_SECURITY_ADMIN_PASSWORD": password, "GF_USERS_ALLOW_SIGN_UP": "false"},
                "volumes": [f"{ROOT}/infra/grafana/provisioning:/etc/grafana/provisioning:ro",
                            f"{ROOT}/infra/grafana/dashboards:/var/lib/grafana/dashboards:ro"]}}}
        path = directory / "compose.json"
        path.write_text(json.dumps(compose))
        command = ["docker", "compose", "-p", project, "-f", str(path)]

        def cli(*arguments, output=False):
            return subprocess.run(command + list(arguments), check=True, text=True,
                                  stdout=subprocess.PIPE if output else None, timeout=120).stdout

        try:
            cli("up", "-d", "--wait", "--wait-timeout", "90")
            grafana = "http://" + cli("port", "grafana", "3000", output=True).strip()
            auth = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
            definition = until(lambda: http(grafana + "/api/dashboards/uid/commerce-settle-payment-worker", authorization=auth),
                               "financial dashboard was not provisioned")["dashboard"]
            proxy = grafana + "/api/datasources/proxy/uid/prometheus/api/v1/query?"

            def query(expression):
                expression = expression.replace("${window}", "60").replace("$comparison_offset", "1h")
                response = http(proxy + urlencode({"query": expression}), authorization=auth)
                assert response["status"] == "success", response
                return response["data"]["result"]

            until(lambda: len(query('up{job="commerce-settle"} == 1')) == 3,
                  "real main/peer/stale Spring metrics were not all scraped")
            exposed = query('{__name__=~"hikaricp_connections_(active|max|pending|acquire_seconds_count|acquire_seconds_sum)"}')
            print("Actual Hikari exposition: " + json.dumps([x["metric"] for x in exposed]))
            assert {x["metric"]["__name__"] for x in exposed} == {
                "hikaricp_connections_active", "hikaricp_connections_max", "hikaricp_connections_pending",
                "hikaricp_connections_acquire_seconds_count", "hikaricp_connections_acquire_seconds_sum"}
            http(probe + "/sql", {})
            with urlopen(probe + "/metrics", timeout=5) as response:
                raw = response.read().decode()
            count_before = sum(float(line.rsplit(" ", 1)[1]) for line in raw.splitlines()
                               if line.startswith("jdbc_query_seconds_count{"))
            count_expression = f'sum(jdbc_query_seconds_count{{application="{application}",role="{role}",instance="probe:9091"}})'
            until(lambda: query(count_expression) and float(query(count_expression)[0]["value"][1]) == count_before,
                  "the completed SQL metric snapshot was not scraped")
            acquire_expression = ('rate(hikaricp_connections_acquire_seconds_sum[60s]) / '
                                  'rate(hikaricp_connections_acquire_seconds_count[60s])')
            until(lambda: query(acquire_expression), "connection timer has fewer than two scrapes")
            assert row(query(acquire_expression)) < 0.2, "SQL execution was counted as pool acquisition"
            http(probe + "/pool", {})
            until(lambda: row(query("hikaricp_connections_pending")) == 2, "real Hikari pool was not saturated")
            print("Actual pool saturation: " + json.dumps({"application": application, "role": role,
                  "active": row(query("hikaricp_connections_active")), "pending": row(query("hikaricp_connections_pending"))}))
            targets = [(panel, target) for panel in definition["panels"] for target in panel.get("targets", [])]

            def target(metric):
                found = [(panel, item) for panel, item in targets if metric in item.get("expr", "")]
                assert len(found) == 1, ("required pool metric not uniquely queryable", metric, found)
                return found[0]

            pool = {name: target(metric) for name, metric in {
                "active": "hikaricp_connections_active", "limit": "hikaricp_connections_max",
                "pending": "hikaricp_connections_pending", "acquire": "hikaricp_connections_acquire_seconds_sum"}.items()}
            assert pool["acquire"][0]["fieldConfig"]["defaults"]["unit"] == "s"
            def values(name):
                return query(pool[name][1]["expr"])
            required = ["jdbc_query_seconds_sum", "jdbc_query_seconds_count", "jdbc_query_seconds_bucket",
                        "async_queue_wait_seconds_sum", "async_queue_wait_seconds_count", "async_queue_wait_seconds_bucket"]

            def required_rows(metric):
                labels = ','.join(f'{key}="{value}"' for key, value in identity.items())
                labels += ',stage="unscoped"' if metric.startswith("jdbc_") else ',kind="initial",queue="payment.inbox.process"'
                if metric.endswith("_bucket"):
                    labels += ',le="+Inf"'
                selector = metric + "{" + labels + "}"
                return query(f'({selector} and on (job,instance) (up == 1)) and (time()-timestamp({selector}) >= 0) and (time()-timestamp({selector}) <= 45)')

            def healthy():
                for metric in required:
                    rows = required_rows(metric)
                    assert rows and all(math.isfinite(float(item["value"][1])) for item in rows), (metric, rows)
                return {name: row(values(name)) for name in pool}

            sql = next(panel for panel in definition["panels"] if panel["id"] == 17)
            sql_mean = next(item["expr"] for item in sql["targets"] if "jdbc_query_seconds_sum" in item["expr"] and "offset" not in item["expr"])
            sql_p95 = next(item["expr"] for item in sql["targets"] if "histogram_quantile(0.95" in item["expr"] and "offset" not in item["expr"])
            for expression in (sql_mean, sql_p95):
                until(lambda: any(float(x["value"][1]) >= 0.1 for x in query(expression)
                                  if all(x["metric"].get(k) == v for k, v in identity.items())), "real slow SQL mean/p95 was not visible")
            until(lambda: row(values("pending")) == 2, "actual Hikari waiters were not visible")
            saturated = healthy()
            assert saturated["active"] == saturated["limit"] == 2, saturated
            assert row(values("pending"), peer) == 0
            assert row(values("active"), peer) < row(values("limit"), peer)
            assert float(query(count_expression)[0]["value"][1]) == count_before, "waiting checkout executed SQL"
            # Keep contention until at least two actual scrapes see it; no injected timer values.
            first = query('timestamp(hikaricp_connections_pending{instance="probe:9091"})')[0]["value"][1]
            until(lambda: float(query('timestamp(hikaricp_connections_pending{instance="probe:9091"})')[0]["value"][1]) >= float(first) + 2,
                  "contention did not span successive scrapes")
            http(probe + "/release", {})
            until(lambda: row(values("pending")) == 0 and row(values("acquire")) >= 0.2,
                  "completed acquisition delay was not visible")
            released = healthy()
            waiting = next(panel for panel in definition["panels"] if panel["id"] == 13)
            queue_expr = next(item["expr"] for item in waiting["targets"] if "offset" not in item["expr"])
            until(lambda: any(float(x["value"][1]) > 0.2 and x["metric"].get("application") == application for x in query(queue_expr)),
                  "subsequent real async queue delay was not visible")
            for number in (12, 18):
                for item in next(panel for panel in definition["panels"] if panel["id"] == number)["targets"]:
                    assert not query(item["expr"]), "low-sample p99 was displayed"
            rejects(lambda: row(query('hikaricp_connections_active{application="payment",role="api"}')))
            for missing, affected in (("active", "active"), ("max", "limit"), ("pending", "pending"),
                                      ("acquire_seconds_sum", "acquire"), ("acquire_seconds_count", "acquire")):
                http(probe + "/missing?metric=hikaricp_connections_" + missing, {})
                until(lambda: not [x for x in values(affected) if x["metric"].get("instance") == identity["instance"]],
                      "missing required " + missing + " series was filled or retained")
                rejects(healthy)
                http(probe + "/healthy", {})
                until(lambda: bool([x for x in values(affected) if x["metric"].get("instance") == identity["instance"]]),
                      "required " + missing + " series did not recover")
            for missing in required:
                http(probe + "/missing?metric=" + missing, {})
                until(lambda: not required_rows(missing), "missing required SQL/async series was retained")
                rejects(healthy)
                http(probe + "/healthy", {})
                until(lambda: bool(required_rows(missing)), "required SQL/async series did not recover")
            http(probe + "/down", {})
            until(lambda: query('up{instance="probe:9091"} == 0'), "failed scrape was not observed")
            until(lambda: all(not [x for x in values(name) if x["metric"].get("instance") == identity["instance"]] for name in pool),
                  "failed scrape passed pool guards")
            rejects(healthy)
            stale = {**identity, "instance": "stale:9091"}
            assert query('up{instance="stale:9091"} == 1'), "stale target itself must be UP"
            # Its raw gauges really exist and are old; this is separate from a failed scrape.
            assert row(query('time()-timestamp(hikaricp_connections_active)'), stale) >= 45
            assert row(query('hikaricp_connections_max'), stale) == 2
            assert all(not [x for x in values(name) if x["metric"].get("instance") == stale["instance"]] for name in pool), "old raw samples passed freshness guards"
            rejects(lambda: row(values("active"), stale))
            print("PASS actual JDBC dashboard acceptance " + json.dumps({"application": application, "role": role,
                  "saturated": saturated, "released": released, "missing_rejected": True, "sql_async_missing_rejected": True, "peer_role_rejected": True,
                  "down_rejected": True, "stale_rejected": True, "low_sample_p99_hidden": True}))
        finally:
            try:
                http(probe + "/healthy", {})
                http(probe + "/release", {})
            finally:
                cli("down", "-v", "--remove-orphans")
                assert not cli("ps", "-aq", output=True).strip(), "owned JDBC test containers remain"
                (directory / "cleanup-complete").touch()


def verify():
    verify_dashboard_quantiles()
    verify_payout_trace_guards()
    project = "cs-quarantine-observation-" + secrets.token_hex(5)
    with owned_settings(project) as directory:
        password = secrets.token_urlsafe(24)
        production = (ROOT / "infra/alertmanager/alertmanager.yml").read_text()
        test_alerts = production.replace("receiver: local-ui", "receiver: test-receiver")
        test_alerts = test_alerts.replace("group_wait: 30s", "group_wait: 2s")
        test_alerts = test_alerts.replace("group_interval: 5m", "group_interval: 5s")
        test_alerts = test_alerts.replace(
            "  - name: local-ui",
            "  - name: test-receiver\n    webhook_configs:\n      - url: http://fixture:8000/alerts\n        send_resolved: true",
        )
        (directory / "alertmanager.yml").write_text(test_alerts)
        (directory / "slack-webhook").write_text("https://example.invalid/unused-test-only")
        template_data = {"Alerts": [{"Labels": {"service": "payment-worker", "instance": "worker:9091"},
                                    "Annotations": {"summary": "memory pressure", "runbook_url": "https://example.invalid/runbook"}}]}
        for application, method, uri in (("gateway", "POST", "/api/orders"), ("commerce", "GET", "/api/products")):
            template_data["Alerts"].append({"Labels": {"application": application, "method": method, "uri": uri},
                                           "Annotations": {"summary": "HTTP errors", "runbook_url": "https://example.invalid/http"}})
        (directory / "template-data.json").write_text(json.dumps(template_data))
        (directory / "empty.tmpl").write_text("")
        (directory / "integration.rules.yml").write_text(integration_rules(
            (ROOT / "infra/prometheus/quarantine.rules.yml").read_text(),
        ))
        (directory / "notification.integration.rules.yml").write_text(integration_rules(
            (ROOT / "infra/prometheus/notification.rules.yml").read_text(),
        ))
        financial = (ROOT / "infra/prometheus/financial.rules.yml").read_text()
        selected = [block for block in re.findall(r"(?ms)^      - (?:record|alert):.*?(?=^      - |^  - name:|\Z)", financial)
                    if re.match(r"      - (?:record: financial:operation_(?:current|age|records)|alert: Financial(?:Unknown|RefundUnknown)|record: business:(?:source|cohort_source|cohort_capture_complete|cohort_cutoff|cohort_snapshot)|alert: Business(?:ObservationUnavailable|CohortObservationUnavailable|CohortPopulationUnverified))\n", block)]
        assert len(selected) == 14
        runtime = (ROOT / "infra/prometheus/runtime.rules.yml").read_text()
        (directory / "runtime.integration.rules.yml").write_text(integration_rules(re.sub(r"(?m)^(\s+)for: [^\n]+$", r"\1for: 2s", runtime)))
        (directory / "financial.integration.rules.yml").write_text(integration_rules(
            re.sub(r"(?m)^(\s+)for: [^\n]+$", r"\1for: 2s",
                "groups:\n  - name: financial-transport\n    interval: 15s\n    rules:\n" + "".join(selected))))
        (directory / "prometheus.yml").write_text(
            "global:\n  scrape_interval: 1s\n  evaluation_interval: 1s\n"
            "rule_files: [/etc/prometheus/integration.rules.yml, /etc/prometheus/notification.integration.rules.yml, /etc/prometheus/runtime.integration.rules.yml, /etc/prometheus/financial.integration.rules.yml]\n"
            "alerting:\n  alertmanagers:\n    - static_configs:\n        - targets: [alertmanager:9093]\n"
            "scrape_configs:\n  - job_name: commerce-settle\n    static_configs:\n      - targets: [fixture:8000]\n"
            "      - targets: [fixture:8000]\n"
            "        labels: {instance: 'payment-api:9091', __metrics_path__: /metrics/payment-api}\n"
            "      - targets: [fixture:8000]\n"
            "        labels: {instance: 'payment-worker:9091', __metrics_path__: /metrics/payment-worker}\n"
            "      - targets: [fixture:8000]\n"
            "        labels: {instance: 'settlement:9091', __metrics_path__: /metrics/settlement}\n"
        )

        def mount(source, target):
            return f"{source}:{target}:ro"

        compose = {
            "services": {
                "fixture": {
                    "image": "python:3.13.7-alpine",
                    "command": ["python", "-B", "/fixture.py"],
                    "volumes": [mount(ROOT / "tests/observability/fixture.py", "/fixture.py")],
                    "ports": ["127.0.0.1::8000"],
                },
                "prometheus": {
                    "image": "prom/prometheus:v3.2.1",
                    "volumes": [
                        mount(directory / "prometheus.yml", "/etc/prometheus/prometheus.yml"),
                        mount(ROOT / "infra/prometheus/prometheus.yml", "/etc/prometheus/production.yml"),
                        mount(ROOT / "infra/prometheus/quarantine.rules.yml", "/etc/prometheus/quarantine.rules.yml"),
                        mount(ROOT / "infra/prometheus/financial.rules.yml", "/etc/prometheus/financial.rules.yml"),
                        mount(ROOT / "infra/prometheus/runtime.rules.yml", "/etc/prometheus/runtime.rules.yml"),
                        mount(ROOT / "infra/prometheus/runtime.rules.test.yml", "/etc/prometheus/runtime.rules.test.yml"),
                        mount(directory / "runtime.integration.rules.yml", "/etc/prometheus/runtime.integration.rules.yml"),
                        mount(directory / "financial.integration.rules.yml", "/etc/prometheus/financial.integration.rules.yml"),
                        mount(ROOT / "infra/prometheus/financial.rules.test.yml", "/etc/prometheus/financial.rules.test.yml"),
                        mount(directory / "integration.rules.yml", "/etc/prometheus/integration.rules.yml"),
                        mount(directory / "notification.integration.rules.yml", "/etc/prometheus/notification.integration.rules.yml"),
                        mount(ROOT / "infra/prometheus/quarantine.rules.test.yml", "/etc/prometheus/quarantine.rules.test.yml"),
                        mount(ROOT / "infra/prometheus/notification.rules.yml", "/etc/prometheus/notification.rules.yml"),
                        mount(ROOT / "infra/prometheus/notification.rules.test.yml", "/etc/prometheus/notification.rules.test.yml"),
                    ],
                    "ports": ["127.0.0.1::9090"],
                },
                "alertmanager": {
                    "image": "prom/alertmanager:v0.28.1",
                    "command": ["--config.file=/etc/alertmanager/alertmanager.yml", "--cluster.listen-address="],
                    "volumes": [
                        mount(directory / "alertmanager.yml", "/etc/alertmanager/alertmanager.yml"),
                        mount(ROOT / "infra/alertmanager/alertmanager.yml", "/etc/alertmanager/base.yml"),
                        mount(ROOT / "infra/alertmanager/alertmanager-slack.yml", "/etc/alertmanager/slack.yml"),
                        mount(directory / "slack-webhook", "/run/secrets/slack_webhook_url"),
                        mount(directory / "template-data.json", "/etc/alertmanager/template-data.json"),
                        mount(directory / "empty.tmpl", "/etc/alertmanager/empty.tmpl"),
                    ],
                    "ports": ["127.0.0.1::9093"],
                },
                "grafana": {
                    "image": "grafana/grafana:11.6.0",
                    "environment": {"GF_SECURITY_ADMIN_PASSWORD": password, "GF_USERS_ALLOW_SIGN_UP": "false"},
                    "volumes": [
                        mount(ROOT / "infra/grafana/provisioning", "/etc/grafana/provisioning"),
                        mount(ROOT / "infra/grafana/dashboards", "/var/lib/grafana/dashboards"),
                    ],
                    "ports": ["127.0.0.1::3000"],
                },
            }
        }
        for service in compose["services"].values():
            service["mem_limit"] = "256m"
        path = directory / "compose.json"
        path.write_text(json.dumps(compose))
        command = ["docker", "compose", "--project-name", project, "-f", str(path)]

        def cli(*arguments, output=False):
            result = subprocess.run(
                command + list(arguments), check=True, text=True,
                stdout=subprocess.PIPE if output else None, timeout=180,
            )
            return result.stdout if output else None

        def endpoint(service, port):
            return "http://" + cli("port", service, str(port), output=True).strip()

        try:
            cli("run", "--rm", "--no-deps", "--workdir", "/etc/prometheus", "--entrypoint", "/bin/promtool", "prometheus", "check", "config", "/etc/prometheus/production.yml")
            cli("run", "--rm", "--no-deps", "--workdir", "/etc/prometheus", "--entrypoint", "/bin/promtool", "prometheus", "test", "rules", "/etc/prometheus/quarantine.rules.test.yml")
            cli("run", "--rm", "--no-deps", "--workdir", "/etc/prometheus", "--entrypoint", "/bin/promtool", "prometheus", "test", "rules", "/etc/prometheus/notification.rules.test.yml")
            cli("run", "--rm", "--no-deps", "--workdir", "/etc/prometheus", "--entrypoint", "/bin/promtool", "prometheus", "test", "rules", "/etc/prometheus/financial.rules.test.yml")
            cli("run", "--rm", "--no-deps", "--workdir", "/etc/prometheus", "--entrypoint", "/bin/promtool", "prometheus", "test", "rules", "/etc/prometheus/runtime.rules.test.yml")
            cli("run", "--rm", "--no-deps", "--entrypoint", "/bin/amtool", "alertmanager", "check-config", "/etc/alertmanager/base.yml", "/etc/alertmanager/slack.yml")
            slack = (ROOT / "infra/alertmanager/alertmanager-slack.yml").read_text()
            template = re.search(r"(?m)^        text: '(.*)'$", slack).group(1).replace("''", "'")
            rendered = cli("run", "--rm", "--no-deps", "--entrypoint", "/bin/amtool", "alertmanager", "template", "render",
                           "--template.glob=/etc/alertmanager/empty.tmpl", "--template.text=" + template,
                           "--template.data=/etc/alertmanager/template-data.json", output=True)
            assert all(value in rendered for value in ("payment-worker", "worker:9091", "https://example.invalid/runbook")), rendered
            normalized = " ".join(rendered.split())
            assert all(value in normalized for value in ("gateway POST /api/orders", "commerce GET /api/products")), rendered
            cli("up", "-d", "--wait", "--wait-timeout", "120")
            fixture = endpoint("fixture", 8000)
            prometheus = endpoint("prometheus", 9090)
            alertmanager = endpoint("alertmanager", 9093)
            grafana = endpoint("grafana", 3000)
            authorization = "Basic " + base64.b64encode(("admin:" + password).encode()).decode()
            dashboard = until(
                lambda: http(grafana + "/api/dashboards/uid/payment-quarantine", authorization=authorization),
                "quarantine dashboard was not provisioned",
            )
            assert len(dashboard["dashboard"]["panels"]) == 8
            for uid in ['commerce-settle-business-performance', 'commerce-settle-payment-worker', 'commerce-settle-overview']:
                definition = http(grafana + "/api/dashboards/uid/" + uid, authorization=authorization)
                assert definition["dashboard"]["panels"], uid
            sources = http(grafana + "/api/datasources", authorization=authorization)
            source = next(item for item in sources if item["type"] == "prometheus")
            proxy_url = grafana + "/api/datasources/proxy/uid/" + source["uid"]
            until(lambda: http(proxy_url + "/api/v1/query?query=commerce_settle_runtime_observed_timestamp_seconds", authorization=authorization)["data"]["result"], "Grafana datasource did not read live Prometheus")
            def query(expression):
                return http(proxy_url + "/api/v1/query?" + urlencode({"query": expression}), authorization=authorization)["data"]["result"]
            until(lambda: query("commerce_settle_docker_host_cpu_usage_ratio"), "host fixture was not scraped")
            verify_runtime_dashboards(query)
            definitions = {uid: http(grafana + "/api/dashboards/uid/" + uid, authorization=authorization)["dashboard"]
                           for uid in ("payment-quarantine", "settlement-quarantine")}
            verify_quarantine_dashboards(query, definitions, fixture)
            assert all(target.get("instant") and not target.get("range") for panel in dashboard["dashboard"]["panels"] if panel["type"] == "stat" for target in panel["targets"])
            until(lambda: http(prometheus + "/api/v1/query?query=payment:quarantine_originals:max")["data"]["result"], "metrics were not scraped and recorded")
            until(lambda: http(alertmanager + "/api/v2/status"), "Alertmanager was not ready")
            assert not http(alertmanager + "/api/v2/alerts")
            http(fixture + "/state", {"active": True})
            expected_alerts = {
                "PaymentQuarantineCaptured", "PaymentQuarantineReviewRequired", "PaymentQuarantineDeliveryOverdue",
                "NotificationDeliveryOverdue", "NotificationReviewRequired", "NotificationRetryExhausted",
                "NotificationFinancialActionRequired", "KakaoReauthenticationRequired",
                "FinancialUnknown", "FinancialRefundUnknown", "RuntimeEventCoverageGap", "ContainerMemoryPressure", "ContainerMemoryCritical", "ContainerOom", "ContainerAbnormalExit",
                "BusinessObservationUnavailable", "BusinessCohortObservationUnavailable", "BusinessCohortPopulationUnverified",
            }

            def firing():
                alerts = [a for notification in http(fixture + "/notifications") if notification["status"] == "firing" for a in notification["alerts"]]
                names = {a["labels"]["alertname"] for a in alerts}
                required_scopes = {("critical", "payment-worker"), ("warning", "settlement")}
                scopes = {(a["labels"]["severity"], a["labels"].get("service")) for a in alerts if a["labels"]["alertname"] in {"ContainerMemoryPressure", "ContainerMemoryCritical"}}
                return alerts if expected_alerts <= names and required_scopes <= scopes else None

            delivered = until(firing, "firing notifications were not delivered")
            assert all(a["labels"]["severity"] == "critical" or
                       (a["labels"]["alertname"] == "ContainerMemoryPressure" and a["labels"].get("service") == "settlement") or
                       a["labels"]["alertname"] in {"FinancialRefundUnknown", "RuntimeEventCoverageGap", "BusinessCohortPopulationUnverified"} for a in delivered)
            assert not any(a["labels"]["alertname"] == "ContainerMemoryPressure" and a["labels"]["severity"] == "warning" and a["labels"].get("service") == "payment-worker" for a in delivered)
            assert all(not ({"buyerId", "sellerId", "orderId", "recipientId", "token", "body"} & set(a["labels"])) for a in delivered)
            http(fixture + "/state", {"active": False})
            def resolved():
                names = {
                    a["labels"]["alertname"]
                    for notification in http(fixture + "/notifications")
                    if notification["status"] == "resolved"
                    for a in notification["alerts"]
                    if a["status"] == "resolved"
                }
                memory_scopes = {(a["labels"]["severity"], a["labels"].get("service"))
                                 for notification in http(fixture + "/notifications") if notification["status"] == "resolved"
                                 for a in notification["alerts"] if a["status"] == "resolved" and a["labels"]["alertname"] in {"ContainerMemoryPressure", "ContainerMemoryCritical"}}
                return expected_alerts <= names and {("critical", "payment-worker"), ("warning", "settlement")} <= memory_scopes

            until(resolved, "resolved notifications were not delivered for all firing alerts")
            print("PASS observability: rule boundaries, live dashboard series and cohort separation, rendered Slack identity, service-scoped firing/inhibition/resolution")
        except BaseException:
            for name, suffix in [("prometheus", "/api/v1/targets"), ("alertmanager", "/api/v2/alerts"), ("fixture", "/notifications")]:
                url = locals().get(name)
                if url:
                    try:
                        print(name + " diagnostic: " + json.dumps(http(url + suffix)))
                    except (URLError, OSError, ValueError):
                        pass
            raise
        finally:
            cli("down", "-v", "--remove-orphans")
            assert not cli("ps", "-aq", output=True).strip(), "owned test containers remain"
            (directory / "cleanup-complete").touch()


if __name__ == "__main__":
    def stop(*_args):
        raise SystemExit(143)

    signal.signal(signal.SIGTERM, stop)
    import sys
    if len(sys.argv) > 1:
        assert len(sys.argv) == 5 and sys.argv[1] == "--jdbc-probe", sys.argv[1:]
        verify_jdbc_probe(int(sys.argv[2]), sys.argv[3], sys.argv[4])
    else:
        verify()
