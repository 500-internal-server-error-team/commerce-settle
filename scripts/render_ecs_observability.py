"""Render private, two-host runtime observation from the existing Compose images and rules."""
import argparse
import copy
import json
from pathlib import Path
import re

from render_ecs_apps import PORTS, SERVICES, private_ipv4
from render_ecs_data import compose_services

DATA_SERVICES = ("postgres", "kafka", "redis", "prometheus", "grafana", "alertmanager", "zipkin",
                 "data-storage-metrics", "docker-metrics-proxy", "container-metrics")
ROOT = Path(__file__).resolve().parents[1]


def validate(cluster, app_ip, data_ip, project):
    if not re.fullmatch(r"arn:aws:ecs:[a-z0-9-]+:[0-9]{12}:cluster/[A-Za-z0-9_-]+", cluster):
        raise ValueError("Select the complete ECS cluster ARN")
    app_ip, data_ip = private_ipv4(app_ip), private_ipv4(data_ip)
    if app_ip == data_ip:
        raise ValueError("App and data hosts must differ")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", project):
        raise ValueError("Select the existing data observation Compose project")
    return app_ip, data_ip


def runtime(role, cluster, app_ip, data_ip, project, source):
    app_ip, data_ip = validate(cluster, app_ip, data_ip, project)
    required = SERVICES if role == "app" else DATA_SERVICES
    base = {"restart": "unless-stopped", "read_only": True, "cap_drop": ["ALL"],
            "security_opt": ["no-new-privileges:true"], "mem_limit": "64m", "cpus": .25,
            "volumes": ["/srv/commerce-settle/observability/runtime_metrics.py:/runtime_metrics.py:ro"]}
    proxy = {**copy.deepcopy(base), "image": source["docker-metrics-proxy"]["image"],
             "command": ["python", "-B", "/runtime_metrics.py", "--proxy"],
             "environment": {"OBSERVER_ECS_CLUSTER": cluster, "OBSERVER_SERVICES": ",".join(required)},
             "networks": ["runtime-observation"]}
    if role == "data":
        proxy["environment"]["OBSERVER_PROJECT"] = project
    proxy["volumes"].append("/var/run/docker.sock:/var/run/docker.sock:ro")
    collector = {**copy.deepcopy(base), "image": source["container-metrics"]["image"],
                 "command": ["python", "-B", "/runtime_metrics.py"], "user": "65534:65534",
                 "environment": {"DOCKER_METRICS_URL": "http://docker-metrics-proxy:2375",
                                 "OBSERVER_REQUIRED_SERVICES": ",".join(required)},
                 "ports": [(app_ip if role == "app" else data_ip) + ":9109:9109"],
                 "networks": ["runtime-observation", "metrics"], "depends_on": ["docker-metrics-proxy"]}
    return {"services": {"docker-metrics-proxy": proxy, "container-metrics": collector},
            "networks": {"runtime-observation": {"internal": True}, "metrics": {}}}


def prometheus(cluster, app_ip, data_ip, project):
    app_ip, data_ip = validate(cluster, app_ip, data_ip, project)
    # Preserve the production rules, labels and Alertmanager route; change only scrape locations.
    config = (ROOT / "infra/prometheus/prometheus.yml").read_text()
    app_targets = [{"targets": [app_ip + ":" + str(PORTS[name][1])],
                "labels": {"instance": name + ":9091", "service": name,
                           "target_address": app_ip + ":" + str(PORTS[name][1])}}
                for name in SERVICES]
    for name, targets in (("commerce-settle", app_targets),
                          ("container-runtime", [{"targets": [app_ip + ":9109", data_ip + ":9109"]}])):
        def replace(match):
            block, count = re.subn(r"(?m)^    static_configs:\n(?:^ {6,}[^\n]*(?:\n|$)|^\n)+",
                "    static_configs: " + json.dumps(targets) + "\n", match.group(0))
            if count != 1:
                raise ValueError("Review the changed production scrape structure: " + name)
            return block
        config, count = re.subn(r"(?ms)^  - job_name: " + re.escape(name) + r"\n.*?(?=^  - job_name: |\Z)", replace, config)
        if count != 1:
            raise ValueError("Required production scrape job is missing or duplicated: " + name)
    return config


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("app", "data", "prometheus"))
    parser.add_argument("--cluster-arn", required=True)
    parser.add_argument("--app-private-ip", required=True)
    parser.add_argument("--data-private-ip", required=True)
    parser.add_argument("--data-compose-project", required=True)
    args = parser.parse_args(argv)
    values = (args.cluster_arn, args.app_private_ip, args.data_private_ip, args.data_compose_project)
    try:
        result = (prometheus(*values) if args.kind == "prometheus"
                  else runtime(args.kind, *values, compose_services()))
        print(result if isinstance(result, str) else json.dumps(result, indent=2))
    except (ValueError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
