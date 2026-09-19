#!/usr/bin/env python3
"""Fixed Topic4 adapters for SecretFlow 1.11 HEU and SecureBoost.

Every party executes this file at the same time.  SecretFlow production mode
matches the identical task graph across the two processes and transports only
device objects over mutually authenticated cross-silo channels.  This module
never substitutes a local plaintext implementation when SecretFlow is absent.
"""
from __future__ import annotations

import csv
import hashlib
import json
import math
import os
import re
import shutil
import sys
from decimal import Decimal, InvalidOperation, ROUND_HALF_EVEN
from pathlib import Path
from typing import Any, Dict, List, Mapping, Sequence, Tuple


SOURCE_REVISION = "b9d6fd5ca9dbdfc95cfda5c282e93022e1caebc4"
EXPECTED_VERSION = "1.11.0b1"
PARTIES = ("A", "B")
LOCAL_ENDPOINT_RE = re.compile(r"^0\.0\.0\.0:([1-9][0-9]{0,4})$")
KUSCIA_FED_ENDPOINT_RE = re.compile(
    r"^[a-z0-9](?:[-a-z0-9.]{0,252}[a-z0-9])?:80$"
)
KUSCIA_SPU_ENDPOINT_RE = re.compile(
    r"^http://[a-z0-9](?:[-a-z0-9.]{0,252}[a-z0-9])?:80$"
)
IDENTIFIER_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
TEMPLATES = {
    "he-paillier-2p-v1": {
        "securityProfile": "SEMI_HONEST_HE",
        "protocolVersion": "secretflow-1.11.0b1/heu-paillier",
        "roles": {"A": "KEY_HOLDER", "B": "DATA_HOLDER"},
    },
    "vfl-secureboost-2p-v1": {
        "securityProfile": "SEMI_HONEST_HE",
        "protocolVersion": "secretflow-1.11.0b1/secureboost-heu",
        "roles": {"A": "ACTIVE", "B": "PASSIVE"},
    },
}


class ContractError(ValueError):
    """The frozen task does not match a registered template."""


class EngineUnavailable(RuntimeError):
    """The pinned engine or deployment configuration is absent."""


def canonical(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o600)
    os.replace(str(temporary), str(path))


def digest_file(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return "sha256:" + value.hexdigest()


def digest_tree(path: Path) -> Tuple[str, List[Dict[str, Any]]]:
    value = hashlib.sha256()
    files: List[Dict[str, Any]] = []
    for item in sorted(candidate for candidate in path.rglob("*") if candidate.is_file()):
        relative = item.relative_to(path).as_posix()
        item_digest = digest_file(item)
        size = item.stat().st_size
        files.append({"name": relative, "sha256": item_digest, "sizeBytes": size})
        value.update(relative.encode("utf-8"))
        value.update(b"\0")
        value.update(item_digest.encode("ascii"))
        value.update(b"\0")
        value.update(str(size).encode("ascii"))
        value.update(b"\n")
    if not files:
        raise RuntimeError("SecretFlow did not persist a local model shard")
    return "sha256:" + value.hexdigest(), files


def remove_file(path: Path) -> None:
    try:
        path.unlink(missing_ok=True)
    except OSError:
        pass


def private_work_directory() -> Path:
    value = os.getenv("TOPIC4_JOB_WORK_DIR", "")
    path = Path(value)
    if not value or not path.is_absolute() or path.is_symlink() or not path.is_dir():
        raise EngineUnavailable("runner-owned TOPIC4_JOB_WORK_DIR is unavailable")
    return path.resolve()


def require_int(value: Any, name: str, minimum: int, maximum: int) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or not minimum <= value <= maximum:
        raise ContractError("%s must be an integer from %d through %d" % (name, minimum, maximum))
    return value


def require_number(value: Any, name: str, minimum: float, maximum: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError("%s must be numeric" % name)
    converted = float(value)
    if not math.isfinite(converted) or not minimum <= converted <= maximum:
        raise ContractError("%s is outside the registered range" % name)
    return converted


def participant_id(item: Mapping[str, Any]) -> Any:
    return item.get("partyId") or item.get("domainId")


def validate_request(request: Any, local_party: str) -> Dict[str, Any]:
    if not isinstance(request, dict):
        raise ContractError("request.json must contain an object")
    template_id = request.get("templateId")
    if template_id not in TEMPLATES:
        raise ContractError("template is not implemented by the SecretFlow adapter")
    template = TEMPLATES[template_id]
    if request.get("securityProfile") != template["securityProfile"]:
        raise ContractError("securityProfile differs from the registered template")
    if request.get("protocolVersion") != template["protocolVersion"]:
        raise ContractError("protocolVersion differs from the registered template")
    participants = request.get("participants")
    if not isinstance(participants, list) or len(participants) != 2:
        raise ContractError("SecretFlow templates require exactly two participants")
    by_party: Dict[str, Dict[str, Any]] = {}
    for item in participants:
        if not isinstance(item, dict):
            raise ContractError("participants must be objects")
        party = participant_id(item)
        if party not in PARTIES or party in by_party:
            raise ContractError("participants must be the fixed A/B domains")
        if item.get("role") != template["roles"][party]:
            raise ContractError("participant role differs from the registered template")
        fields = item.get("fields")
        if not isinstance(fields, list) or not fields or not all(
            isinstance(field, str) and IDENTIFIER_RE.fullmatch(field) for field in fields
        ):
            raise ContractError("every participant requires fixed CSV field bindings")
        if len(set(fields)) != len(fields):
            raise ContractError("participant field bindings contain duplicates")
        by_party[party] = item
    if set(by_party) != set(PARTIES) or local_party not in by_party:
        raise ContractError("local party is absent from the fixed A/B task")
    index = os.getenv("TOPIC4_PARTY_INDEX", "")
    if index:
        if index not in {"0", "1"} or participant_id(participants[int(index)]) != local_party:
            raise ContractError("local party index differs from participant ordering")
    recipients = request.get("resultRecipients")
    if (
        not isinstance(recipients, list)
        or not recipients
        or len(set(recipients)) != len(recipients)
        or any(party not in PARTIES for party in recipients)
    ):
        raise ContractError("resultRecipients must be a non-empty subset of A/B")
    policy = request.get("enginePolicy")
    if not isinstance(policy, dict):
        raise ContractError("enginePolicy must be an object")
    normalized = dict(request)
    normalized["participantsByParty"] = by_party
    if template_id == "he-paillier-2p-v1":
        if set(policy) != {"operation", "scale"}:
            raise ContractError("HE policy must contain exactly operation and scale")
        operation = policy.get("operation")
        if operation not in {"ADD", "PLAINTEXT_MULTIPLY", "DOT_PRODUCT"}:
            raise ContractError("HE operation is not registered")
        scale = require_int(policy.get("scale"), "scale", 1, 1_000_000)
        if any(by_party[party]["fields"] != ["value"] for party in PARTIES):
            raise ContractError("HE template fixes the bound field to value")
        normalized["normalizedPolicy"] = {"operation": operation, "scale": scale}
    else:
        expected = {"labelColumn", "featureColumns", "epochs", "learningRate", "seed"}
        if set(policy) != expected:
            raise ContractError("VFL policy fields differ from the fixed template")
        label = policy.get("labelColumn")
        features = policy.get("featureColumns")
        if not isinstance(label, str) or not IDENTIFIER_RE.fullmatch(label):
            raise ContractError("labelColumn must be a CSV identifier")
        if (
            not isinstance(features, list)
            or not 1 <= len(features) <= 256
            or len(set(features)) != len(features)
            or not all(isinstance(item, str) and IDENTIFIER_RE.fullmatch(item) for item in features)
            or label in features
        ):
            raise ContractError("featureColumns must contain unique CSV identifiers without the label")
        if label not in by_party["A"]["fields"] or label in by_party["B"]["fields"]:
            raise ContractError("the ACTIVE party A must be the only label owner")
        owners: Dict[str, str] = {}
        for feature in features:
            matches = [party for party in PARTIES if feature in by_party[party]["fields"]]
            if len(matches) != 1:
                raise ContractError("every VFL feature must be bound to exactly one party")
            owners[feature] = matches[0]
        if set(owners.values()) != set(PARTIES):
            raise ContractError("SecureBoost requires at least one feature from each party")
        if any("id" not in by_party[party]["fields"] for party in PARTIES):
            raise ContractError("VFL fixes id as the PSI alignment key for both parties")
        normalized["normalizedPolicy"] = {
            "labelColumn": label,
            "featureColumns": list(features),
            "featureOwners": owners,
            "epochs": require_int(policy.get("epochs"), "epochs", 1, 5),
            "learningRate": require_number(
                policy.get("learningRate"), "learningRate", 0.000001, 1.0
            ),
            "seed": require_int(policy.get("seed"), "seed", 0, 2_147_483_647),
        }
    return normalized


def load_kuscia_context(path: Path, expected_party: str | None = None) -> Dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise EngineUnavailable("validated Kuscia runtime context is unavailable") from exc
    if not isinstance(value, dict):
        raise EngineUnavailable("validated Kuscia runtime context must be an object")
    supplied_digest = value.pop("contextDigest", None)
    actual_digest = "sha256:" + hashlib.sha256(canonical(value)).hexdigest()
    value["contextDigest"] = supplied_digest
    if supplied_digest != actual_digest:
        raise EngineUnavailable("Kuscia runtime context digest mismatch")
    deployment_id = value.get("servingId")
    runtime_party = value.get("partyId")
    if (
        value.get("transport") != "KUSCIA_CLUSTER_MTLS_ENVOY"
        or os.getenv("TOPIC4_KUSCIA_TRANSPORT") != value.get("transport")
        or not isinstance(deployment_id, str)
        or not deployment_id
        or os.getenv("TOPIC4_KUSCIA_DEPLOYMENT_ID") != deployment_id
    ):
        raise EngineUnavailable("runtime is not a validated KusciaDeployment workload")
    if not isinstance(runtime_party, str) or not runtime_party:
        raise EngineUnavailable("Kuscia runtime party identity is unavailable")
    if expected_party is not None and runtime_party != expected_party:
        raise EngineUnavailable("Kuscia runtime party differs from the task party")

    endpoints = value.get("secretflowEndpoints")
    if not isinstance(endpoints, dict):
        raise EngineUnavailable("Kuscia SecretFlow endpoints are unavailable")
    fed = endpoints.get("fed")
    spu_endpoints = endpoints.get("spu")
    if (
        not isinstance(fed, dict)
        or not isinstance(spu_endpoints, dict)
        or set(fed) != set(spu_endpoints)
        or not set(PARTIES).issubset(fed)
        or runtime_party not in fed
    ):
        raise EngineUnavailable("Kuscia SecretFlow endpoint topology is invalid")
    psi_endpoints = value.get("engineEndpoints")
    if not isinstance(psi_endpoints, dict) or set(psi_endpoints) != set(fed):
        raise EngineUnavailable("Kuscia PSI endpoint topology is invalid")
    for party in fed:
        is_local = party == runtime_party
        fed_value = fed[party]
        spu_value = spu_endpoints[party]
        if not isinstance(fed_value, str) or not isinstance(spu_value, str):
            raise EngineUnavailable("Kuscia SecretFlow endpoint is invalid")
        if is_local:
            fed_match = LOCAL_ENDPOINT_RE.fullmatch(fed_value)
            spu_match = LOCAL_ENDPOINT_RE.fullmatch(spu_value)
            if (
                not fed_match
                or not spu_match
                or int(fed_match.group(1)) > 65535
                or int(spu_match.group(1)) > 65535
            ):
                raise EngineUnavailable("local SecretFlow endpoint is not an allocated port")
        elif (
            not KUSCIA_FED_ENDPOINT_RE.fullmatch(fed_value)
            or not KUSCIA_SPU_ENDPOINT_RE.fullmatch(spu_value)
        ):
            raise EngineUnavailable("remote SecretFlow endpoint is not a Kuscia Cluster route")
        psi_value = psi_endpoints[party]
        psi_local_match = (
            LOCAL_ENDPOINT_RE.fullmatch(psi_value) if isinstance(psi_value, str) else None
        )
        if (
            not isinstance(psi_value, str)
            or (is_local and (not psi_local_match or int(psi_local_match.group(1)) > 65535))
            or (not is_local and not KUSCIA_SPU_ENDPOINT_RE.fullmatch(psi_value))
        ):
            raise EngineUnavailable("PSI endpoint is not a Kuscia Cluster route")
    return {
        "contextDigest": supplied_digest,
        "deploymentId": deployment_id,
        "partyId": runtime_party,
        "transport": value["transport"],
        "fed": dict(fed),
        "spu": dict(spu_endpoints),
        "psi": dict(psi_endpoints),
    }


def validate_input_path() -> Path:
    value = os.getenv("TOPIC4_JOB_INPUT", "")
    path = Path(value)
    if not value or not path.is_file() or path.is_symlink():
        raise EngineUnavailable("runner did not stage an authorized regular input file")
    return path


def decimal_to_scaled(value: str, scale: int) -> int:
    try:
        parsed = Decimal(value.strip())
    except InvalidOperation as exc:
        raise ContractError("HE value column contains a non-numeric value") from exc
    if not parsed.is_finite():
        raise ContractError("HE value column contains a non-finite value")
    scaled = (parsed * Decimal(scale)).to_integral_value(rounding=ROUND_HALF_EVEN)
    if parsed * Decimal(scale) != scaled:
        raise ContractError("HE value cannot be represented exactly at the selected scale")
    result = int(scaled)
    if abs(result) > (1 << 52):
        raise ContractError("scaled HE value exceeds the registered numeric bound")
    return result


def read_he_values(path: Path, scale: int) -> List[int]:
    try:
        with path.open(newline="", encoding="utf-8-sig") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != ["value"]:
                raise ContractError("HE input CSV must contain only the value column")
            values = [decimal_to_scaled(row.get("value", ""), scale) for row in reader]
    except (OSError, UnicodeError, csv.Error) as exc:
        raise ContractError("HE input is not a readable CSV") from exc
    if not 1 <= len(values) <= 100_000:
        raise ContractError("HE input must contain between 1 and 100000 values")
    return values


def validate_vfl_csv(
    path: Path, party: str, participant: Mapping[str, Any], policy: Mapping[str, Any]
) -> int:
    required = {"id"}
    required.update(
        feature for feature, owner in policy["featureOwners"].items() if owner == party
    )
    if party == "A":
        required.add(policy["labelColumn"])
    if not required.issubset(set(participant["fields"])):
        raise ContractError("VFL field bindings do not include the fixed local columns")
    count = 0
    ids = set()
    labels = set()
    try:
        with path.open(newline="", encoding="utf-8-sig") as stream:
            reader = csv.DictReader(stream)
            if not reader.fieldnames or not required.issubset(set(reader.fieldnames)):
                raise ContractError("VFL input CSV is missing a bound column")
            for row in reader:
                key = (row.get("id") or "").strip()
                if not key or key in ids:
                    raise ContractError("VFL PSI keys must be non-empty and unique")
                ids.add(key)
                for feature, owner in policy["featureOwners"].items():
                    if owner == party:
                        try:
                            value = float((row.get(feature) or "").strip())
                        except ValueError as exc:
                            raise ContractError("VFL feature columns must be numeric") from exc
                        if not math.isfinite(value):
                            raise ContractError("VFL feature columns must be finite")
                if party == "A":
                    label = (row.get(policy["labelColumn"]) or "").strip()
                    if label not in {"0", "1", "0.0", "1.0"}:
                        raise ContractError("SecureBoost requires binary labels 0/1")
                    labels.add(int(float(label)))
                count += 1
    except (OSError, UnicodeError, csv.Error) as exc:
        raise ContractError("VFL input is not a readable CSV") from exc
    if not 2 <= count <= 1_000_000:
        raise ContractError("VFL input row count is outside the registered bound")
    if party == "A" and labels != {0, 1}:
        raise ContractError("SecureBoost requires both binary label classes")
    return count


def _read_scaled_numpy(path: str, scale: int):
    import numpy as np

    return np.asarray(read_he_values(Path(path), scale), dtype=np.int64)


def _save_he_release(values, output_path: str, divisor: int, metadata: Mapping[str, Any]):
    import numpy as np

    array = np.asarray(values).reshape(-1)
    converted = [float(item) / divisor for item in array.tolist()]
    normalized = [int(value) if value.is_integer() else value for value in converted]
    result: Any = normalized[0] if metadata["operation"] == "DOT_PRODUCT" else normalized
    envelope = {
        "operation": metadata["operation"],
        "scale": metadata["scale"],
        "value": result,
        "valueCount": len(normalized),
    }
    atomic_json(Path(output_path), envelope)
    return None


def _compute_binary_metrics(predictions, aligned_path: str, label_column: str):
    import numpy as np
    import pandas as pd

    probabilities = np.asarray(predictions, dtype=float).reshape(-1)
    labels = pd.read_csv(aligned_path, usecols=[label_column])[label_column].to_numpy(dtype=float)
    if labels.shape[0] != probabilities.shape[0]:
        raise ValueError("prediction and label row counts differ")
    clipped = np.clip(probabilities, 1e-12, 1 - 1e-12)
    accuracy = float(np.mean((probabilities >= 0.5).astype(int) == labels.astype(int)))
    log_loss = float(-np.mean(labels * np.log(clipped) + (1 - labels) * np.log(1 - clipped)))
    positive = probabilities[labels == 1]
    negative = probabilities[labels == 0]
    comparisons = (positive[:, None] > negative[None, :]).sum()
    ties = (positive[:, None] == negative[None, :]).sum()
    auc = float((comparisons + 0.5 * ties) / (len(positive) * len(negative)))
    return {
        "rowCount": int(labels.shape[0]),
        "accuracy": accuracy,
        "logLoss": log_loss,
        "rocAuc": auc,
    }


def _model_manifest(path: str, reference: str, party: str):
    root = Path(path)
    tree_digest, files = digest_tree(root)
    return {
        "partyId": party,
        "reference": reference,
        "sha256": tree_digest,
        "fileCount": len(files),
        "sizeBytes": sum(item["sizeBytes"] for item in files),
    }


def _save_vfl_release(
    predictions,
    metrics: Mapping[str, Any],
    model_manifests: Sequence[Mapping[str, Any]],
    envelope_path: str,
    prediction_path: str,
    prediction_reference: str,
    metadata: Mapping[str, Any],
):
    import numpy as np

    values = np.asarray(predictions, dtype=float).reshape(-1)
    artifact = Path(prediction_path)
    artifact.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = artifact.with_suffix(".csv.tmp")
    with temporary.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["row", "probability"])
        for index, value in enumerate(values.tolist()):
            writer.writerow([index, format(float(value), ".17g")])
        stream.flush()
        os.fsync(stream.fileno())
    os.chmod(temporary, 0o600)
    os.replace(str(temporary), str(artifact))
    envelope = {
        "modelReferences": list(model_manifests),
        "predictionReference": prediction_reference,
        "predictionSha256": digest_file(artifact),
        "predictionRows": int(values.size),
        "metrics": dict(metrics),
        "alignment": "RR22_FAST_PSI_2PC",
        "training": dict(metadata),
    }
    atomic_json(Path(envelope_path), envelope)
    return None


def _initialize_secretflow(
    request: Mapping[str, Any], local_party: str, config: Mapping[str, Any]
):
    try:
        import secretflow as sf
        import spu
        from secretflow.device.device.heu import HEUMoveConfig
        from secretflow.ml.boost.sgb_v import Sgb
    except (ImportError, OSError) as exc:
        raise EngineUnavailable("SecretFlow 1.11 runtime modules are unavailable") from exc
    if getattr(sf, "__version__", None) != EXPECTED_VERSION:
        raise EngineUnavailable("SecretFlow runtime version differs from 1.11.0b1")
    cluster_config = {
        "parties": {
            party: ({"address": config["fed"][party], "listen_addr": config["fed"][party]}
                    if party == local_party else {"address": config["fed"][party]})
            for party in PARTIES
        },
        "self_party": local_party,
    }
    timeout_ms = min(int(request["timeoutSeconds"]) * 1000, 3_600_000)
    sf.init(
        ray_mode=False,
        cluster_config=cluster_config,
        # Kuscia terminates and originates cross-domain mTLS at its Cluster
        # Envoy route.  The application endpoints are deliberately not given a
        # second keypair or a direct network path.
        tls_config=None,
        job_name="topic4-%s" % request["attemptId"],
        cross_silo_comm_backend="brpc_link",
        cross_silo_comm_options={
            "timeout_in_ms": timeout_ms,
            "messages_max_size_in_bytes": 64 * 1024 * 1024,
            "exit_on_sending_failure": True,
            "connect_retry_times": 120,
            "connect_retry_interval_ms": 1000,
            "recv_timeout_ms": timeout_ms,
            "http_timeout_ms": timeout_ms,
        },
        enable_waiting_for_other_parties_ready=True,
        logging_level="warning",
    )
    devices = {party: sf.PYU(party) for party in PARTIES}
    heu_config = {
        "sk_keeper": {"party": "A"},
        "evaluators": [{"party": "B"}],
        "mode": "PHEU",
        "encoding": {"cleartext_type": "DT_I64", "encoder": "BigintEncoder"},
        "he_parameters": {
            "schema": "paillier",
            "key_pair": {"generate": {"bit_size": 2048}},
        },
    }
    heu = sf.HEU(heu_config, spu.spu_pb2.FM128)
    psi_spu = None
    if request["templateId"] == "vfl-secureboost-2p-v1":
        spu_def = {
            "nodes": [
                ({"party": party, "address": config["spu"][party],
                  "listen_address": config["spu"][party]}
                 if party == local_party
                 else {"party": party, "address": config["spu"][party]})
                for party in PARTIES
            ],
            "runtime_config": {"protocol": "SEMI2K", "field": "FM128"},
        }
        psi_spu = sf.SPU(
            spu_def,
            link_desc={
                "connect_retry_times": 120,
                "connect_retry_interval_ms": 1000,
                "recv_timeout_ms": timeout_ms,
                "http_timeout_ms": timeout_ms,
            },
            id="topic4-psi-%s" % request["attemptId"],
        )
    return sf, HEUMoveConfig, Sgb, devices, heu, psi_spu, config


def execute_he(
    request: Mapping[str, Any], work_dir: Path, local_party: str, context: Tuple[Any, ...]
) -> Dict[str, Any]:
    sf, HEUMoveConfig, _sgb, devices, heu, _psi_spu, runtime = context
    policy = request["normalizedPolicy"]
    source = str(validate_input_path())
    scale = policy["scale"]
    vectors = {
        party: devices[party](_read_scaled_numpy)(source, scale) for party in PARTIES
    }
    encrypted = vectors["A"].to(heu, config=HEUMoveConfig(heu_dest_party="B"))
    clear = vectors["B"].to(heu, config=HEUMoveConfig(heu_dest_party="B"))
    operation = policy["operation"]
    if operation == "ADD":
        encrypted_result = encrypted + clear
        divisor = scale
    elif operation == "PLAINTEXT_MULTIPLY":
        encrypted_result = encrypted * clear
        divisor = scale * scale
    else:
        encrypted_result = (encrypted * clear).sum()
        divisor = scale * scale
    decrypted = encrypted_result.to(devices["A"])
    release_handles = []
    for recipient in request["resultRecipients"]:
        envelope = work_dir / ("release-%s.json" % recipient)
        remove_file(envelope)
        release_handles.append(
            devices[recipient](_save_he_release)(
                decrypted.to(devices[recipient]),
                str(envelope),
                divisor,
                {"operation": operation, "scale": scale},
            )
        )
    sf.wait(release_handles)
    local_release = work_dir / ("release-%s.json" % local_party)
    released = local_party in request["resultRecipients"]
    if released and not local_release.is_file():
        raise RuntimeError("authorized HE result was not materialized at its recipient")
    value = json.loads(local_release.read_text(encoding="utf-8")) if released else None
    for party in PARTIES:
        remove_file(work_dir / ("release-%s.json" % party))
    return {
        "released": released,
        "result": value,
        "evidence": {
            "framework": "SecretFlow",
            "frameworkVersion": EXPECTED_VERSION,
            "sourceRevision": SOURCE_REVISION,
            "device": "HEU/PHEU",
            "scheme": "Paillier",
            "keySizeBits": 2048,
            "keyKeeper": "A",
            "ciphertextEvaluator": "B",
            "operation": operation,
            "fixedPointScale": scale,
            "freshKeyPerAttempt": True,
            "kusciaContextDigest": runtime["contextDigest"],
            "kusciaDeploymentId": runtime["deploymentId"],
            "transport": runtime["transport"],
        },
    }


def execute_vfl(
    request: Mapping[str, Any], job_dir: Path, work_dir: Path, local_party: str,
    context: Tuple[Any, ...]
) -> Dict[str, Any]:
    sf, _move_config, Sgb, devices, heu, psi_spu, runtime = context
    from secretflow.data.vertical import read_csv

    policy = request["normalizedPolicy"]
    source = str(validate_input_path())
    aligned = work_dir / "aligned-private.csv"
    remove_file(aligned)
    paths = {device: source for device in devices.values()}
    aligned_paths = {device: str(aligned) for device in devices.values()}
    reports = psi_spu.psi_csv(
        key="id",
        input_path=paths,
        output_path=aligned_paths,
        receiver="A",
        protocol="RR22_FAST_PSI_2PC",
        precheck_input=True,
        sort=True,
        broadcast_result=True,
    )
    if not aligned.is_file():
        raise RuntimeError("RR22 PSI did not materialize the local aligned input")
    aligned_count = validate_vfl_csv(
        aligned,
        local_party,
        request["participantsByParty"][local_party],
        policy,
    )
    usecols: Dict[Any, List[str]] = {}
    for party, device in devices.items():
        columns = [
            feature for feature in policy["featureColumns"] if policy["featureOwners"][feature] == party
        ]
        if party == "A":
            columns.append(policy["labelColumn"])
        usecols[device] = columns
    vertical = read_csv(aligned_paths, usecols=usecols)
    labels = vertical[policy["labelColumn"]].values
    features = vertical[policy["featureColumns"]].values
    params = {
        "tree_growing_method": "level",
        "num_boost_round": policy["epochs"],
        "max_depth": 2,
        "max_leaf": 4,
        "sketch_eps": 0.25,
        "objective": "logistic",
        "learning_rate": policy["learningRate"],
        "reg_lambda": 1.0,
        "gamma": 0.0,
        "rowsample_by_tree": 1.0,
        "colsample_by_tree": 1.0,
        "base_score": 0.5,
        "seed": policy["seed"],
        "fixed_point_parameter": 20,
        "verbose": False,
        "wait_execution": False,
        "first_tree_with_label_holder_feature": True,
        "enable_goss": False,
        "enable_quantization": True,
        "enable_packbits": False,
        "batch_encoding_enabled": True,
        "enable_monitor": False,
        "enable_early_stop": False,
    }
    model = Sgb(heu).train(params, features, labels)
    model_root = Path(
        os.getenv("TOPIC4_SECRETFLOW_MODEL_ROOT", "/var/lib/topic4-privacy/models")
    )
    if not model_root.is_absolute():
        raise EngineUnavailable("TOPIC4_SECRETFLOW_MODEL_ROOT must be absolute")
    shard_bases = {
        devices[party]: str(
            model_root / request["jobId"] / request["attemptId"] / party / "secureboost"
        )
        for party in PARTIES
    }
    model.save_model(shard_bases)
    predictions = model.predict(features)
    metrics = devices["A"](_compute_binary_metrics)(
        predictions, str(aligned), policy["labelColumn"]
    )
    manifests = {}
    for party, device in devices.items():
        reference = "party-model://%s/%s/%s/secureboost" % (
            party,
            request["jobId"],
            request["attemptId"],
        )
        manifests[party] = device(_model_manifest)(shard_bases[device], reference, party)
    release_handles = []
    for recipient in request["resultRecipients"]:
        envelope = work_dir / ("release-%s.json" % recipient)
        artifact = job_dir / "artifacts" / ("predictions-%s.csv" % recipient)
        remove_file(envelope)
        prediction_reference = "party-artifact://%s/%s/%s/predictions.csv" % (
            recipient,
            request["jobId"],
            request["attemptId"],
        )
        release_handles.append(
            devices[recipient](_save_vfl_release)(
                predictions.to(devices[recipient]),
                metrics.to(devices[recipient]),
                [manifests[party].to(devices[recipient]) for party in PARTIES],
                str(envelope),
                str(artifact),
                prediction_reference,
                {
                    "algorithm": "SecureBoost",
                    "objective": "binary:logistic",
                    "epochs": policy["epochs"],
                    "learningRate": policy["learningRate"],
                    "seed": policy["seed"],
                },
            )
        )
    sf.wait(release_handles)
    local_release = work_dir / ("release-%s.json" % local_party)
    released = local_party in request["resultRecipients"]
    if released and not local_release.is_file():
        raise RuntimeError("authorized VFL result was not materialized at its recipient")
    value = json.loads(local_release.read_text(encoding="utf-8")) if released else None
    for party in PARTIES:
        remove_file(work_dir / ("release-%s.json" % party))
    intersection_count = next(
        (
            int(report.get("intersection_count"))
            for report in reports
            if report.get("party") == "A" and int(report.get("intersection_count", -1)) >= 0
        ),
        aligned_count,
    )
    return {
        "released": released,
        "result": value,
        "evidence": {
            "framework": "SecretFlow",
            "frameworkVersion": EXPECTED_VERSION,
            "sourceRevision": SOURCE_REVISION,
            "alignmentProtocol": "RR22_FAST_PSI_2PC",
            "intersectionCount": intersection_count,
            "trainer": "SecureBoost",
            "secureDevice": "HEU/PHEU",
            "scheme": "Paillier",
            "keySizeBits": 2048,
            "labelHolder": "A",
            "modelShardsStayAtOwner": True,
            "freshKeyPerAttempt": True,
            "kusciaContextDigest": runtime["contextDigest"],
            "kusciaDeploymentId": runtime["deploymentId"],
            "transport": runtime["transport"],
        },
    }


def write_outputs(
    request: Mapping[str, Any], job_dir: Path, local_party: str, execution: Mapping[str, Any], input_path: Path
) -> None:
    result = {
        "contractVersion": "topic4.privacy.result/v1",
        "jobId": request["jobId"],
        "attemptId": request["attemptId"],
        "templateId": request["templateId"],
        "partyId": local_party,
        "released": execution["released"],
        "securityProfile": request["securityProfile"],
        "protocolVersion": request["protocolVersion"],
        "result": execution["result"] if execution["released"] else None,
        "plaintextFallback": False,
    }
    evidence = {
        **execution["evidence"],
        "partyId": local_party,
        "localInputDigest": digest_file(input_path),
        "resultRecipients": request["resultRecipients"],
        "released": execution["released"],
        "plaintextFallback": False,
    }
    atomic_json(job_dir / "engine-result.json", result)
    atomic_json(job_dir / "engine-evidence.json", evidence)


def main(argv: Sequence[str] = sys.argv) -> int:
    if len(argv) != 3:
        return 64
    request_path, job_dir = map(Path, argv[1:])
    local_party = os.getenv("TOPIC4_PARTY_ID", "")
    if local_party not in PARTIES:
        print("TOPIC4_PARTY_ID must be A or B", file=sys.stderr)
        return 69
    request: Dict[str, Any] = {}
    try:
        request = json.loads(request_path.read_text(encoding="utf-8"))
        request = validate_request(request, local_party)
        input_path = validate_input_path()
        work_dir = private_work_directory()
        if request["templateId"] == "he-paillier-2p-v1":
            read_he_values(input_path, request["normalizedPolicy"]["scale"])
        else:
            validate_vfl_csv(
                input_path,
                local_party,
                request["participantsByParty"][local_party],
                request["normalizedPolicy"],
            )
        context_path = Path(
            os.getenv(
                "TOPIC4_KUSCIA_CONTEXT_FILE", "/var/run/topic4-kuscia/context.json"
            )
        )
        config = load_kuscia_context(context_path, local_party)
    except ContractError as exc:
        print("SecretFlow task rejected: %s" % exc, file=sys.stderr)
        return 64
    except EngineUnavailable as exc:
        print("SecretFlow engine unavailable: %s" % exc, file=sys.stderr)
        return 69
    except (OSError, UnicodeError, ValueError):
        print("SecretFlow task request is unreadable", file=sys.stderr)
        return 64
    context = None
    succeeded = False
    try:
        context = _initialize_secretflow(request, local_party, config)
        if request["templateId"] == "he-paillier-2p-v1":
            execution = execute_he(request, work_dir, local_party, context)
        else:
            execution = execute_vfl(request, job_dir, work_dir, local_party, context)
        write_outputs(request, job_dir, local_party, execution, input_path)
        succeeded = True
        return 0
    except EngineUnavailable as exc:
        print("SecretFlow engine unavailable: %s" % exc, file=sys.stderr)
        return 69
    except ContractError as exc:
        print("SecretFlow task rejected: %s" % exc, file=sys.stderr)
        return 64
    except Exception as exc:
        # Exception text from data libraries can contain values or paths.  Only
        # emit the type; detailed protocol state belongs in the evidence digest.
        print("SecretFlow protocol failed: %s" % type(exc).__name__, file=sys.stderr)
        return 70
    finally:
        if context is not None:
            try:
                context[0].shutdown(barrier_on_shutdown=False, on_error=not succeeded)
            except Exception:
                pass
        if "work_dir" in locals():
            remove_file(work_dir / "aligned-private.csv")
        if not succeeded and request.get("templateId") == "vfl-secureboost-2p-v1":
            model_root = Path(
                os.getenv("TOPIC4_SECRETFLOW_MODEL_ROOT", "/var/lib/topic4-privacy/models")
            )
            failed_root = model_root / str(request.get("jobId", "")) / str(request.get("attemptId", ""))
            if model_root.is_absolute() and failed_root.parent.parent == model_root:
                shutil.rmtree(failed_root, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
