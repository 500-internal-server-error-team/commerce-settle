"""Deploy one complete image release to existing EC2 app services."""

import argparse
import copy
from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request

from render_ecs_apps import MODULES, SERVICES, image_identity, module_for


ORDER = ("finance-mock", "commerce", "payment-api", "payment-worker", "settlement", "gateway")
TASK_METADATA = (
    "taskDefinitionArn", "revision", "status", "requiresAttributes", "compatibilities",
    "registeredAt", "registeredBy", "deregisteredAt",
)


class Aws:
    def __init__(self, region):
        self.region = region

    def __call__(self, service, operation, *arguments, payload=None, timeout=None):
        command = ["aws", "--region", self.region, "--output", "json", "--no-cli-pager",
                   service, operation, *arguments]
        try:
            with ExitStack() as stack:
                if payload is not None:
                    parameters = stack.enter_context(tempfile.NamedTemporaryFile(
                        mode="w", encoding="utf-8", prefix="commerce-settle-ecs-", suffix=".json",
                    ))
                    json.dump(payload, parameters)
                    parameters.flush()
                    command += ["--cli-input-json", "file://" + parameters.name]
                result = subprocess.run(
                    command, capture_output=True, text=True, check=True,
                    timeout=timeout if timeout is not None else (660 if operation == "wait" else 60),
                )
        except subprocess.CalledProcessError as error:
            if (service, operation) == ("ecr", "get-lifecycle-policy") and re.search(
                    r"\(LifecyclePolicyNotFoundException\)", error.stderr or ""):
                return {}
            raise RuntimeError(f"AWS {service} {operation} failed") from error
        except (OSError, subprocess.SubprocessError) as error:
            # Do not send task environment values or AWS response bodies to CI logs.
            raise RuntimeError(f"AWS {service} {operation} failed") from error
        return json.loads(result.stdout) if result.stdout.strip() else {}


def read_images(directory, release, commit, region):
    if not re.fullmatch(r"v[0-9][A-Za-z0-9_.-]*", release):
        raise ValueError("release must be a v-prefixed version")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("commit must be a full Git SHA")
    images, identities = {}, set()
    for filename in sorted(Path(directory).glob("*.json")):
        value = json.loads(filename.read_text())
        if set(value) != {"module", "image", "release", "commit"}:
            raise ValueError("invalid image manifest fields")
        module = value["module"]
        if module not in MODULES or module in images:
            raise ValueError("unknown or duplicate image module")
        if (value["release"], value["commit"]) != (release, commit):
            raise ValueError("mixed release or commit in image manifests")
        identity = image_identity(value["image"])
        if identity[1] != region:
            raise ValueError("image and selected region differ")
        identities.add(identity)
        images[module] = value["image"]
    if set(images) != set(MODULES) or len(identities) != 1:
        raise ValueError("all five images must use one ECR account, region and repository")
    return images


def service_name(name):
    return "commerce-settle-" + name


def describe(aws, cluster, names, *, timeout=None):
    response = aws("ecs", "describe-services", "--cluster", cluster, "--services",
                   *[service_name(name) for name in names], timeout=timeout)
    services = response.get("services", [])
    if response.get("failures") or {item["serviceName"] for item in services} != {
        service_name(name) for name in names
    } or len(services) != len(names):
        raise RuntimeError("all requested app services must already exist")
    return {name: next(item for item in services if item["serviceName"] == service_name(name))
            for name in names}


def require_stable(service, task):
    deployments = service.get("deployments", [])
    if (service.get("status") != "ACTIVE" or service.get("desiredCount") != 1
            or service.get("runningCount") != 1 or service.get("pendingCount") != 0
            or service.get("taskDefinition") != task or len(deployments) != 1
            or deployments[0].get("taskDefinition") != task
            or deployments[0].get("rolloutState") != "COMPLETED"):
        raise RuntimeError("app service did not complete the expected task revision")


def wait_for(aws, cluster, name, task):
    deadline = time.monotonic() + 600
    aws("ecs", "wait", "services-stable", "--cluster", cluster,
        "--services", service_name(name), timeout=600)
    # The waiter checks deployment/running counts, not rollout completion or revision.
    while True:
        remaining = deadline - time.monotonic()
        if remaining < 0:
            raise RuntimeError(f"{name} deployment completion timed out before the final observation")
        current = describe(aws, cluster, [name], timeout=min(60, remaining))[name]
        expired = time.monotonic() > deadline
        try:
            if expired:
                raise RuntimeError("completion budget expired during the service observation")
            require_stable(current, task)
            return
        except RuntimeError as error:
            primary = next((item for item in current.get("deployments", [])
                            if item.get("status") == "PRIMARY"), {})
            summary = {key: current.get(key) for key in
                       ("status", "taskDefinition", "desiredCount", "runningCount", "pendingCount")}
            summary["rolloutState"] = primary.get("rolloutState")
            if expired:
                raise RuntimeError(f"{name} deployment completion timed out: {summary}") from error
            if (current.get("status") != "ACTIVE" or current.get("desiredCount") != 1
                    or current.get("taskDefinition") != task
                    or primary.get("taskDefinition") != task
                    or primary.get("rolloutState") not in ("IN_PROGRESS", "COMPLETED")):
                raise RuntimeError(f"{name} did not retain the expected deployment: {summary}") from error
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(f"{name} deployment completion timed out: {summary}") from error
            time.sleep(min(15, remaining))


def prepare(aws, cluster, images, region, demo_auto_shipment=None):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,255}", cluster):
        raise ValueError("an explicit ECS cluster name is required")
    account = image_identity(images["gateway"])[0]
    if aws("sts", "get-caller-identity").get("Account") != account:
        raise RuntimeError("caller and release image accounts differ")
    services = describe(aws, cluster, SERVICES)
    tasks = {}
    for name, service in services.items():
        old = service["taskDefinition"]
        require_stable(service, old)
        config = service.get("deploymentConfiguration", {})
        if (service.get("clusterArn") != f"arn:aws:ecs:{region}:{account}:cluster/{cluster}"
                or service.get("deploymentController", {}).get("type") != "ECS"
                or service.get("schedulingStrategy") != "REPLICA"
                or config.get("minimumHealthyPercent") != 0
                or config.get("maximumPercent") != 100
                or not config.get("deploymentCircuitBreaker", {}).get("enable")
                or not config.get("deploymentCircuitBreaker", {}).get("rollback")):
            raise RuntimeError("app services require the documented EC2 deployment settings")
        response = aws("ecs", "describe-task-definition", "--task-definition", old,
                       "--include", "TAGS")
        task = response["taskDefinition"]
        if task.get("status") != "ACTIVE":
            raise RuntimeError("previous task definition must be ACTIVE for rollback")
        if (task.get("family") != service_name(name) or task.get("networkMode") != "bridge"
                or "EC2" not in task.get("requiresCompatibilities", [])
                or task.get("runtimePlatform", {}).get("cpuArchitecture") != "ARM64"):
            raise RuntimeError("app task must use its expected EC2 ARM64 family")
        task = {key: copy.deepcopy(value) for key, value in task.items()
                if key not in TASK_METADATA}
        containers = [item for item in task["containerDefinitions"] if item["name"] == name]
        if (len(containers) != 1 or not containers[0].get("healthCheck")
                or not containers[0].get("essential", True)):
            raise RuntimeError("app container name or health check is missing")
        containers[0]["image"] = images[module_for(name)]
        if demo_auto_shipment is not None and name == "commerce":
            environment = {item["name"]: item["value"]
                           for item in containers[0].get("environment", [])}
            environment["APP_DEMO_AUTO_SHIPMENT_ENABLED"] = str(demo_auto_shipment).lower()
            if demo_auto_shipment:
                environment["APP_ORDER_WORKFLOW_ENABLED"] = "true"
            containers[0]["environment"] = [
                {"name": key, "value": value} for key, value in sorted(environment.items())
            ]
        task["tags"] = [tag for tag in response.get("tags", [])
                        if not tag["key"].lower().startswith("aws:")]
        tasks[name] = task
    return services, tasks


def public_origin(value):
    parts = urllib.parse.urlsplit(value)
    if (parts.scheme != "https" or not parts.hostname or parts.username or parts.password
            or parts.port not in (None, 443) or parts.path not in ("", "/")
            or parts.query or parts.fragment):
        raise ValueError("public URL must be an HTTPS origin without credentials or a path")
    return urllib.parse.urlunsplit(("https", parts.netloc, "", "", ""))


def verify_https(origin):
    for owner in ("commerce", "payment", "settlement"):
        with urllib.request.urlopen(origin + "/v3/api-docs/" + owner, timeout=20) as response:
            target = urllib.parse.urlsplit(response.geturl())._replace(path="").geturl()
            if public_origin(target) != origin:
                raise RuntimeError("HTTPS check redirected to another origin")
            body = response.read(2 * 1024 * 1024 + 1)
            if len(body) > 2 * 1024 * 1024:
                raise RuntimeError("unexpected HTTPS response size")
            document = json.loads(body)
            if not document.get("openapi") or not document.get("paths"):
                raise RuntimeError("public Gateway OpenAPI response is missing")


def rollback_baseline(services, cluster, images, region, demo_auto_shipment=None):
    return {
        "status": "prepared", "cluster": cluster, "region": region,
        "previous": {name: value["taskDefinition"] for name, value in services.items()},
        "images": images,
        "demo_auto_shipment": demo_auto_shipment,
    }


def deploy(aws, cluster, images, region, verify, save, baseline, demo_auto_shipment=None):
    services, tasks = prepare(aws, cluster, images, region, demo_auto_shipment)
    expected = rollback_baseline(services, cluster, images, region, demo_auto_shipment)
    if any(baseline.get(key) != value for key, value in expected.items()):
        raise RuntimeError("saved rollback baseline differs from the current release or services")
    record = {
        **expected,
        "registered": {}, "attempted": [], "rolled_back": [], "rollback_errors": [],
    }
    save(record)
    try:
        for name in ORDER:
            response = aws("ecs", "register-task-definition", payload=tasks[name])
            record["registered"][name] = response["taskDefinition"]["taskDefinitionArn"]
            save(record)
        for name in ORDER:
            # This detects visible changes; ECS UpdateService has no revision CAS.
            require_stable(describe(aws, cluster, [name])[name], record["previous"][name])
            record["attempted"].append(name)
            record["status"] = "deploying"
            save(record)
            aws("ecs", "update-service", "--cluster", cluster, "--service", service_name(name),
                "--task-definition", record["registered"][name])
            wait_for(aws, cluster, name, record["registered"][name])
        verify()
        record["status"] = "success"
        save(record)
        return record
    except BaseException:
        for name in reversed(record["attempted"]):
            try:
                current = describe(aws, cluster, [name])[name]["taskDefinition"]
                if current not in (record["previous"][name], record["registered"][name]):
                    raise RuntimeError("service has another owner's task revision")
                aws("ecs", "update-service", "--cluster", cluster,
                    "--service", service_name(name),
                    "--task-definition", record["previous"][name])
                wait_for(aws, cluster, name, record["previous"][name])
                record["rolled_back"].append(name)
            except Exception:
                record["rollback_errors"].append(name)
            try:
                save(record)
            except OSError:
                # Keep restoring services even if the runner can no longer write records.
                pass
        record["status"] = "failed"
        try:
            save(record)
        except OSError:
            pass
        raise


def require_no_lifecycle(aws, images):
    account, _, repository = image_identity(images["gateway"])
    policy = aws("ecr", "get-lifecycle-policy", "--registry-id", account,
                 "--repository-name", repository)
    if policy:
        raise RuntimeError("remove the competing ECR lifecycle policy before publishing a release")


def require_running_release(aws, cluster, images, registered):
    services = describe(aws, cluster, SERVICES)
    for name in SERVICES:
        require_stable(services[name], registered[name])
    arns = set()
    # Include tasks whose desired state is STOPPED but whose containers are still draining.
    for desired in ("RUNNING", "STOPPED"):
        arns.update(aws("ecs", "list-tasks", "--cluster", cluster,
                        "--desired-status", desired).get("taskArns", []))
    found = set()
    for offset in range(0, len(arns), 100):
        batch = sorted(arns)[offset:offset + 100]
        response = aws("ecs", "describe-tasks", "--cluster", cluster, "--tasks", *batch)
        if response.get("failures") or {t["taskArn"] for t in response.get("tasks", [])} != set(batch):
            raise RuntimeError("cannot verify all cluster tasks before image cleanup")
        for task in response["tasks"]:
            if task.get("lastStatus") == "STOPPED":
                continue
            for container in task.get("containers", []):
                image = container.get("image", "")
                registry = images["gateway"].split("@", 1)[0]
                if image.split("@", 1)[0].split(":", 1)[0] != registry:
                    continue
                name = container.get("name")
                if (name not in SERVICES or task.get("group") != "service:" + service_name(name)
                        or task.get("taskDefinitionArn") != registered[name]
                        or task.get("lastStatus") != "RUNNING"
                        or container.get("lastStatus") != "RUNNING"
                        or container.get("healthStatus") != "HEALTHY"
                        or container.get("imageDigest") != images[module_for(name)].split("@", 1)[1]
                        or name in found):
                    raise RuntimeError("cluster still references another or unverified app image")
                found.add(name)
    if found != set(SERVICES):
        raise RuntimeError("all six running app image digests must be observed")


def cleanup_images(aws, cluster, images, registered, save):
    """Delete only a verified snapshot after successful deployment, never during rollback."""
    account, _, repository = image_identity(images["gateway"])
    require_no_lifecycle(aws, images)
    require_running_release(aws, cluster, images, registered)
    args = ("--registry-id", account, "--repository-name", repository)
    details = aws("ecr", "describe-images", *args).get("imageDetails", [])
    inventory = {item["imageDigest"]: item for item in details}
    retained = {image.split("@", 1)[1] for image in images.values()}
    if not retained <= inventory.keys():
        raise RuntimeError("successful release images are missing from ECR")
    managed = re.compile(r"(?:" + "|".join(MODULES) + r")-v[0-9][A-Za-z0-9_.-]*$"
                         r"|keep-(?:" + "|".join(MODULES) + r")-(?:current|rollback|candidate)$")
    candidates = {digest for digest, item in inventory.items()
                  if all(managed.fullmatch(tag) for tag in item.get("imageTags", []))}
    retained.update(inventory.keys() - candidates)
    manifests = {}
    digests = sorted(inventory)
    for offset in range(0, len(digests), 100):
        batch = digests[offset:offset + 100]
        response = aws("ecr", "batch-get-image", payload={
            "registryId": account, "repositoryName": repository,
            "imageIds": [{"imageDigest": digest} for digest in batch],
        })
        if response.get("failures"):
            raise RuntimeError("cannot read image dependencies before cleanup")
        received = {item["imageId"]["imageDigest"]: json.loads(item["imageManifest"])
                    for item in response.get("images", [])}
        if received.keys() != set(batch):
            raise RuntimeError("incomplete image dependency response")
        manifests.update(received)
    dependencies = {digest: {item["digest"] for item in body.get("manifests", [])}
                    for digest, body in manifests.items()}
    # An attestation/reference artifact belonging to a retained image must also survive.
    while True:
        expanded = retained | {child for digest in retained for child in dependencies.get(digest, [])}
        expanded.update(digest for digest, body in manifests.items()
                        if body.get("subject", {}).get("digest") in expanded)
        if expanded == retained:
            break
        retained = expanded
    if not retained <= inventory.keys():
        raise RuntimeError("retained image has missing manifest dependencies")
    pending = candidates - retained
    record = {"status": "deleting", "retained": sorted(retained),
              "planned": sorted(pending), "deleted": []}
    save(record)
    while pending:
        # Delete manifest lists before their children; AWS also enforces these references.
        children = {child for digest in pending for child in dependencies.get(digest, [])}
        batch = sorted(pending - children)[:100]
        if not batch:
            raise RuntimeError("cyclic image manifest references")
        require_running_release(aws, cluster, images, registered)
        response = aws("ecr", "batch-delete-image", payload={
            "registryId": account, "repositoryName": repository,
            "imageIds": [{"imageDigest": digest} for digest in batch],
        })
        deleted = {item["imageDigest"] for item in response.get("imageIds", [])}
        record["deleted"] += sorted(deleted)
        save(record)
        if response.get("failures") or deleted != set(batch):
            raise RuntimeError("ECR deletion incomplete; retry cleanup without redeploying")
        pending -= deleted
    remaining = {item["imageDigest"] for item in aws("ecr", "describe-images", *args).get("imageDetails", [])}
    if not retained <= remaining or (candidates - retained) & remaining:
        raise RuntimeError("ECR image cleanup verification failed")
    record["status"] = "success"
    save(record)
    return record


def finish_cleanup(aws, record, save):
    # Successful deployment is irreversible here: a cleanup error must never invoke rollback.
    if record.get("status") != "success" or set(record.get("registered", {})) != set(SERVICES):
        raise ValueError("cleanup requires a successful complete deployment record")

    def save_cleanup(cleanup):
        record["cleanup"] = cleanup
        save(record)

    try:
        cleanup_images(aws, record["cluster"], record["images"], record["registered"], save_cleanup)
    except Exception:
        record.setdefault("cleanup", {})["status"] = "failed"
        save(record)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("cluster", "region", "images-directory", "release", "commit",
                 "public-url", "record"):
        parser.add_argument("--" + name, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-only", action="store_true")
    mode.add_argument("--baseline")
    mode.add_argument("--cleanup-only", metavar="DEPLOYMENT_RECORD")
    parser.add_argument("--baseline-sha256")
    parser.add_argument("--demo-auto-shipment", choices=("true", "false", "preserve"),
                        default="preserve")
    args = parser.parse_args()
    demo_auto_shipment = (None if args.demo_auto_shipment == "preserve"
                          else args.demo_auto_shipment == "true")
    try:
        origin = public_origin(args.public_url)
        images = read_images(args.images_directory, args.release, args.commit, args.region)

        def save(record):
            record.update(release=args.release, commit=args.commit)
            target = Path(args.record)
            temporary = target.with_name(target.name + ".tmp")
            temporary.write_text(json.dumps(record, indent=2) + "\n")
            temporary.replace(target)

        if args.baseline:
            raw = Path(args.baseline).read_bytes()
            if hashlib.sha256(raw).hexdigest() != args.baseline_sha256:
                raise ValueError("rollback baseline checksum differs")
            baseline = json.loads(raw)
            if (baseline.get("release"), baseline.get("commit")) != (args.release, args.commit):
                raise ValueError("rollback baseline belongs to another release")
        aws = Aws(args.region)
        if args.cleanup_only:
            record = json.loads(Path(args.cleanup_only).read_text())
            if any(record.get(key) != value for key, value in {
                    "cluster": args.cluster, "region": args.region, "images": images,
                    "release": args.release, "commit": args.commit}.items()):
                raise ValueError("cleanup record belongs to another release or target")
            if aws("sts", "get-caller-identity").get("Account") != image_identity(images["gateway"])[0]:
                raise RuntimeError("caller and cleanup image accounts differ")
            finish_cleanup(aws, record, save)
            print("Successful deployment images retained; cleanup verified.")
            return 0
        require_no_lifecycle(aws, images)
        if args.prepare_only:
            services, _ = prepare(Aws(args.region), args.cluster, images, args.region,
                                  demo_auto_shipment)
            save(rollback_baseline(services, args.cluster, images, args.region,
                                   demo_auto_shipment))
            print("Rollback baseline prepared; no AWS changes made.")
            return 0
        record = deploy(aws, args.cluster, images, args.region,
               lambda: verify_https(origin), save, baseline, demo_auto_shipment)
        finish_cleanup(aws, record, save)
    except (Exception, KeyboardInterrupt) as error:
        # The record contains only image/revision references, never task environments.
        reason = str(error) if isinstance(error, (ValueError, RuntimeError)) else type(error).__name__
        print("Deployment or image cleanup failed: " + reason + "; check status and cleanup in the record.")
        return 1
    print("All six app services and public HTTPS verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
