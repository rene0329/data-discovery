#!/usr/bin/env python3
"""Two-process production smoke for the pinned SecretFlow 1.11 runtime.

Run this exact program in two containers with TOPIC4_SMOKE_PARTY=A and B.
Both drivers build the same SecretFlow graph.  There is deliberately no local
or simulation fallback: HEU, RR22 PSI, and SecureBoost must cross the two
production-mode party endpoints.
"""
from __future__ import annotations

import csv
import hashlib
import json
import math
import os
from pathlib import Path

import numpy as np
import secretflow as sf
import spu
from secretflow.data.vertical import read_csv
from secretflow.device.device.heu import HEUMoveConfig
from secretflow.ml.boost.sgb_v import Sgb


PARTIES = ("A", "B")
WORK = Path("/work")


def _save_json(value, path: str):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, sort_keys=True)
        stream.write("\n")
    return None


def _save_predictions(value, path: str):
    predictions = np.asarray(value, dtype=float).reshape(-1)
    if predictions.size != 12 or not all(math.isfinite(float(item)) for item in predictions):
        raise RuntimeError("SecureBoost prediction shape or values are invalid")
    target = Path(path)
    with target.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["row", "probability"])
        for index, item in enumerate(predictions.tolist()):
            writer.writerow([index, format(float(item), ".17g")])
    return None


def _tree_digest(path: Path) -> str:
    files = sorted(item for item in path.rglob("*") if item.is_file())
    if not files:
        raise RuntimeError("SecureBoost did not persist the local model shard")
    digest = hashlib.sha256()
    for item in files:
        digest.update(item.relative_to(path).as_posix().encode("utf-8"))
        digest.update(b"\0")
        digest.update(item.read_bytes())
        digest.update(b"\n")
    return "sha256:" + digest.hexdigest()


def main():
    party = os.getenv("TOPIC4_SMOKE_PARTY", "")
    if party not in PARTIES:
        raise RuntimeError("TOPIC4_SMOKE_PARTY must be A or B")
    if sf.__version__ != "1.11.0b1":
        raise RuntimeError("SecretFlow image version mismatch")

    cluster_config = {
        "parties": {
            "A": {"address": "smoke-a:61001", "listen_addr": "0.0.0.0:61001"},
            "B": {"address": "smoke-b:61001", "listen_addr": "0.0.0.0:61001"},
        },
        "self_party": party,
    }
    sf.init(
        ray_mode=False,
        cluster_config=cluster_config,
        # Deployment traffic is carried by Kuscia Cluster routes.  Kuscia
        # Envoy owns cross-domain mTLS, so SecretFlow receives no direct key.
        tls_config=None,
        job_name="topic4-secretflow-1-11-production-smoke",
        cross_silo_comm_backend="brpc_link",
        cross_silo_comm_options={
            "timeout_in_ms": 600_000,
            "messages_max_size_in_bytes": 64 * 1024 * 1024,
            "exit_on_sending_failure": True,
        },
        enable_waiting_for_other_parties_ready=True,
        logging_level="warning",
    )
    try:
        a, b = sf.PYU("A"), sf.PYU("B")
        heu = sf.HEU(
            {
                "sk_keeper": {"party": "A"},
                "evaluators": [{"party": "B"}],
                "mode": "PHEU",
                "encoding": {"cleartext_type": "DT_I64", "encoder": "BigintEncoder"},
                "he_parameters": {
                    "schema": "paillier",
                    "key_pair": {"generate": {"bit_size": 2048}},
                },
            },
            spu.spu_pb2.FM128,
        )

        left = a(lambda: np.asarray([10, 20], dtype=np.int64))()
        right = b(lambda: np.asarray([3, 4], dtype=np.int64))()
        encrypted = left.to(heu, config=HEUMoveConfig(heu_dest_party="B"))
        plaintext = right.to(heu, config=HEUMoveConfig(heu_dest_party="B"))
        add = (encrypted + plaintext).to(a)
        multiply = (encrypted * plaintext).to(a)
        dot = (encrypted * plaintext).sum().to(a)
        sf.wait(
            a(_save_json)(
                a(
                    lambda x, y, z: {
                        "add": np.asarray(x).reshape(-1).tolist(),
                        "plaintextMultiply": np.asarray(y).reshape(-1).tolist(),
                        "dotProduct": int(np.asarray(z).reshape(-1)[0]),
                    }
                )(add, multiply, dot),
                "/work/he-result.json",
            )
        )

        psi_spu = sf.SPU(
            {
                "nodes": [
                    {
                        "party": current,
                        "address": "smoke-%s:62001" % current.lower(),
                        "listen_address": "0.0.0.0:62001",
                    }
                    for current in PARTIES
                ],
                "runtime_config": {"protocol": "SEMI2K", "field": "FM128"},
            },
            link_desc={
                "connect_retry_times": 120,
                "connect_retry_interval_ms": 500,
                "recv_timeout_ms": 600_000,
                "http_timeout_ms": 600_000,
            },
            id="topic4-production-smoke-psi",
        )
        input_paths = {a: "/work/vfl.csv", b: "/work/vfl.csv"}
        aligned_paths = {a: "/work/aligned.csv", b: "/work/aligned.csv"}
        reports = psi_spu.psi_csv(
            key="id",
            input_path=input_paths,
            output_path=aligned_paths,
            receiver="A",
            protocol="RR22_FAST_PSI_2PC",
            precheck_input=True,
            sort=True,
            broadcast_result=True,
        )
        if not all(int(report["intersection_count"]) == 12 for report in reports):
            raise RuntimeError("RR22 PSI did not produce the expected intersection")

        vertical = read_csv(
            aligned_paths,
            usecols={a: ["x1", "label"], b: ["x2"]},
        )
        labels = vertical["label"].values
        features = vertical[["x1", "x2"]].values
        model = Sgb(heu).train(
            {
                "tree_growing_method": "level",
                "num_boost_round": 1,
                "max_depth": 2,
                "max_leaf": 4,
                "sketch_eps": 0.25,
                "objective": "logistic",
                "learning_rate": 0.1,
                "reg_lambda": 1.0,
                "gamma": 0.0,
                "rowsample_by_tree": 1.0,
                "colsample_by_tree": 1.0,
                "base_score": 0.5,
                "seed": 20260919,
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
            },
            features,
            labels,
        )
        model.save_model({a: "/work/model-a", b: "/work/model-b"})
        predictions = model.predict(features)
        sf.wait(a(_save_predictions)(predictions, "/work/predictions.csv"))

        expected_he = {"add": [13, 24], "plaintextMultiply": [30, 80], "dotProduct": 110}
        local_model = WORK / ("model-" + party.lower())
        result = {
            "party": party,
            "secretflowVersion": sf.__version__,
            "heuScheme": "Paillier-2048",
            "psiProtocol": "RR22_FAST_PSI_2PC",
            "intersectionCount": 12,
            "modelShardSha256": _tree_digest(local_model),
        }
        if party == "A":
            he_result = json.loads((WORK / "he-result.json").read_text(encoding="utf-8"))
            if he_result != expected_he:
                raise RuntimeError("HEU known-answer test failed")
            if not (WORK / "predictions.csv").is_file():
                raise RuntimeError("SecureBoost predictions were not persisted at A")
            result["heuKnownAnswer"] = he_result
            result["predictionRows"] = 12
        _save_json(result, str(WORK / "smoke-ok.json"))
    finally:
        sf.shutdown(barrier_on_shutdown=False)


if __name__ == "__main__":
    main()
