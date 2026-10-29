#!/usr/bin/env python3
"""Create local settings without replacing existing non-empty values."""

import os
from pathlib import Path
import re
import secrets
import uuid


def initialize(directory):
    directory = Path(directory)
    target = directory / ".env"
    content = target.read_text() if target.exists() else ""
    lines = content.splitlines(keepends=True)
    values = {}
    for index, line in enumerate(lines):
        match = re.match(r"^([A-Z][A-Z0-9_]*)=(.*)$", line.rstrip("\r\n"))
        if match:
            key = match[1]
            if key in values:
                raise ValueError(f"Duplicate setting: {key}")
            values[key] = (index, match[2])

    for line in (directory / ".env.example").read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        key, default = line.split("=", 1)
        existing = values.get(key)
        if existing and existing[1].strip():
            continue
        if key.endswith("PASSWORD"):
            value = secrets.token_urlsafe(32)
        elif key.endswith("EPOCH"):
            value = str(uuid.uuid4())
        else:
            value = default
        if existing:
            lines[existing[0]] = f"{key}={value}\n"
        else:
            if lines and not lines[-1].endswith("\n"):
                lines[-1] += "\n"
            lines.append(f"{key}={value}\n")

    updated = "".join(lines)
    if updated == content:
        return False
    if target.exists():
        target.write_text(updated)
    else:
        descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as handle:
            handle.write(updated)
    return True


if __name__ == "__main__":
    changed = initialize(Path(__file__).resolve().parents[1])
    print("Local settings initialized." if changed else "Existing settings preserved.")
