#!/usr/bin/env python3
import csv
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

LOCAL_HOST_RE = re.compile(r"^0\.0\.0\.0:[1-9][0-9]{0,4}$")
KUSCIA_HOST_RE = re.compile(
    r"^http://[a-z0-9](?:[-a-z0-9.]{0,252}[a-z0-9])?:80$"
)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()


def atomic(path, value):
    target = Path(path)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    os.replace(str(temporary), str(target))


def canonical_digest(value):
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def protocol_message_report(report):
    """Extract only communication byte counters emitted by the pinned launcher."""
    counters = []

    def visit(value, prefix=""):
        if isinstance(value, dict):
            for key, item in value.items():
                path = "%s.%s" % (prefix, key) if prefix else str(key)
                lowered = str(key).lower()
                if (isinstance(item, int) and not isinstance(item, bool)
                        and "byte" in lowered
                        and any(marker in lowered for marker in ("send", "sent", "recv", "receive", "comm", "network"))):
                    counters.append({"counter": path, "bytes": item})
                else:
                    visit(item, path)
        elif isinstance(value, list):
            for index, item in enumerate(value):
                visit(item, "%s[%d]" % (prefix, index))

    visit(report)
    summary = {
        "source": "SecretFlow PSI launcher Report byte counters",
        "observations": counters,
        "reportedBytes": sum(item["bytes"] for item in counters) if counters else None,
        "transcriptDigestAvailable": False,
    }
    summary["summaryDigest"] = canonical_digest(summary)
    return summary


def remove_file(path):
    try:
        Path(path).unlink(missing_ok=True)
    except OSError:
        pass


def private_work_directory():
    value = os.getenv("TOPIC4_JOB_WORK_DIR", "")
    path = Path(value)
    if not value or not path.is_absolute() or path.is_symlink() or not path.is_dir():
        raise OSError("runner-owned TOPIC4_JOB_WORK_DIR is unavailable")
    return path.resolve()


def validate_unique_keys(source, keys):
    with Path(source).open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or any(key not in reader.fieldnames for key in keys):
            raise ValueError("input CSV does not contain every registered key column")
        seen = set()
        for row in reader:
            value = tuple((row.get(key) or "").strip() for key in keys)
            if any(not item for item in value):
                raise ValueError("PSI/PIR keys cannot be empty")
            if value in seen:
                raise ValueError("PSI/PIR keys must be unique within each party")
            seen.add(value)


def read_result(path):
    with Path(path).open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames:
            raise ValueError("protocol result is missing its CSV header")
        return [{key: value for key, value in row.items()} for row in reader]


def kuscia_context():
    context_file = Path(os.getenv(
        "TOPIC4_KUSCIA_CONTEXT_FILE", "/var/run/topic4-kuscia/context.json"))
    try:
        context = json.loads(context_file.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise ValueError("validated Kuscia runtime context is unavailable") from exc
    supplied_digest = context.pop("contextDigest", None)
    if supplied_digest != canonical_digest(context):
        raise ValueError("Kuscia runtime context digest mismatch")
    context["contextDigest"] = supplied_digest
    if (context.get("transport") != "KUSCIA_CLUSTER_MTLS_ENVOY"
            or os.getenv("TOPIC4_KUSCIA_TRANSPORT") != context["transport"]
            or os.getenv("TOPIC4_KUSCIA_DEPLOYMENT_ID") != context.get("servingId")):
        raise ValueError("runtime is not a validated KusciaDeployment workload")
    endpoint_map = context.get("engineEndpoints")
    if not isinstance(endpoint_map, dict) or not endpoint_map:
        raise ValueError("Kuscia runtime engine endpoints are unavailable")
    return context


def parties(request, context):
    endpoint_map = context["engineEndpoints"]
    result = []
    for item in request["participants"]:
        party_id = item.get("partyId") or item.get("domainId")
        endpoint = endpoint_map.get(party_id)
        if (not isinstance(endpoint, str)
                or not (LOCAL_HOST_RE.fullmatch(endpoint) or KUSCIA_HOST_RE.fullmatch(endpoint))):
            raise ValueError("participant is absent from the Kuscia Cluster endpoint map")
        if LOCAL_HOST_RE.fullmatch(endpoint) and int(endpoint.rsplit(":", 1)[1]) > 65535:
            raise ValueError("participant endpoint port is invalid")
        result.append({"id": party_id, "host": endpoint})
    return result


def prepare_apsi_input(source, destination, sender, query_column, value_columns):
    with Path(source).open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        required = [query_column] + (value_columns if sender else [])
        if not reader.fieldnames or any(name not in reader.fieldnames for name in required):
            raise ValueError("APSI CSV does not contain the registered query/value columns")
        rows = list(reader)
    keys = [(row.get(query_column) or "").strip() for row in rows]
    if any(not key for key in keys):
        raise ValueError("APSI keys cannot be empty")
    if len(set(keys)) != len(keys):
        raise ValueError("APSI keys must be unique")
    with Path(destination).open("w", newline="", encoding="utf-8") as stream:
        # APSI validates the header with std::getline and compares it byte for
        # byte.  csv.writer defaults to CRLF even on Linux, leaving a trailing
        # '\r' in APSI's header and causing the engine to reject an otherwise
        # valid staged CSV.  Emit the LF-only format used by the pinned PSI
        # examples and accepted by its strict header parser.
        writer = csv.writer(stream, lineterminator="\n")
        if sender:
            writer.writerow(["key"] + (["value"] if value_columns else []))
            for row in rows:
                writer.writerow([row[query_column]] + [row.get(value_columns[0], "")] if value_columns
                                else [row[query_column]])
        else:
            writer.writerow(["key"])
            for row in rows:
                writer.writerow([row[query_column]])
    return keys


def main():
    if len(sys.argv) != 3:
        return 64
    request_path, job_dir = map(Path, sys.argv[1:])
    request = json.loads(request_path.read_text(encoding="utf-8"))
    template = request.get("templateId")
    if template not in {"psi-2p-v1", "psi-3p-v1", "pir-keyword-2p-v1"}:
        return 64
    party_id = os.getenv("TOPIC4_PARTY_ID", "")
    if not party_id:
        print("TOPIC4_PARTY_ID is required", file=sys.stderr)
        return 69
    try:
        context = kuscia_context()
        links = parties(request, context)
    except ValueError as exc:
        # Kuscia context/endpoints are deployment state, never client input.
        # Missing or invalid context means the fixed engine is unavailable.
        print(str(exc), file=sys.stderr)
        return 69
    ids = [item["id"] for item in links]
    if party_id not in ids:
        print("local party is absent from participants", file=sys.stderr)
        return 64
    local_rank = ids.index(party_id)
    policy = request.get("enginePolicy", {})
    allowed = ({"keyColumns", "outputMode"}
               if template in {"psi-2p-v1", "psi-3p-v1"}
               else {"queryColumn", "valueColumns"})
    if set(policy) - allowed:
        print("unsupported PSI enginePolicy field", file=sys.stderr)
        return 64
    keys = policy.get("keyColumns", ["id"])
    if not isinstance(keys, list) or not keys or len(keys) > 4 or not all(re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", key or "") for key in keys):
        print("keys must be one to four CSV column identifiers", file=sys.stderr)
        return 64
    input_path = Path(os.getenv("TOPIC4_JOB_INPUT", ""))
    if not input_path.is_file():
        print("runner did not stage an authorized local input", file=sys.stderr)
        return 69
    try:
        work_dir = private_work_directory()
    except OSError as exc:
        print(str(exc), file=sys.stderr)
        return 69
    if template != "pir-keyword-2p-v1":
        try:
            validate_unique_keys(input_path, keys)
        except ValueError as exc:
            print(str(exc), file=sys.stderr)
            return 64
    output_path = work_dir / "protocol-output.csv"
    trace_path = work_dir / "protocol.trace"
    config = {
        "link_config": {
            "parties": links,
            "brpc_channel_protocol": "http",
            "brpc_channel_connection_type": "pooled",
        },
        "self_link_party": party_id,
    }
    if template == "psi-2p-v1":
        receiver = next((item["id"] for item, participant in zip(links, request["participants"])
                         if participant.get("role") == "RECEIVER"), ids[0])
        output_mode = policy.get("outputMode", "RECEIVER_ONLY")
        if output_mode not in {"RECEIVER_ONLY", "ALL_PARTIES"}:
            print("outputMode must be RECEIVER_ONLY or ALL_PARTIES", file=sys.stderr)
            return 64
        broadcast = output_mode == "ALL_PARTIES"
        expected_recipients = set(ids if broadcast else [receiver])
        if set(request["resultRecipients"]) != expected_recipients:
            print("resultRecipients does not match outputMode", file=sys.stderr)
            return 64
        config["psi_config"] = {
            "protocol_config": {
                "protocol": "PROTOCOL_RR22",
                "role": "ROLE_RECEIVER" if party_id == receiver else "ROLE_SENDER",
                "broadcast_result": broadcast,
                "rr22_config": {"low_comm_mode": False}
            },
            "input_config": {"type": "IO_TYPE_FILE_CSV", "path": str(input_path)},
            "output_config": {"type": "IO_TYPE_FILE_CSV", "path": str(output_path)},
            "keys": keys,
            "debug_options": {"logging_level": "info", "trace_path": str(trace_path)},
            "disable_alignment": False,
            "recovery_config": {"enabled": False}
        }
    elif template == "psi-3p-v1":
        output_mode = policy.get("outputMode", "RECEIVER_ONLY")
        if output_mode not in {"RECEIVER_ONLY", "ALL_PARTIES"}:
            print("outputMode must be RECEIVER_ONLY or ALL_PARTIES", file=sys.stderr)
            return 64
        if output_mode == "RECEIVER_ONLY":
            if len(request["resultRecipients"]) != 1:
                print("RECEIVER_ONLY requires exactly one result recipient", file=sys.stderr)
                return 64
            receiver = request["resultRecipients"][0]
        else:
            receiver = ids[0]
        expected_recipients = set(ids if output_mode == "ALL_PARTIES" else [receiver])
        if set(request["resultRecipients"]) != expected_recipients:
            print("resultRecipients does not match outputMode", file=sys.stderr)
            return 64
        receiver_rank = ids.index(receiver)
        config["legacy_psi_config"] = {
            "psi_type": "ECDH_PSI_3PC",
            "receiver_rank": receiver_rank,
            "broadcast_result": output_mode == "ALL_PARTIES",
            "input_params": {"path": str(input_path), "select_fields": keys, "precheck": True},
            "output_params": {"path": str(output_path), "need_sort": True},
            "curve_type": "CURVE_25519"
        }
    else:
        sender = next((participant.get("partyId") or participant.get("domainId")
                       for participant in request["participants"]
                       if participant.get("role") == "SERVER"), ids[1])
        receiver = next((participant.get("partyId") or participant.get("domainId")
                         for participant in request["participants"]
                         if participant.get("role") == "CLIENT"), ids[0])
        if set(request["resultRecipients"]) != {receiver}:
            print("APSI resultRecipients must contain only the CLIENT party", file=sys.stderr)
            return 64
        query_column = policy.get("queryColumn", "key")
        value_columns = policy.get("valueColumns", ["value"])
        if (not isinstance(query_column, str)
                or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", query_column)
                or not isinstance(value_columns, list)
                or len(value_columns) != 1
                or not all(isinstance(value, str)
                           and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", value)
                           for value in value_columns)):
            print("invalid APSI queryColumn/valueColumns", file=sys.stderr)
            return 64
        local_input = work_dir / ("apsi-db.csv" if party_id == sender else "apsi-query.txt")
        try:
            apsi_keys = prepare_apsi_input(input_path, local_input, party_id == sender,
                                           query_column, value_columns)
        except ValueError as exc:
            print(str(exc), file=sys.stderr)
            return 64
        params = Path("/opt/psi/parameters/100K-1-16.json")
        if party_id == sender:
            config["apsi_sender_config"] = {
                "threads": 1, "source_file": str(local_input), "params_file": str(params),
                "log_level": "info", "silent": True
            }
        else:
            config["apsi_receiver_config"] = {
                "threads": 1, "query_file": str(local_input), "output_file": str(output_path),
                "params_file": str(params), "log_level": "info", "silent": True,
                "query_batch_size": 1
            }
    config_path = work_dir / "psi-launch.json"
    atomic(config_path, config)
    engine_log = work_dir / "psi.log"
    launcher = Path("/opt/psi/main")
    with engine_log.open("wb") as stream:
        completed = subprocess.run([str(launcher), "--config", str(config_path)], stdout=stream, stderr=subprocess.STDOUT, check=False)
    if completed.returncode:
        return 70
    output = engine_log.read_text(encoding="utf-8", errors="replace")
    report = None
    matches = re.findall(r"Report:\s*(\{.*\})", output)
    if matches:
        try:
            report = json.loads(matches[-1])
        except ValueError:
            pass
    released = party_id in request["resultRecipients"]
    if released and not output_path.is_file():
        print("result recipient has no protocol output", file=sys.stderr)
        return 70
    released_rows = read_result(output_path) if released else None
    if not released:
        remove_file(output_path)
    result = {
        "contractVersion": "topic4.privacy.result/v1",
        "jobId": request["jobId"],
        "attemptId": request["attemptId"],
        "templateId": template,
        "partyId": party_id,
        "released": released,
        "securityProfile": "SEMI_HONEST_PIR" if template == "pir-keyword-2p-v1" else "SEMI_HONEST",
        "report": report,
        "rows": released_rows,
        "hit": bool(released_rows) if template == "pir-keyword-2p-v1" and released else None,
        "matchCount": len(released_rows) if released_rows is not None else None,
        "outputDigest": digest(output_path) if released else None,
        "outputBytes": output_path.stat().st_size if released else 0
    }
    atomic(job_dir / "engine-result.json", result)
    atomic(job_dir / "engine-evidence.json", {
        "protocol": {"psi-2p-v1": "RR22", "psi-3p-v1": "ECDH_3PC", "pir-keyword-2p-v1": "APSI"}[template],
        "launcherRevision": "72f3312fd9142ea567f3a170d0808a2118b75af8",
        "localInputDigest": digest(input_path),
        "localPartyRank": local_rank,
        "launchConfigDigest": digest(config_path),
        "engineLogDigest": digest(engine_log),
        "protocolMessages": protocol_message_report(report),
        "launcherSilent": template == "pir-keyword-2p-v1",
        "serverQueryKeyLogCheck": (
            "launcher configured silent; acceptance must scan the SERVER log against CLIENT queries"
            if template == "pir-keyword-2p-v1" else None),
        "resultDigest": digest(output_path) if released else None,
        "knownDisclosure": "receiver may learn pairwise intersection cardinality" if template == "psi-3p-v1" else None,
        "kusciaContextDigest": context["contextDigest"],
        "kusciaDeploymentId": context["servingId"],
        "transportSecurity": "Kuscia Cluster endpoint routed by Envoy under domain MTLS",
        "plaintextFallback": False
    })
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
