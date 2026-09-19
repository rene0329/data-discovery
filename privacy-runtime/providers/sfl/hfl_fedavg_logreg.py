#!/usr/bin/env python3
"""Pinned three-party SFL FedAvg logistic-regression adapter.

The production path is SPMD: KusciaDeployment starts the same registered program
in A/B/C, SecretFlow's production backend matches calls with a shared job name,
and every process reads only the staged CSV in its own domain.  There is no
single-process or plaintext fallback in the HTTP runtime.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.metadata
import json
import os
import re
import shutil
import sys
from pathlib import Path

PARTIES = ("A", "B", "C")
ROLES = {"A": "TRAINER", "B": "TRAINER", "C": "TRAINER"}
FIELDS = ["x1", "x2", "label"]
SOURCE_REVISION = "c383e40f665063d7f7d87e437a73e015f87c435c"
PROTOCOL_VERSION = "sfl-c383e40f/fedavg-logreg"
SECURITY_PROFILE = "SEMI_HONEST_FL"


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()


def tree_digest(path):
    root = Path(path)
    if root.is_file():
        return digest(root), root.stat().st_size, 1
    if not root.is_dir():
        raise ValueError("SFL did not create the local model shard")
    h = hashlib.sha256()
    total = 0
    files = 0
    for item in sorted(value for value in root.rglob("*") if value.is_file()):
        relative = item.relative_to(root).as_posix().encode("utf-8")
        payload = item.read_bytes()
        h.update(len(relative).to_bytes(8, "big"))
        h.update(relative)
        h.update(len(payload).to_bytes(8, "big"))
        h.update(payload)
        total += len(payload)
        files += 1
    if not files:
        raise ValueError("SFL created an empty local model shard")
    return "sha256:" + h.hexdigest(), total, files


def atomic(path, value):
    target = Path(path)
    temporary = target.with_suffix(target.suffix + ".tmp")
    temporary.write_text(json.dumps(value, sort_keys=True, indent=2, default=str) + "\n", encoding="utf-8")
    os.replace(str(temporary), str(target))


def private_work_directory():
    value = os.getenv("TOPIC4_JOB_WORK_DIR", "")
    path = Path(value)
    if not value or not path.is_absolute() or path.is_symlink() or not path.is_dir():
        raise OSError("runner-owned TOPIC4_JOB_WORK_DIR is unavailable")
    return path.resolve()


def model_builder():
    from tensorflow import keras
    # SFL's CSV loader presents feature columns as a named tensor mapping.  Keep
    # the Keras input signature aligned with that mapping instead of declaring
    # one anonymous two-column tensor, which Keras rejects when it receives
    # {"x1": ..., "x2": ...} from FLModel.
    inputs = {
        "x1": keras.Input(shape=(1,), name="x1"),
        "x2": keras.Input(shape=(1,), name="x2"),
    }
    features = keras.layers.Concatenate(name="features")(
        [inputs["x1"], inputs["x2"]]
    )
    prediction = keras.layers.Dense(1, activation="sigmoid", name="prediction")(features)
    model = keras.Model(inputs=inputs, outputs=prediction)
    model.compile(optimizer=keras.optimizers.SGD(learning_rate=0.05), loss="binary_crossentropy", metrics=["accuracy"])
    return model


def normalized_global_metrics(history):
    if not isinstance(history, dict) or not isinstance(history.get("global_history"), dict):
        raise RuntimeError("SFL returned an unsupported training history")
    metrics = {}
    for key, values in history["global_history"].items():
        if not isinstance(key, str) or not isinstance(values, (list, tuple)):
            raise RuntimeError("SFL returned an unsupported global metric series")
        metrics[key] = [float(item) for item in values]
    return metrics


def validated_context():
    path = Path(os.getenv("TOPIC4_KUSCIA_CONTEXT_FILE", "/var/run/topic4-kuscia/context.json"))
    context = json.loads(path.read_text(encoding="utf-8"))
    supplied = context.pop("contextDigest", None)
    actual = "sha256:" + hashlib.sha256(canonical(context)).hexdigest()
    if supplied != actual:
        raise OSError("Kuscia runtime context digest mismatch")
    context["contextDigest"] = supplied
    if (context.get("transport") != "KUSCIA_CLUSTER_MTLS_ENVOY"
            or os.getenv("TOPIC4_KUSCIA_TRANSPORT") != context["transport"]
            or os.getenv("TOPIC4_KUSCIA_DEPLOYMENT_ID") != context.get("servingId")):
        raise OSError("validated KusciaDeployment identity is unavailable")
    if not isinstance(context.get("sflClusterConfig"), dict) or not isinstance(context.get("spuClusterDef"), dict):
        raise OSError("Kuscia fed/SPU endpoints are unavailable")
    return context


def validate_request(request):
    if request.get("templateId") != "hfl-fedavg-logreg-3p-v1":
        raise ValueError("unexpected templateId")
    if request.get("protocolVersion") != PROTOCOL_VERSION or request.get("securityProfile") != SECURITY_PROFILE:
        raise ValueError("protocolVersion/securityProfile differs from the registered template")
    participants = request.get("participants")
    if not isinstance(participants, list) or len(participants) != 3:
        raise ValueError("fixed SFL template requires A/B/C")
    ids = [item.get("partyId") or item.get("domainId") for item in participants if isinstance(item, dict)]
    if ids != list(PARTIES):
        raise ValueError("participant order must be A,B,C")
    for party, item in zip(PARTIES, participants):
        if item.get("role") != ROLES[party]:
            raise ValueError("participant role differs from the registered template")
        if item.get("fields") != FIELDS:
            raise ValueError("participant fields must be [x1,x2,label] in registered order")
    local_party = os.getenv("TOPIC4_PARTY_ID", "")
    if local_party not in PARTIES:
        raise ValueError("TOPIC4_PARTY_ID must be A, B or C")
    policy = request.get("enginePolicy")
    if not isinstance(policy, dict) or set(policy) - {
            "labelColumn", "featureColumns", "epochs", "learningRate", "seed"}:
        raise ValueError("enginePolicy contains an unregistered field")
    if policy.get("labelColumn", "label") != "label":
        raise ValueError("registered model fixes labelColumn=label")
    if policy.get("featureColumns", ["x1", "x2"]) != ["x1", "x2"]:
        raise ValueError("registered model fixes featureColumns=[x1,x2]")
    learning_rate = policy.get("learningRate", 0.05)
    seed = policy.get("seed", 20260919)
    epochs = policy.get("epochs", 1)
    if (not isinstance(learning_rate, (int, float)) or isinstance(learning_rate, bool)
            or float(learning_rate) != 0.05):
        raise ValueError("registered model fixes learningRate=0.05")
    if not isinstance(seed, int) or isinstance(seed, bool) or seed != 20260919:
        raise ValueError("registered model fixes seed=20260919")
    if not isinstance(epochs, int) or isinstance(epochs, bool) or not 1 <= epochs <= 5:
        raise ValueError("epochs must be an integer between 1 and 5")
    recipients = request.get("resultRecipients")
    if (not isinstance(recipients, list) or not recipients
            or len(set(recipients)) != len(recipients)
            or any(item not in PARTIES for item in recipients)):
        raise ValueError("resultRecipients must be a non-empty A/B/C subset")
    return local_party, epochs


def prepare_local_csv(source, destination):
    with Path(source).open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames or any(name not in reader.fieldnames for name in FIELDS):
            raise ValueError("staged CSV is missing a registered SFL field")
        rows = list(reader)
    if not rows:
        raise ValueError("staged CSV must contain at least one record")
    with Path(destination).open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()
        for row in rows:
            values = [(row.get(name) or "").strip() for name in FIELDS]
            if any(value == "" for value in values):
                raise ValueError("registered SFL fields cannot be empty")
            try:
                float(values[0]); float(values[1])
            except ValueError as exc:
                raise ValueError("SFL feature fields must be numeric") from exc
            if values[2] not in ("0", "1", "0.0", "1.0"):
                raise ValueError("SFL label must be binary")
            writer.writerow(dict(zip(FIELDS, values)))


def execute(request, work_dir, context, local_party, epochs):
    import secretflow as sf
    from secretflow.security.aggregation import SPUAggregator
    from sfl.ml.nn import FLModel

    # In production mode SecretFlow requires every institution to run the same
    # code. The shared job_name makes these A/B/C SPMD processes one job.
    job_name = "topic4-%s-%s" % (request["jobId"], request["attemptId"])
    if not re.fullmatch(r"topic4-[A-Za-z0-9._:-]+-[A-Za-z0-9._:-]+", job_name):
        raise ValueError("invalid production job name")
    timeout_ms = min(int(request["timeoutSeconds"]) * 1000, 3_600_000)
    link_desc = {
        "connect_retry_times": 120,
        "connect_retry_interval_ms": 1000,
        "recv_timeout_ms": timeout_ms,
        "http_timeout_ms": timeout_ms,
        # Kuscia Cluster routes terminate mTLS in Envoy and expose an HTTP
        # upstream on port 80. BRPC's native baidu_std framing cannot traverse
        # that route, so both RayFed and SPU must use BRPC-over-HTTP.
        "brpc_channel_protocol": "http",
        "brpc_channel_connection_type": "pooled",
    }
    sf.init(
        cluster_config=context["sflClusterConfig"],
        ray_mode=False,
        cross_silo_comm_backend="brpc_link",
        cross_silo_comm_options=dict(link_desc, timeout_in_ms=timeout_ms),
        enable_waiting_for_other_parties_ready=True,
        job_name=job_name,
        log_to_driver=True,
    )
    model_root = Path(os.getenv("TOPIC4_MODEL_DIR", "/var/lib/topic4-privacy/models"))
    attempt_root = model_root / request["jobId"] / request["attemptId"]
    local_model = attempt_root / local_party / "logreg"
    try:
        devices = {party: sf.PYU(party) for party in PARTIES}
        spu = sf.SPU(context["spuClusterDef"], link_desc=link_desc,
                     id="topic4-sfl-aggregate-%s" % request["attemptId"])
        fed_model = FLModel(
            device_list=[devices[party] for party in PARTIES],
            model=model_builder,
            aggregator=SPUAggregator(spu),
            strategy="fed_avg_w",
            random_seed=20260919,
        )
        # The deterministic externalJobId makes this absolute pathname identical
        # in all three isolated pods; a PYU resolves it in its own domain.
        training_path = str(Path(work_dir) / "training.csv")
        data = {devices[party]: training_path for party in PARTIES}
        history = fed_model.fit(
            data, "label", epochs=epochs, batch_size=2, aggregate_freq=1,
            shuffle=False, random_seed=20260919,
        )
        paths = {
            devices[party]: str(attempt_root / party / "logreg")
            for party in PARTIES
        }
        fed_model.save_model(paths)
        model_digest, model_bytes, model_files = tree_digest(local_model)
        metrics = normalized_global_metrics(history)
        return metrics, {
            "modelReference": "runtime://%s/models/%s/%s/logreg" % (
                local_party, request["jobId"], request["attemptId"]),
            "modelDigest": model_digest,
            "modelBytes": model_bytes,
            "modelFiles": model_files,
        }
    except Exception:
        shutil.rmtree(attempt_root / local_party, ignore_errors=True)
        raise
    finally:
        sf.shutdown()


def self_test():
    # Build verification exercises pinned imports and the named Keras input
    # signature; it is deliberately not accepted as distributed availability
    # evidence.
    import secretflow as sf
    import tensorflow as tf
    import tqdm
    from secretflow.security.aggregation import SPUAggregator  # noqa: F401
    from sfl.ml.nn import FLModel  # noqa: F401
    model = model_builder()
    prediction = model({
        "x1": tf.constant([[0.0]], dtype=tf.float32),
        "x2": tf.constant([[1.0]], dtype=tf.float32),
    }, training=False)
    if (model is None or not getattr(sf, "__version__", "")
            or importlib.metadata.version("tqdm") != "4.67.1"
            or not getattr(tqdm, "__version__", "")
            or tuple(prediction.shape) != (1, 1)):
        raise SystemExit("SFL build check failed")
    print(json.dumps({"status": "build-ok", "distributed": False}, sort_keys=True))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("request", nargs="?")
    parser.add_argument("job_dir", nargs="?")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if not args.request or not args.job_dir:
        return 64
    request_path, job_dir = Path(args.request), Path(args.job_dir)
    try:
        request = json.loads(request_path.read_text(encoding="utf-8"))
        local_party, epochs = validate_request(request)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print("SFL request rejected: %s" % exc, file=sys.stderr)
        return 64
    try:
        context = validated_context()
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print("SFL Kuscia runtime unavailable: %s" % exc, file=sys.stderr)
        return 69
    local_input = Path(os.getenv("TOPIC4_JOB_INPUT", ""))
    if not local_input.is_file():
        print("runner did not stage an authorized local input", file=sys.stderr)
        return 69
    try:
        work_dir = private_work_directory()
        training_path = work_dir / "training.csv"
        prepare_local_csv(local_input, training_path)
        metrics, model = execute(request, work_dir, context, local_party, epochs)
    except ValueError as exc:
        print("SFL input rejected: %s" % exc, file=sys.stderr)
        return 64
    except (ImportError, OSError, RuntimeError) as exc:
        print("SFL distributed execution failed: %s" % type(exc).__name__, file=sys.stderr)
        return 70
    except Exception as exc:
        print("SFL distributed execution failed: %s" % type(exc).__name__, file=sys.stderr)
        return 70
    released = local_party in request["resultRecipients"]
    result = {
        "contractVersion": "topic4.privacy.result/v1",
        "jobId": request["jobId"], "attemptId": request["attemptId"],
        "templateId": request["templateId"], "partyId": local_party,
        "released": released, "securityProfile": SECURITY_PROFILE,
        "model": model,
        "metrics": metrics if released else None,
    }
    atomic(job_dir / "engine-result.json", result)
    atomic(job_dir / "engine-evidence.json", {
        "framework": "SFL", "frameworkVersion": importlib.metadata.version("sfl"),
        "sourceRevision": SOURCE_REVISION,
        "strategy": "fed_avg_w", "aggregator": "SPUAggregator",
        "executionMode": "KusciaDeployment SecretFlow production SPMD",
        "transport": context["transport"], "servingId": context["servingId"],
        "kusciaContextDigest": context["contextDigest"],
        "localInputDigest": digest(local_input), "localModel": model,
        "plaintextFallback": False,
    })
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
