"""Exercise the deployment Caddy config on isolated Docker resources, without public ACME."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]


def run(*args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=120, **kwargs).stdout


def verify(directory):
    task = json.loads((ROOT / "infra/caddy/task-definition.json").read_text())
    container = task["containerDefinitions"][0]
    assert task["networkMode"] == "bridge"
    assert {(p["hostPort"], p["containerPort"], p["protocol"]) for p in container["portMappings"]} == {
        (80, 80, "tcp"), (443, 443, "tcp")}
    assert {e["name"] for e in container["environment"]} == {"PUBLIC_DOMAIN", "GATEWAY_UPSTREAM"}
    mounts = {m["containerPath"]: m for m in container["mountPoints"]}
    assert mounts["/etc/caddy"]["readOnly"] and not mounts["/data"]["readOnly"]
    assert {v["name"] for v in task["volumes"]} == {m["sourceVolume"] for m in mounts.values()}

    project = "caddy-smoke-" + uuid.uuid4().hex[:12]
    settings = directory / "compose.json"
    backend = directory / "backend.Caddyfile"
    backend.write_text(':8080, :3000, :9090, :9411, :8081 {\n log {\n output stdout\n }\n'
                       ' handle /reset {\n abort\n }\n handle {\n'
                       ' respond "{method}|{uri}|{header.X-Forwarded-Proto}|'
                       '{header.X-Forwarded-Host}|{header.Authorization}|{header.Forwarded}"\n }\n}\n')
    password_hash = run("docker", "run", "--rm", "--network", "none", container["image"],
                        "caddy", "hash-password", "--plaintext", "synthetic-monitor-password").strip()
    caddy = {
        "image": container["image"], "command": container["command"],
        "mem_limit": f"{container['memory']}m",
        "environment": {"PUBLIC_DOMAIN": "localhost", "GATEWAY_UPSTREAM": "upstream:8080",
                        "DATA_PRIVATE_IP": "upstream", "MONITORING_USER": "viewer",
                        "MONITORING_PASSWORD_HASH": password_hash.replace("$", "$$")},
        "ports": ["127.0.0.1::80", "127.0.0.1::443"],
        "volumes": [f"{ROOT / 'infra/caddy/Caddyfile'}:/etc/caddy/Caddyfile:ro",
                    f"{ROOT / 'infra/caddy/monitoring.Caddyfile'}:/etc/caddy/monitoring/tools.caddy:ro",
                    "data:/data", "config:/config"],
    }
    settings.write_text(json.dumps({"services": {
        "caddy": caddy,
        "upstream": {"image": container["image"], "command": container["command"],
                     "volumes": [f"{backend}:/etc/caddy/Caddyfile:ro"]},
    }, "volumes": {"data": {}, "config": {}}}))
    compose = ["docker", "compose", "-p", project, "-f", str(settings)]

    def cli(*args):
        return run(*compose, *args)

    def port(number):
        return cli("port", "caddy", str(number)).strip().rsplit(":", 1)[1]

    def request(path, method="GET"):
        return run("curl", "--fail-with-body", "--silent", "--show-error", "--noproxy", "*",
                   "--max-time", "5", "--cacert", str(directory / "root.crt"),
                   "-H", "Host: localhost", "-H", "Authorization: Bearer synthetic",
                   "-H", "Idempotency-Key: synthetic-reset-check",
                   "-H", "X-Forwarded-Proto: http", "-H", "Forwarded: proto=http;host=spoof.invalid",
                   "-X", method, f"https://localhost:{port(443)}{path}")

    def ready():
        deadline = time.monotonic() + 30
        while True:
            try:
                cli("cp", "caddy:/data/caddy/pki/authorities/local/root.crt", str(directory / "root.crt"))
                cli("cp", "caddy:/data/caddy/certificates/local/localhost/localhost.crt",
                    str(directory / "localhost.crt"))
                assert request("/ready") == "GET|/ready|https|localhost|Bearer synthetic|"
                return
            except (subprocess.CalledProcessError, AssertionError):
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.25)

    def monitoring_request(tool, path="/", authenticated=False, method="GET"):
        host = tool + ".localhost"
        args = ["curl", "--silent", "--show-error", "--noproxy", "*", "--max-time", "5",
                "--cacert", str(directory / "root.crt"), "--resolve",
                f"{host}:{port(443)}:127.0.0.1", "-H", "Host: " + host, "-X", method,
                "-w", "\n%{http_code}"]
        if authenticated:
            args.extend(["--user", "viewer:synthetic-monitor-password"])
        body, status = run(*args, f"https://{host}:{port(443)}{path}").rsplit("\n", 1)
        return body, int(status)

    def verify_monitoring():
        for tool in ("prometheus", "zipkin", "kafka"):
            assert monitoring_request(tool)[1] == 401, tool
            body, status = monitoring_request(tool, "/deep/link?check=1", authenticated=True)
            assert status == 200 and body == f"GET|/deep/link?check=1|https|{tool}.localhost||", (tool, body)
        assert monitoring_request("zipkin", "/api/v2/spans", authenticated=True, method="POST")[1] == 405
        # Grafana keeps its own login/API credentials instead of receiving proxy Basic credentials.
        assert monitoring_request("grafana", "/login")[1] == 200

    try:
        # Validate the public-domain path too; validation does not request a certificate.
        cli("run", "--rm", "--no-deps", "-e", "PUBLIC_DOMAIN=api.example.com", "caddy",
            "caddy", "validate", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile")
        cli("up", "-d")
        ready()
        verify_monitoring()
        root_sha = hashlib.sha256((directory / "root.crt").read_bytes()).hexdigest()
        certificate_sha = hashlib.sha256((directory / "localhost.crt").read_bytes()).hexdigest()
        for method in ("GET", "POST"):
            assert request("/api/orders?size=2", method) == (
                f"{method}|/api/orders?size=2|https|localhost|Bearer synthetic|")
        headers = run("curl", "--silent", "--show-error", "--noproxy", "*", "--max-time", "5",
                      "-D", "-", "-o", "/dev/null", "-H", "Host: localhost",
                      f"http://127.0.0.1:{port(80)}/api/orders?size=2")
        assert " 308 " in headers and "location: https://localhost/api/orders?size=2" in headers.lower()
        try:
            request("/reset", "POST")
            raise AssertionError("reset upstream must fail without replay")
        except subprocess.CalledProcessError as error:
            assert "502" in error.stderr, error.stderr
        entries = [json.loads(line) for line in cli("logs", "--no-color", "--no-log-prefix", "upstream").splitlines()
                   if line.startswith("{")]
        assert sum(entry.get("request", {}).get("uri") == "/reset" for entry in entries) == 1
        cli("stop", "upstream")
        try:
            request("/unavailable", "POST")
            raise AssertionError("unavailable upstream must fail")
        except subprocess.CalledProcessError as error:
            assert "502" in error.stderr, error.stderr
        cli("start", "upstream")
        cli("restart", "caddy")
        ready()
        verify_monitoring()
        assert hashlib.sha256((directory / "root.crt").read_bytes()).hexdigest() == root_sha
        assert hashlib.sha256((directory / "localhost.crt").read_bytes()).hexdigest() == certificate_sha
        cli("up", "-d", "--force-recreate", "caddy")
        ready()
        verify_monitoring()
        assert hashlib.sha256((directory / "root.crt").read_bytes()).hexdigest() == root_sha
        assert hashlib.sha256((directory / "localhost.crt").read_bytes()).hexdigest() == certificate_sha
        print("Caddy HTTPS, monitoring authentication/routing, private-only trace ingestion, "
              "no POST replay, 502, restart and retained certificates: PASS")
    except Exception as error:
        if isinstance(error, subprocess.CalledProcessError):
            print(error.stderr)
        print(cli("logs", "--no-color", "--tail=50"))
        raise
    finally:
        cli("down", "-v", "--remove-orphans")
        label = f"label=com.docker.compose.project={project}"
        for resource in ("container", "volume", "network"):
            flags = ["-a"] if resource == "container" else []
            assert not run("docker", resource, "ls", *flags, "-q", "--filter", label).strip(), resource


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work-dir", type=Path, help="parent for temporary verification files")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="caddy-smoke-", dir=args.work_dir) as directory:
        verify(Path(directory))


if __name__ == "__main__":
    main()
