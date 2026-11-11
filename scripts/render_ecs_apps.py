"""Render initial EC2 app tasks without reading local secrets or changing AWS."""

import argparse
import ipaddress
import json
import re
import subprocess

from render_ecs_data import compose_services


MODULES = ("gateway", "commerce", "payment", "settlement", "finance-mock")
SERVICES = ("gateway", "commerce", "payment-api", "payment-worker", "settlement", "finance-mock")
PORTS = {name: (8080 + index, 9091 + index) for index, name in enumerate(SERVICES)}
IMAGE = re.compile(
    r"([0-9]{12})\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com/"
    r"([a-z0-9]+(?:[._/-][a-z0-9]+)*)@sha256:([0-9a-f]{64})"
)
VARIABLE = re.compile(r"\$\{([A-Z0-9_]+)(?::([?-])(.*))?\}")


def module_for(service):
    return "payment" if service.startswith("payment-") else service


def private_ipv4(value):
    address = ipaddress.IPv4Address(value)
    if not any(address in ipaddress.IPv4Network(cidr)
               for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")):
        raise ValueError("host addresses must be RFC1918 IPv4")
    return str(address)


def image_identity(image):
    match = IMAGE.fullmatch(image)
    if not match:
        raise ValueError("image must be an ECR URI with an immutable sha256 digest")
    return match.group(1, 2, 3)


def render(service, services, image, region, role, parameter_prefix, app_ip, data_ip):
    if service not in SERVICES:
        raise ValueError("unknown app service")
    app_ip, data_ip = private_ipv4(app_ip), private_ipv4(data_ip)
    if app_ip == data_ip:
        raise ValueError("app and data hosts must differ")
    account, image_region, _ = image_identity(image)
    if image_region != region:
        raise ValueError("image and selected region differ")
    if not re.fullmatch(rf"arn:aws:iam::{account}:role/[A-Za-z0-9_+=,.@/-]+", role):
        raise ValueError("execution role and image account differ")
    if not re.fullmatch(
        rf"arn:aws:ssm:{re.escape(region)}:{account}:parameter/([A-Za-z0-9_.-]+/)+",
        parameter_prefix,
    ):
        raise ValueError("parameter prefix must use the selected account/region and end in /")
    source = services[service]
    environment, secrets = {}, {}
    for name, value in source["environment"].items():
        value = str(value)
        if name == "SPRING_APPLICATION_JSON":
            # The complete sender map stays in SSM rather than assembling secret JSON.
            secrets[name] = parameter_prefix + service + "/" + name
            continue
        variable = VARIABLE.fullmatch(value)
        if variable:
            key, operator, default = variable.groups()
            if operator == "?" or key == "DEV_FIXTURE_PASSWORD" or key.endswith("_CREDENTIAL"):
                secrets[name] = parameter_prefix + key
            elif operator == "-":
                if default:
                    environment[name] = default
            else:
                raise ValueError("unresolved app environment variable: " + name)
        elif "$" + "{" in value:
            raise ValueError("unsupported embedded app environment variable: " + name)
        else:
            environment[name] = value
    environment.update({
        "KAFKA_BOOTSTRAP_SERVERS": data_ip + ":9092",
        "SPRING_DATA_REDIS_HOST": data_ip,
        "MANAGEMENT_ZIPKIN_TRACING_ENDPOINT": f"http://{data_ip}:9411/api/v2/spans",
    })
    if "DB_URL" in environment:
        environment["DB_URL"] = environment["DB_URL"].replace(
            "//postgres:5432/", f"//{data_ip}:5432/"
        )
    peers = {
        "COMMERCE_URL": "commerce", "PAYMENT_URL": "payment-api",
        "PAYMENT_API_URL": "payment-api", "SETTLEMENT_URL": "settlement",
        "FINANCE_MOCK_URL": "finance-mock",
    }
    for name, peer in peers.items():
        environment[name] = f"http://{app_ip}:{PORTS[peer][0]}"
    if service == "commerce":
        environment["APP_ORDER_ADMISSION_PROMETHEUS_URL"] = f"http://{data_ip}:9090"
    health = source["healthcheck"]
    container = {
        "name": service, "image": image, "essential": True, "memory": 512,
        "environment": [{"name": name, "value": value}
                        for name, value in sorted(environment.items())],
        "secrets": [{"name": name, "valueFrom": value}
                    for name, value in sorted(secrets.items())],
        "portMappings": [
            {"containerPort": 8080, "hostPort": PORTS[service][0], "protocol": "tcp"},
            {"containerPort": 9091, "hostPort": PORTS[service][1], "protocol": "tcp"},
        ],
        "healthCheck": {
            "command": health["test"], "interval": 10, "timeout": 5,
            "retries": 10, "startPeriod": 120,
        },
        "stopTimeout": 60,
        "logConfiguration": {"logDriver": "awslogs", "options": {
            "awslogs-group": "/ecs/commerce-settle/" + service,
            "awslogs-region": region, "awslogs-stream-prefix": "ecs",
        }},
    }
    return {
        "family": "commerce-settle-" + service, "networkMode": "bridge",
        "requiresCompatibilities": ["EC2"], "executionRoleArn": role,
        "runtimePlatform": {"cpuArchitecture": "ARM64", "operatingSystemFamily": "LINUX"},
        "placementConstraints": [{"type": "memberOf", "expression": "attribute:workload == app"}],
        "containerDefinitions": [container],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service", choices=SERVICES)
    for name in ("image", "region", "execution-role", "parameter-prefix",
                 "app-private-ip", "data-private-ip"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        task = render(args.service, compose_services(), args.image, args.region,
                      args.execution_role, args.parameter_prefix,
                      args.app_private_ip, args.data_private_ip)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.error(str(error))
    print(json.dumps(task, indent=2))


if __name__ == "__main__":
    main()
