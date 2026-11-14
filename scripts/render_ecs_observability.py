"""Render private, two-host runtime observation from the existing Compose images and rules."""
import argparse
import copy
import json
from pathlib import Path
import re

from render_ecs_apps import PORTS, SERVICES, private_ipv4
from render_ecs_data import compose_services

DATA_SERVICES = ("postgres", "kafka", "redis", "prometheus", "grafana", "alertmanager", "zipkin", "kafka-ui",
                 "data-storage-metrics", "docker-metrics-proxy", "container-metrics")
ROOT = Path(__file__).resolve().parents[1]


def validate(cluster, app_ip, data_ip, project):
    if not re.fullmatch(r"arn:aws:ecs:[a-z0-9-]+:[0-9]{12}:cluster/[A-Za-z0-9_-]+", cluster):
        raise ValueError("Select the complete ECS cluster ARN")
    app_ip, data_ip = private_ipv4(app_ip), private_ipv4(data_ip)
    if app_ip == data_ip:
        raise ValueError("App and data hosts must differ")
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", project):
        raise ValueError("Select the runtime observers' Compose project")
    return app_ip, data_ip


def runtime(role, cluster, app_ip, data_ip, project, source, persistent=False):
    app_ip, data_ip = validate(cluster, app_ip, data_ip, project)
    required = (*SERVICES, "caddy", "docker-metrics-proxy", "container-metrics") if role == "app" else DATA_SERVICES
    if persistent and role == "data":
        required = (*required, "zipkin-store")
    base = {"restart": "unless-stopped", "read_only": True, "cap_drop": ["ALL"],
            "security_opt": ["no-new-privileges:true"], "mem_limit": "64m", "cpus": .25,
            "volumes": ["/srv/commerce-settle/observability/runtime_metrics.py:/runtime_metrics.py:ro"]}
    proxy = {**copy.deepcopy(base), "image": source["docker-metrics-proxy"]["image"],
             "command": ["python", "-B", "/runtime_metrics.py", "--proxy"],
             "environment": {"OBSERVER_ECS_CLUSTER": cluster, "OBSERVER_PROJECT": project,
                             "OBSERVER_SERVICES": ",".join(required)},
             "networks": ["runtime-observation"]}
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
    config = config.replace("targets: [alertmanager:9093]", "targets: [" + data_ip + ":9093]")
    app_targets = [{"targets": [app_ip + ":" + str(PORTS[name][1])],
                "labels": {"instance": name + ":9091", "service": name,
                           "target_address": app_ip + ":" + str(PORTS[name][1])}}
                for name in SERVICES]
    for name, targets in (("commerce-settle", app_targets),
                          ("data-storage", [{"targets": [data_ip + ":9108"]}]),
                          ("container-runtime", [{"targets": [app_ip + ":9109", data_ip + ":9109"]}]),
                          ("zipkin", [{"targets": [data_ip + ":9411"]}])):
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


def zipkin_task(cluster, role, parameter_prefix):
    """Reuse the tested native store config in the existing Zipkin EC2 task family."""
    match = re.fullmatch(r"arn:aws:ecs:([a-z0-9-]+):([0-9]{12}):cluster/[A-Za-z0-9_-]+", cluster)
    if not match:
        raise ValueError("Select the complete ECS cluster ARN")
    region, account = match.groups()
    if not re.fullmatch(rf"arn:aws:iam::{account}:role/[A-Za-z0-9_+=,.@/-]+", role or ""):
        raise ValueError("Execution role must use the selected ECS account")
    if not re.fullmatch(rf"arn:aws:ssm:{re.escape(region)}:{account}:parameter/([A-Za-z0-9_.-]+/)+", parameter_prefix or ""):
        raise ValueError("SSM prefix must use the ECS account/region and end in /")
    sources = json.loads((ROOT / "compose.zipkin-storage.yml").read_text())["services"]
    containers, volumes = [], []
    for name, source in sources.items():
        container = {"name": name, "image": source["image"], "essential": name != "zipkin-storage-init",
                     "memory": {"zipkin": 384, "zipkin-store": 1024, "zipkin-storage-init": 64}[name],
                     "cpu": 1024 if name == "zipkin-store" else 128,
                     "environment": [], "secrets": [], "mountPoints": [],
                     "stopTimeout": 60,
                     "logConfiguration": {"logDriver": "awslogs", "options": {
                         "awslogs-group": "/ecs/commerce-settle/zipkin", "awslogs-region": region,
                         "awslogs-stream-prefix": "ecs"}}}
        if "healthcheck" in source:
            health = source["healthcheck"]
            container["healthCheck"] = {"command": [part.replace("$$", "$") for part in health["test"]],
                "interval": int(health["interval"][:-1]), "timeout": int(health["timeout"][:-1]),
                "retries": 10, "startPeriod": int(health["start_period"][:-1])}
        for key, value in source["environment"].items():
            if key in ("ES_PASSWORD", "ELASTIC_PASSWORD"):
                container["secrets"].append({"name": key, "valueFrom": parameter_prefix + "ZIPKIN_STORAGE_PASSWORD"})
            else:
                container["environment"].append({"name": key, "value": value})
        for index, mount in enumerate(source.get("volumes", [])):
            identity = name + "-" + str(index)
            host = ("/srv/commerce-settle/zipkin-store/data" if name == "zipkin-store" else
                    "/srv/commerce-settle/observability/zipkin/" + Path(mount["source"]).name)
            volumes.append({"name": identity, "host": {"sourcePath": host}})
            container["mountPoints"].append({"sourceVolume": identity, "containerPath": mount["target"],
                                              "readOnly": mount.get("read_only", False)})
        if "entrypoint" in source:
            container["entryPoint"] = source["entrypoint"]
            container["command"] = [part.replace("$$", "$") for part in source["command"]]
        if name == "zipkin":
            container.update({
                "portMappings": [{"containerPort": 9411, "hostPort": 9411, "protocol": "tcp"}],
                "links": ["zipkin-store:zipkin-store"],
                "dependsOn": [{"containerName": "zipkin-storage-init", "condition": "SUCCESS"}]})
        elif name == "zipkin-storage-init":
            container.update({"links": ["zipkin-store:zipkin-store"],
                              "dependsOn": [{"containerName": "zipkin-store", "condition": "HEALTHY"}],
                              "startTimeout": 120})
        containers.append(container)
    return {"family": "commerce-settle-zipkin", "networkMode": "bridge", "requiresCompatibilities": ["EC2"],
            "executionRoleArn": role,
            "runtimePlatform": {"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
            "placementConstraints": [{"type": "memberOf", "expression": "attribute:workload == data"}],
            "containerDefinitions": containers, "volumes": volumes}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("app", "data", "prometheus", "zipkin"))
    parser.add_argument("--cluster-arn", required=True)
    parser.add_argument("--app-private-ip")
    parser.add_argument("--data-private-ip")
    parser.add_argument("--observer-project", help="Compose project for the two new runtime observers")
    parser.add_argument("--persistent-zipkin", action="store_true")
    parser.add_argument("--execution-role")
    parser.add_argument("--parameter-prefix")
    args = parser.parse_args(argv)
    values = (args.cluster_arn, args.app_private_ip, args.data_private_ip,
              args.observer_project or "commerce-runtime-" + args.kind)
    try:
        if args.kind == "zipkin":
            result = zipkin_task(args.cluster_arn, args.execution_role, args.parameter_prefix)
        elif not args.app_private_ip or not args.data_private_ip:
            raise ValueError("Both private host addresses are required")
        else:
            result = (prometheus(*values) if args.kind == "prometheus"
                      else runtime(args.kind, *values, compose_services(), args.persistent_zipkin))
        print(result if isinstance(result, str) else json.dumps(result, indent=2))
    except (ValueError, OSError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
