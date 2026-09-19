#!/usr/bin/env python3
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

TEMPLATES = {
    "secure-sum-3p-v1": ("malicious-rep-ring-party.x", "topic4_secure_sum"),
    "private-stats-3p-v1": ("malicious-rep-ring-party.x", "topic4_private_stats"),
    "private-threshold-3p-v1": ("malicious-rep-ring-party.x", "topic4_private_threshold_100"),
}


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


def protocol_message_report(output):
    """Record only byte counters reported by MP-SPDZ, never a fake transcript hash."""
    units = {"B": 1, "KB": 1000, "MB": 1000 * 1000, "GB": 1000 * 1000 * 1000}
    observations = []
    pattern = re.compile(
        r"(?P<label>(?:Global\s+)?Data sent)\s*=\s*(?P<amount>[0-9]+(?:\.[0-9]+)?)\s*(?P<unit>GB|MB|KB|B)\b",
        re.IGNORECASE,
    )
    for match in pattern.finditer(output):
        unit = match.group("unit").upper()
        observations.append({
            "counter": " ".join(match.group("label").split()),
            "bytes": int(round(float(match.group("amount")) * units[unit])),
        })
    report = {
        "source": "MP-SPDZ process-reported byte counters",
        "observations": observations,
        "reportedBytes": max((item["bytes"] for item in observations), default=None),
        "transcriptDigestAvailable": False,
    }
    report["summaryDigest"] = canonical_digest(report)
    return report


def parse_statistics(output, vector_length):
    pattern = re.compile(
        r"TOPIC4_STATS\[(\d+)\] sum=(\S+) count=3 mean=(\S+) "
        r"min=(\S+) max=(\S+) variance=(\S+)"
    )
    values = {}
    for match in pattern.finditer(output):
        values[int(match.group(1))] = {
            "sum": match.group(2), "count": 3, "mean": match.group(3),
            "min": match.group(4), "max": match.group(5), "variance": match.group(6),
        }
    if any(lane not in values for lane in range(vector_length)):
        return None
    return [values[lane] for lane in range(vector_length)]


def private_work_directory():
    value = os.getenv("TOPIC4_JOB_WORK_DIR", "")
    path = Path(value)
    if not value or not path.is_absolute() or path.is_symlink() or not path.is_dir():
        raise OSError("runner-owned TOPIC4_JOB_WORK_DIR is unavailable")
    return path.resolve()


def main():
    if len(sys.argv) != 3:
        return 64
    request_path, job_dir = map(Path, sys.argv[1:])
    request = json.loads(request_path.read_text(encoding="utf-8"))
    template = request.get("templateId")
    if template not in TEMPLATES:
        return 64
    party_index_text = os.getenv("TOPIC4_PARTY_INDEX", "")
    party_id = os.getenv("TOPIC4_PARTY_ID", "")
    if party_index_text not in {"0", "1", "2"} or not party_id:
        print("TOPIC4_PARTY_INDEX/TOPIC4_PARTY_ID are required", file=sys.stderr)
        return 69
    party_index = int(party_index_text)
    participants = request["participants"]
    expected_id = participants[party_index].get("partyId") or participants[party_index].get("domainId")
    if expected_id != party_id:
        print("local party identity does not match participant ordering", file=sys.stderr)
        return 64
    recipient_mask = 0
    for index, participant in enumerate(participants):
        identifier = participant.get("partyId") or participant.get("domainId")
        if identifier in request["resultRecipients"]:
            recipient_mask |= 1 << index
    if recipient_mask < 1 or recipient_mask > 7:
        print("resultRecipients must map to a non-empty precompiled recipient mask", file=sys.stderr)
        return 64
    policy = request.get("enginePolicy", {})
    allowed_by_template = {
        "secure-sum-3p-v1": set(),
        "private-stats-3p-v1": {"scale"},
        "private-threshold-3p-v1": {"programId", "threshold", "scale"},
    }
    if set(policy) - allowed_by_template[template]:
        print("unsupported MP-SPDZ enginePolicy field", file=sys.stderr)
        return 64
    if template == "private-threshold-3p-v1":
        if policy.get("programId", "topic4_private_threshold_100") != "topic4_private_threshold_100":
            print("only the pre-registered topic4_private_threshold_100 program is allowed", file=sys.stderr)
            return 64
        if policy.get("threshold", 100) != 100 or policy.get("scale", 1) != 1:
            print("the registered threshold program fixes threshold=100 and scale=1", file=sys.stderr)
            return 64
    if template == "private-stats-3p-v1":
        scale = policy.get("scale", 4)
        if not isinstance(scale, int) or isinstance(scale, bool) or not 0 <= scale <= 8:
            print("private statistics scale must be an integer from 0 through 8", file=sys.stderr)
            return 64
    bound_lengths = []
    for participant in participants:
        fields = participant.get("fields")
        if not isinstance(fields, list) or not fields or not all(isinstance(item, str) and item for item in fields):
            print("each participant requires fixed field bindings", file=sys.stderr)
            return 64
        if len(set(fields)) != len(fields):
            print("participant field bindings must be unique", file=sys.stderr)
            return 64
        bound_lengths.append(len(fields))
    if len(set(bound_lengths)) != 1 or bound_lengths[0] > 8:
        print("all parties must bind the same one-to-eight field count", file=sys.stderr)
        return 64
    if template == "private-threshold-3p-v1" and bound_lengths[0] != 1:
        print("threshold template requires exactly one bound field per party", file=sys.stderr)
        return 64
    vector_length = bound_lengths[0]
    local_fields = participants[party_index]["fields"]
    input_path = Path(os.getenv("TOPIC4_JOB_INPUT", ""))
    if not input_path.is_file():
        print("runner did not stage an authorized local input", file=sys.stderr)
        return 69
    try:
        work_dir = private_work_directory()
    except OSError as exc:
        print(str(exc), file=sys.stderr)
        return 69
    player_data = Path(os.getenv("MP_SPDZ_PLAYER_DATA", ""))
    if (not player_data.is_absolute() or player_data.is_symlink()
            or player_data.parent.resolve() != work_dir):
        print("MP_SPDZ_PLAYER_DATA must be a direct child of the runner work directory",
              file=sys.stderr)
        return 69
    player_data.mkdir(parents=True, exist_ok=True)
    player_input = player_data / ("Input-P%d-0" % party_index)
    converter = Path("/opt/topic4/bin/csv_to_input.py")
    if not converter.is_file():
        converter = Path(__file__).with_name("csv_to_input.py")
    converted = subprocess.run(
        [sys.executable, str(converter), template, str(input_path), str(player_input),
         json.dumps(local_fields, separators=(",", ":"))],
        check=False,
    )
    if converted.returncode:
        return converted.returncode
    tls_dir = Path(os.getenv("TOPIC4_MPSPDZ_TLS_DIR", "/tls"))
    for name in ("P0.pem", "P1.pem", "P2.pem"):
        source = tls_dir / name
        if not source.is_file():
            print("MP-SPDZ TLS material missing: %s" % name, file=sys.stderr)
            return 69
        target = player_data / name
        if target.exists() or target.is_symlink():
            target.unlink()
        target.symlink_to(source)
    key_name = "P%d.key" % party_index
    key_source = tls_dir / key_name
    if not key_source.is_file():
        print("local MP-SPDZ TLS private key is missing", file=sys.stderr)
        return 69
    key_target = player_data / key_name
    if key_target.exists() or key_target.is_symlink():
        key_target.unlink()
    key_target.symlink_to(key_source)
    subprocess.run(["c_rehash", str(player_data)], check=True, stdout=subprocess.DEVNULL)
    binary_name, program_prefix = TEMPLATES[template]
    program = "%s_r%d" % (program_prefix, recipient_mask)
    binary = Path("/opt/mp-spdz") / binary_name
    programs = work_dir / "Programs"
    if programs.exists() or programs.is_symlink():
        programs.unlink()
    programs.symlink_to("/opt/mp-spdz/Programs", target_is_directory=True)
    hosts = Path(os.getenv("TOPIC4_MPSPDZ_HOSTS", "/etc/topic4-mpspdz/hosts.txt"))
    if not binary.is_file() or not hosts.is_file():
        print("MP-SPDZ binary or fixed host map unavailable", file=sys.stderr)
        return 69
    engine_log = work_dir / "mp-spdz.log"
    command = [
        str(binary), str(party_index), program, "--ip-file-name", str(hosts),
        "--portnumbase", "5000", "-IF", str(player_data / "Input"), "-OF", ".",
    ]
    with engine_log.open("wb") as stream:
        completed = subprocess.run(command, cwd=str(work_dir), stdout=stream,
                                   stderr=subprocess.STDOUT, check=False)
    if completed.returncode:
        return 70
    output = engine_log.read_text(encoding="utf-8", errors="replace")
    released = bool(recipient_mask & (1 << party_index))
    result = {
        "contractVersion": "topic4.privacy.result/v1",
        "jobId": request["jobId"],
        "attemptId": request["attemptId"],
        "templateId": template,
        "partyId": party_id,
        "released": released,
        "securityProfile": "MALICIOUS_3PC_HONEST_MAJORITY",
    }
    if released:
        if template == "secure-sum-3p-v1":
            values = {int(i): int(v) for i, v in re.findall(r"TOPIC4_SUM\[(\d+)\]=(-?\d+)", output)}
            if len(values) < vector_length:
                print("engine output did not contain all sum lanes", file=sys.stderr)
                return 70
            result["value"] = [values[i] for i in range(vector_length)]
            if vector_length == 1:
                result["value"] = result["value"][0]
        elif template == "private-threshold-3p-v1":
            match = re.search(r"TOPIC4_THRESHOLD threshold=100 reached=(\d+)", output)
            if not match:
                print("engine output did not contain threshold result", file=sys.stderr)
                return 70
            result.update({"threshold": 100, "reached": match.group(1) == "1"})
        else:
            values = parse_statistics(output, vector_length)
            if values is None:
                print("engine output did not contain all statistic lanes", file=sys.stderr)
                return 70
            result["value"] = values
            if vector_length == 1:
                result["value"] = result["value"][0]
    else:
        result["value"] = None
    atomic(job_dir / "engine-result.json", result)
    atomic(job_dir / "engine-evidence.json", {
        "protocolExecutable": binary_name,
        "protocol": "malicious replicated %s" % ("binary circuit" if "bin" in binary_name else "ring"),
        "program": program,
        "recipientMask": recipient_mask,
        "programScheduleDigest": digest(Path("/opt/mp-spdz/Programs/Schedules") / (program + ".sch")),
        "localInputDigest": digest(input_path),
        "localPartyIndex": party_index,
        "tlsEnabled": True,
        "tlsCertificateDigests": {name: digest(tls_dir / name) for name in ("P0.pem", "P1.pem", "P2.pem")},
        "engineLogDigest": digest(engine_log),
        "protocolMessages": protocol_message_report(output),
        "plaintextFallback": False,
    })
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
