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


def verify():
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
        (directory / "integration.rules.yml").write_text(integration_rules(
            (ROOT / "infra/prometheus/quarantine.rules.yml").read_text(),
        ))
        (directory / "prometheus.yml").write_text(
            "global:\n  scrape_interval: 1s\n  evaluation_interval: 1s\n"
            "rule_files: [/etc/prometheus/integration.rules.yml]\n"
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
                        mount(directory / "integration.rules.yml", "/etc/prometheus/integration.rules.yml"),
                        mount(ROOT / "infra/prometheus/quarantine.rules.test.yml", "/etc/prometheus/quarantine.rules.test.yml"),
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
            cli("run", "--rm", "--no-deps", "--entrypoint", "/bin/amtool", "alertmanager", "check-config", "/etc/alertmanager/base.yml", "/etc/alertmanager/slack.yml")
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
            assert all(target.get("instant") and not target.get("range") for panel in dashboard["dashboard"]["panels"] if panel["type"] == "stat" for target in panel["targets"])
            until(lambda: http(prometheus + "/api/v1/query?query=payment:quarantine_originals:max")["data"]["result"], "metrics were not scraped and recorded")
            until(lambda: http(alertmanager + "/api/v2/status"), "Alertmanager was not ready")
            assert not http(alertmanager + "/api/v2/alerts")
            http(fixture + "/state", {"active": True})

            def firing():
                alerts = [a for notification in http(fixture + "/notifications") if notification["status"] == "firing" for a in notification["alerts"]]
                names = {a["labels"]["alertname"] for a in alerts}
                return alerts if {"PaymentQuarantineCaptured", "PaymentQuarantineReviewRequired", "PaymentQuarantineDeliveryOverdue"} <= names else None

            delivered = until(firing, "firing notifications were not delivered")
            assert all(a["labels"]["severity"] == "critical" for a in delivered)
            http(fixture + "/state", {"active": False})
            def resolved():
                names = {
                    a["labels"]["alertname"]
                    for notification in http(fixture + "/notifications")
                    if notification["status"] == "resolved"
                    for a in notification["alerts"]
                    if a["status"] == "resolved"
                }
                return {"PaymentQuarantineCaptured", "PaymentQuarantineReviewRequired", "PaymentQuarantineDeliveryOverdue"} <= names

            until(resolved, "resolved notifications were not delivered for all firing alerts")
            print("PASS observability: rule boundaries, stale/duplicate samples, provisioned dashboard, firing/inhibition/resolution delivery")
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
