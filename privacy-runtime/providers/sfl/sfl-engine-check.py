#!/usr/bin/env python3
"""Fail-closed health and explicit operator approval for pinned SFL."""
import argparse
import importlib.metadata
import json
import os
import subprocess
from pathlib import Path

from topic4_sfl_smoke import approve_record, load_and_validate_record, require_approved_record

EXPECTED = {"secretflow-lite": "1.13.0b0", "ray": "2.52.0"}
REVISION = "c383e40f665063d7f7d87e437a73e015f87c435c"
PARTIES = ["A", "B", "C"]


def main():
    parser = argparse.ArgumentParser()
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--base-check", action="store_true")
    action.add_argument("--verify-smoke", action="store_true")
    action.add_argument("--approve-smoke", action="store_true")
    args = parser.parse_args()
    actual = {}
    for package, expected in EXPECTED.items():
        value = importlib.metadata.version(package)
        actual[package] = value
        if value != expected:
            raise SystemExit("%s version mismatch: %s != %s" % (package, value, expected))
    actual["sfl"] = importlib.metadata.version("sfl")
    if Path("/opt/sfl/SOURCE_REVISION").read_text(encoding="utf-8").strip() != REVISION:
        raise SystemExit("SFL source revision mismatch")

    context_file = Path(os.getenv(
        "TOPIC4_KUSCIA_CONTEXT_FILE", "/var/run/topic4-kuscia/context.json"))
    completed = subprocess.run(
        ["python3", "/opt/topic4/bin/kuscia-runtime-entry.py", "check", str(context_file)],
        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        timeout=15, check=False, text=True,
    )
    if completed.returncode != 0:
        raise SystemExit("validated Kuscia runtime context is unavailable")
    context = json.loads(context_file.read_text(encoding="utf-8"))
    cluster = context.get("sflClusterConfig", {})
    if (list(cluster.get("parties", {})) != PARTIES
            or cluster.get("self_party") != context.get("partyId")
            or set(context.get("secretflowEndpoints", {})) != {"fed", "spu"}):
        raise SystemExit("SFL Kuscia fed/SPU topology is incomplete")

    if args.base_check:
        print(json.dumps({
            "status": "base-ok", "packages": actual, "sourceRevision": REVISION,
            "party": context["partyId"], "servingId": context["servingId"],
            "transport": context["transport"], "plaintextFallback": False,
        }, sort_keys=True))
        return

    try:
        if args.approve_smoke:
            actual_digest = approve_record(context)
        elif args.verify_smoke:
            _, actual_digest = load_and_validate_record(context)
        else:
            _, actual_digest = require_approved_record(context)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        raise SystemExit(str(exc)) from exc

    print(json.dumps({
        "status": "ok", "packages": actual, "sourceRevision": REVISION,
        "party": context["partyId"], "servingId": context["servingId"],
        "distributedSmokeDigest": actual_digest,
        "operatorApproved": not args.verify_smoke,
        "transport": context["transport"], "plaintextFallback": False,
    }, sort_keys=True))


if __name__ == "__main__":
    main()
