"""Check production rules and an isolated metrics -> alert -> resolved notification flow."""

import base64
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
from urllib.request import Request, urlopen
from urllib.parse import urlencode


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
    for name in ("business-performance", "payment-worker"):
        dashboard = json.loads((ROOT / "infra/grafana/dashboards" / (name + ".json")).read_text())
        p99 = [target["expr"] for panel in dashboard["panels"] for target in panel.get("targets", [])
               if re.search(r"histogram_quantile\(0?\.99", target.get("expr", ""))]
        assert p99 and all(re.search(r">=\s*10000", expression) for expression in p99), name
        assert all("window" in expression for expression in p99), name


def verify_runtime_dashboards(query):
    def panels(name):
        dashboard = json.loads((ROOT / "infra/grafana/dashboards" / (name + ".json")).read_text())
        return {panel["id"]: panel for panel in dashboard["panels"]}

    host = panels("overview")[8]
    names = {row["metric"]["__name__"] for target in host["targets"] for row in query(target["expr"])}
    assert names == {"commerce_settle_docker_host_cpu_usage_ratio", "commerce_settle_docker_host_memory_available_ratio"}, names
    business = panels("business-performance")
    for number, expected in ((15, {"payout.total", "payout.ready"}), (16, {"payout.total", "payout.ready"}),
                             (19, {"payout.unapplied"}), (22, {"payout.unapplied"})):
        actual = {row["metric"]["operation"] for target in business[number]["targets"] for row in query(target["expr"])}
        assert actual == expected, (number, actual)


def verify():
    verify_dashboard_quantiles()
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
                    if re.match(r"      - (?:record: financial:operation_(?:current|age)|alert: FinancialUnknown)\n", block)]
        assert len(selected) == 4
        runtime = (ROOT / "infra/prometheus/runtime.rules.yml").read_text()
        (directory / "runtime.integration.rules.yml").write_text(integration_rules(re.sub(r"(?m)^(\s+)for: [^\n]+$", r"\1for: 2s", runtime)))
        (directory / "financial.integration.rules.yml").write_text(integration_rules(
            "groups:\n  - name: financial-transport\n    interval: 15s\n    rules:\n" + "".join(selected)))
        (directory / "prometheus.yml").write_text(
            "global:\n  scrape_interval: 1s\n  evaluation_interval: 1s\n"
            "rule_files: [/etc/prometheus/integration.rules.yml, /etc/prometheus/notification.integration.rules.yml, /etc/prometheus/runtime.integration.rules.yml, /etc/prometheus/financial.integration.rules.yml]\n"
            "alerting:\n  alertmanagers:\n    - static_configs:\n        - targets: [alertmanager:9093]\n"
            "scrape_configs:\n  - job_name: commerce-settle\n    static_configs:\n      - targets: [fixture:8000]\n"
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
            assert all(target.get("instant") and not target.get("range") for panel in dashboard["dashboard"]["panels"] if panel["type"] == "stat" for target in panel["targets"])
            until(lambda: http(prometheus + "/api/v1/query?query=payment:quarantine_originals:max")["data"]["result"], "metrics were not scraped and recorded")
            until(lambda: http(alertmanager + "/api/v2/status"), "Alertmanager was not ready")
            assert not http(alertmanager + "/api/v2/alerts")
            http(fixture + "/state", {"active": True})
            expected_alerts = {
                "PaymentQuarantineCaptured", "PaymentQuarantineReviewRequired", "PaymentQuarantineDeliveryOverdue",
                "NotificationDeliveryOverdue", "NotificationReviewRequired", "NotificationRetryExhausted",
                "NotificationFinancialActionRequired", "KakaoReauthenticationRequired",
                "FinancialUnknown", "ContainerMemoryCritical", "ContainerOom", "ContainerAbnormalExit",
            }

            def firing():
                alerts = [a for notification in http(fixture + "/notifications") if notification["status"] == "firing" for a in notification["alerts"]]
                names = {a["labels"]["alertname"] for a in alerts}
                return alerts if expected_alerts <= names else None

            delivered = until(firing, "firing notifications were not delivered")
            def memory_inhibition():
                alerts = {(a["labels"]["alertname"], a["labels"].get("service")): a for a in http(alertmanager + "/api/v2/alerts")}
                suppressed = alerts.get(("ContainerMemoryPressure", "payment-worker"), {}).get("status", {})
                other = alerts.get(("ContainerMemoryPressure", "commerce"), {}).get("status", {})
                return suppressed.get("inhibitedBy") and other.get("state") == "active" and not other.get("inhibitedBy")
            until(memory_inhibition, "memory severity inhibition did not preserve service boundaries")
            delivered = [a for n in http(fixture + "/notifications") if n["status"] == "firing" for a in n["alerts"]]
            assert all(a["labels"]["severity"] == "critical" or
                       (a["labels"]["alertname"] == "ContainerMemoryPressure" and a["labels"].get("service") == "commerce")
                       for a in delivered), delivered
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
                return expected_alerts <= names

            until(resolved, "resolved notifications were not delivered for all firing alerts")
            print("PASS observability: rule boundaries, dashboard series and cohort separation, rendered Slack identity, service-scoped inhibition and resolution")
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
    verify()
