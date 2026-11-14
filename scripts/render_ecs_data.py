"""Render the three ECS EC2 data tasks from the existing Compose configuration."""

import argparse
import ipaddress
import json
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[1]
PORTS = {"postgres": 5432, "redis": 6379, "kafka": 9092}


def capacity_profile():
    """Versioned experiment settings shared by initial rendering and tag deployment."""
    profile = json.loads((ROOT / "infra/ecs/performance.json").read_text())
    if set(profile) != {"targetConfirmedTps", "delayHeadroom", "services"}:
        raise ValueError("invalid capacity profile")
    if set(profile["services"]) != {"postgres", "commerce", "payment-api", "payment-worker",
                                    "settlement", "finance-mock"}:
        raise ValueError("invalid capacity service scope")
    for service, settings in profile["services"].items():
        if set(settings) - {"memory", "environment", "postgresSettings"}:
            raise ValueError("unsupported capacity setting")
        if "memory" in settings and (type(settings["memory"]) is not int or settings["memory"] < 1):
            raise ValueError("invalid capacity memory")
        for name, value in settings.get("environment", {}).items():
            if (not re.fullmatch(r"[A-Z][A-Z0-9_]*", name) or not isinstance(value, str)
                    or any(word in name for word in ("PASSWORD", "CREDENTIAL", "TOKEN", "SECRET"))):
                raise ValueError("capacity environment must contain only non-secret settings")
        if "postgresSettings" in settings and (
                service != "postgres" or set(settings["postgresSettings"]) != {"max_connections"}
                or not re.fullmatch(r"[1-9][0-9]*", settings["postgresSettings"]["max_connections"])):
            raise ValueError("unsupported PostgreSQL capacity setting")
    return profile


def apply_capacity(container, service, profile=None):
    settings = (profile if profile is not None else capacity_profile())["services"].get(service, {})
    overrides = settings.get("environment", {})
    if overrides.keys() & {item["name"] for item in container.get("secrets", [])}:
        raise ValueError("capacity setting conflicts with a secret reference")
    environment = {item["name"]: item["value"] for item in container.get("environment", [])}
    environment.update(overrides)
    container["environment"] = [{"name": key, "value": value} for key, value in sorted(environment.items())]
    if "memory" in settings:
        container["memory"] = settings["memory"]
    if "postgresSettings" in settings:
        command = container.get("command", ["postgres"])
        if not command or command[0] != "postgres":
            raise ValueError("PostgreSQL capacity requires the existing postgres command")
        kept, index = [], 0
        while index < len(command):
            argument = command[index]
            if (argument == "-c" and index + 1 < len(command)
                    and command[index + 1].startswith("max_connections=")):
                index += 2
                continue
            if argument == "--max_connections" and index + 1 < len(command):
                index += 2
                continue
            if argument.startswith(("--max_connections=", "-cmax_connections=")):
                index += 1
                continue
            kept.append(argument)
            index += 1
        container["command"] = kept + ["-c", "max_connections=" + settings["postgresSettings"]["max_connections"]]
    return container


def compose_services():
    result = subprocess.run(
        ["docker", "compose", "--env-file", "/dev/null", "-f", str(ROOT / "compose.yml"),
         "config", "--no-interpolate", "--format", "json"],
        cwd=ROOT, capture_output=True, text=True, check=True, timeout=30,
    )
    return json.loads(result.stdout)["services"]


def render(service, services, region, role, parameter_prefix, private_ip):
    address = ipaddress.IPv4Address(private_ip)
    if not any(address in ipaddress.IPv4Network(cidr)
               for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
        raise ValueError("data-private-ip must be an RFC1918 IPv4 address")
    role_match = re.fullmatch(r"arn:aws:iam::([0-9]{12}):role/[A-Za-z0-9_+=,.@/-]+", role)
    prefix_match = re.fullmatch(
        r"arn:aws:ssm:([a-z0-9-]+):([0-9]{12}):parameter/([A-Za-z0-9_.-]+/)+",
        parameter_prefix,
    )
    if not role_match or not prefix_match or prefix_match.groups()[:2] != (region, role_match[1]):
        raise ValueError("execution role and parameter ARN prefix must use the selected account/region")
    source = services[service]
    environment, secrets = [], []
    for name, value in source.get("environment", {}).items():
        value = str(value)
        if name.endswith("_PASSWORD"):
            if not value.startswith("${" + name + ":?"):
                raise ValueError("password must remain an unresolved Compose variable: " + name)
            secrets.append({"name": name, "valueFrom": parameter_prefix + name})
        else:
            if "${" in value:
                raise ValueError("unsupported unresolved environment variable: " + name)
            environment.append({"name": name, "value": value})
    if service == "kafka":
        overrides = {"KAFKA_ADVERTISED_LISTENERS": f"PLAINTEXT://{address}:9092",
                     "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@127.0.0.1:9093"}
        for entry in environment:
            entry["value"] = overrides.get(entry["name"], entry["value"])
    image = source.get("image")
    if service == "postgres":
        image = (ROOT / "infra/postgres/Dockerfile").read_text().splitlines()[0].split()[1]
    memory_value = str(source["mem_limit"]).lower()
    if memory_value.endswith("g"):
        memory = int(memory_value[:-1]) * 1024
    elif memory_value.endswith("m"):
        memory = int(memory_value[:-1])
    else:
        memory = int(memory_value) // (1024 * 1024)
    health = source["healthcheck"]
    container = {
        "name": service, "image": image, "essential": True, "memory": memory,
        "environment": environment, "secrets": secrets,
        "portMappings": [{"containerPort": PORTS[service], "hostPort": PORTS[service], "protocol": "tcp"}],
        "mountPoints": [], "stopTimeout": 60,
        "healthCheck": {"command": [part.replace("$$", "$") for part in health["test"]],
                        "interval": int(health["interval"].removesuffix("s")),
                        "timeout": int(health["timeout"].removesuffix("s")),
                        "retries": 10, "startPeriod": 120},
        "logConfiguration": {"logDriver": "awslogs", "options": {
            "awslogs-group": f"/ecs/commerce-settle/{service}",
            "awslogs-region": region, "awslogs-stream-prefix": "ecs"}},
    }
    if "command" in source:
        container["command"] = [part.replace("$$", "$") for part in source["command"]]
    apply_capacity(container, service)
    volumes = []

    def mount(name, host, target, read_only=False):
        volumes.append({"name": name, "host": {"sourcePath": host}})
        container["mountPoints"].append(
            {"sourceVolume": name, "containerPath": target, "readOnly": read_only})

    mount(service + "-data", f"/srv/commerce-settle/{service}/data", source["volumes"][0]["target"])
    if service == "postgres":
        mount("postgres-schema", "/srv/commerce-settle/postgres/schema", "/opt/commerce-settle/schema", True)
        mount("postgres-init", "/srv/commerce-settle/postgres/01-init.sh",
              "/docker-entrypoint-initdb.d/01-init.sh", True)
    return {
        "family": "commerce-settle-" + service, "networkMode": "bridge",
        "requiresCompatibilities": ["EC2"], "executionRoleArn": role,
        "runtimePlatform": {"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
        "placementConstraints": [{"type": "memberOf", "expression": "attribute:workload == data"}],
        "containerDefinitions": [container], "volumes": volumes,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=PORTS)
    parser.add_argument("--region", required=True)
    parser.add_argument("--execution-role", required=True)
    parser.add_argument("--parameter-prefix", required=True, help="full SSM parameter ARN prefix, ending in /")
    parser.add_argument("--data-private-ip", required=True)
    args = parser.parse_args()
    try:
        task = render(args.service, compose_services(), args.region, args.execution_role,
                      args.parameter_prefix, args.data_private_ip)
    except (ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.error(str(error))
    print(json.dumps(task, indent=2))


if __name__ == "__main__":
    main()
