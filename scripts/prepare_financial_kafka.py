#!/usr/bin/env python3
"""Prepare missing financial consumer groups on empty local Kafka topics.

Run after the base Compose stack creates its topics and before enabling consumers.
Existing positions are preserved. Historical data and partial groups need an explicit
recovery decision; this command never guesses their starting position.
"""

from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SUBSCRIPTIONS = {
    "commerce.v1": {"commerce-settle.payment.v1"},
    "payment.v1": {"commerce-settle.commerce.v1", "commerce-settle.settlement.v1"},
    "settlement.v1": {"commerce-settle.payment.v1", "commerce-settle.commerce.v1"},
}
TOPICS = set().union(*SUBSCRIPTIONS.values())
TOPIC_PATTERN = r"^commerce-settle\.(commerce|payment|settlement)\.v1$"


def compose(*args):
    return subprocess.run(
        ["docker", "compose", *args], cwd=ROOT, text=True, capture_output=True,
        timeout=90, check=True,
    ).stdout


def kafka(run, tool, *args):
    return run("exec", "-T", "kafka", "/opt/kafka/bin/" + tool,
               "--bootstrap-server", "kafka:9092", *args)


def partitions(run):
    description = kafka(run, "kafka-topics.sh", "--describe", "--topic", TOPIC_PATTERN)
    counts = re.findall(r"Topic:\s+(\S+).*?PartitionCount:\s+(\d+)", description)
    if len(counts) != len(TOPICS) or {topic for topic, _ in counts} != TOPICS:
        raise RuntimeError("Missing financial topics; start the base Compose stack first")
    expected = {(topic, number) for topic, count in counts for number in range(int(count))}
    if any(int(count) == 0 for _, count in counts):
        raise RuntimeError("Missing financial topic partitions")
    return expected


def bounds(run, expected, position):
    output = kafka(run, "kafka-get-offsets.sh", "--topic", TOPIC_PATTERN, "--time", position)
    offsets = {}
    for line in output.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r"([^:]+):(\d+):(\d+)", line.strip())
        if not match:
            raise RuntimeError("Invalid Kafka log offset observation")
        topic, partition, offset = match.groups()
        key = (topic, int(partition))
        if key in offsets:
            raise RuntimeError("Duplicate Kafka log offset observation")
        offsets[key] = int(offset)
    if offsets.keys() != expected:
        raise RuntimeError("Incomplete financial topic offset observation")
    return offsets


def group_offsets(run, group):
    output = kafka(run, "kafka-consumer-groups.sh", "--group", group, "--describe", "--offsets")
    offsets = {}
    for line in output.splitlines():
        fields = line.split()
        if not fields or fields[0] == "GROUP":
            continue
        if len(fields) < 6 or fields[0] != group or not fields[2].isdigit():
            raise RuntimeError(f"Invalid consumer offset observation for {group}")
        key = (fields[1], int(fields[2]))
        if key in offsets or not (fields[3].isdigit() or fields[3] == "-"):
            raise RuntimeError(f"Invalid consumer offset observation for {group}")
        if fields[3] != "-":
            offsets[key] = int(fields[3])
    return offsets


def inactive(run, group):
    output = kafka(run, "kafka-consumer-groups.sh", "--group", group, "--describe", "--state")
    rows = [line.split() for line in output.splitlines() if line.strip()]
    if len(rows) != 2 or rows[0][0] != "GROUP" or rows[1][0] != group:
        raise RuntimeError(f"Invalid consumer state observation for {group}")
    return rows[1][-2:] in (["Empty", "0"], ["Dead", "0"])


def prepare_offsets(run=compose):
    expected = partitions(run)
    starts = bounds(run, expected, "earliest")
    ends = bounds(run, expected, "latest")
    if any(starts[key] > ends[key] for key in expected):
        raise RuntimeError("Invalid Kafka log bounds")
    groups = set(kafka(run, "kafka-consumer-groups.sh", "--list").split())
    pending = []
    for group, topics in SUBSCRIPTIONS.items():
        required = {key for key in expected if key[0] in topics}
        offsets = group_offsets(run, group) if group in groups else {}
        if offsets:
            if offsets.keys() != required or any(
                not starts[key] <= offsets[key] <= ends[key] for key in required
            ):
                raise RuntimeError(f"Partial or out-of-range offsets for {group}; use the documented recovery procedure")
        else:
            if group in groups and not inactive(run, group):
                raise RuntimeError(f"Active consumer group {group}; stop consumers before first preparation")
            if any(starts[key] != 0 or ends[key] != 0 for key in required):
                raise RuntimeError(f"Existing Kafka history for {group}; choose offsets through the documented recovery procedure")
            pending.append(group)

    # Preflight every group before writing. Kafka also rejects reset while a group is active.
    for group in pending:
        current = set(kafka(run, "kafka-consumer-groups.sh", "--list").split())
        if group in current and (group_offsets(run, group) or not inactive(run, group)):
            raise RuntimeError(f"Consumer group {group} changed during preparation; inspect it before retrying")
        args = ["--group", group, "--reset-offsets", "--to-earliest", "--execute"]
        for topic in sorted(SUBSCRIPTIONS[group]):
            args += ["--topic", topic]
        kafka(run, "kafka-consumer-groups.sh", *args)
        required = {key for key in expected if key[0] in SUBSCRIPTIONS[group]}
        if group_offsets(run, group) != {key: 0 for key in required}:
            raise RuntimeError(f"Consumer offset preparation incomplete for {group}; inspect it before retrying")
    return pending


def main():
    try:
        prepared = prepare_offsets()
    except (RuntimeError, subprocess.SubprocessError, OSError) as error:
        print(f"Financial Kafka preparation failed: {error}. See docs/development.md#금융-관측과-접수-복구", file=sys.stderr)
        return 1
    print("Financial Kafka offsets ready; initialized: " + (", ".join(prepared) or "none (existing offsets preserved)")
          + ". Order admission still requires all observations to remain healthy for 60 seconds.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
