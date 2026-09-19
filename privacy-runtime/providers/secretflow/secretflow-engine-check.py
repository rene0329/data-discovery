#!/usr/bin/env python3
"""Fail-closed runtime check for the pinned SecretFlow provider image."""
from __future__ import annotations

import importlib.util
import inspect
import json
import socket
import subprocess
from pathlib import Path
from urllib.parse import urlparse

import numpy as np


EXPECTED_VERSION = "1.11.0b1"
EXPECTED_REVISION = "b9d6fd5ca9dbdfc95cfda5c282e93022e1caebc4"
EXPECTED_PSI_REVISION = "72f3312fd9142ea567f3a170d0808a2118b75af8"


def load_adapter():
    path = Path("/opt/topic4/bin/secretflow-run-engine.py")
    if not path.is_file():
        path = Path(__file__).with_name("run-engine.py")
    spec = importlib.util.spec_from_file_location("topic4_secretflow_adapter", path)
    if spec is None or spec.loader is None:
        raise RuntimeError("SecretFlow adapter cannot be loaded")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def validate_dns(config):
    resolved = {}
    for party in config["fed"]:
        hosts = []
        for endpoint in (config["fed"][party], config["spu"][party], config["psi"][party]):
            if endpoint.startswith("0.0.0.0:"):
                continue
            if endpoint.startswith("http://"):
                parsed = urlparse(endpoint)
                host, port = parsed.hostname, parsed.port
            else:
                host, raw_port = endpoint.rsplit(":", 1)
                port = int(raw_port)
            socket.getaddrinfo(host, int(port), type=socket.SOCK_STREAM)
            hosts.append(host)
        resolved[party] = hosts
    return resolved


def validate_heu_crypto():
    from heu import numpy as hnp
    from heu import phe

    schema = phe.parse_schema_type("paillier")
    encoder = phe.BigintEncoder(schema)
    kit = hnp.setup("paillier", 2048)
    left = kit.array(np.asarray([2, 4], dtype=np.int64), encoder)
    right = kit.array(np.asarray([3, 5], dtype=np.int64), encoder)
    encrypted = kit.encryptor().encrypt(left)
    added = kit.evaluator().add(encrypted, right)
    multiplied = kit.evaluator().mul(encrypted, right)
    decoded_add = kit.decryptor().decrypt(added).to_numpy(encoder).reshape(-1).tolist()
    decoded_mul = kit.decryptor().decrypt(multiplied).to_numpy(encoder).reshape(-1).tolist()
    if decoded_add != [5, 9] or decoded_mul != [6, 20]:
        raise RuntimeError("HEU Paillier known-answer check failed")


def validate_psi():
    executable = Path("/opt/psi/main")
    revision_file = Path("/opt/psi/SOURCE_REVISION")
    if not executable.is_file() or not revision_file.is_file():
        raise RuntimeError("pinned SecretFlow PSI executable is missing")
    if revision_file.read_text(encoding="utf-8").strip() != EXPECTED_PSI_REVISION:
        raise RuntimeError("SecretFlow PSI source revision mismatch")
    completed = subprocess.run(
        [str(executable), "--version"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=15,
        check=False,
        text=True,
    )
    if completed.returncode != 0:
        raise RuntimeError("SecretFlow PSI self-check failed")


def main():
    import secretflow as sf
    import secretflow.version as sf_version
    from spu import psi
    from secretflow.device import HEU, PYU, SPU
    from secretflow.ml.boost.sgb_v import Sgb, SgbModel

    if sf.__version__ != EXPECTED_VERSION:
        raise SystemExit("SecretFlow version mismatch")
    revision = getattr(sf_version, "__commit_id__", "")
    if revision and "$$" not in revision and revision != EXPECTED_REVISION:
        raise SystemExit("SecretFlow source revision mismatch")
    if not all(inspect.isclass(value) for value in (HEU, PYU, SPU, Sgb, SgbModel)):
        raise SystemExit("SecretFlow HEU/SecureBoost APIs are unavailable")
    for owner, method in ((Sgb, "train"), (SgbModel, "predict"), (SgbModel, "save_model")):
        if not callable(getattr(owner, method, None)):
            raise SystemExit("SecretFlow SecureBoost API mismatch")
    try:
        psi.PsiType.Value("RR22_FAST_PSI_2PC")
    except ValueError as exc:
        raise SystemExit("SecretFlow SPU RR22 alignment API mismatch") from exc

    adapter = load_adapter()
    context_path = Path(
        adapter.os.getenv(
            "TOPIC4_KUSCIA_CONTEXT_FILE", "/var/run/topic4-kuscia/context.json"
        )
    )
    config = adapter.load_kuscia_context(context_path)
    resolved = validate_dns(config)
    validate_heu_crypto()
    validate_psi()
    print(
        json.dumps(
            {
                "status": "ok",
                "secretflowVersion": sf.__version__,
                "sourceRevision": EXPECTED_REVISION,
                "heu": "paillier-2048-known-answer-ok",
                "psiRevision": EXPECTED_PSI_REVISION,
                "secureBoostApis": ["train", "predict", "save_model"],
                "vflAlignment": "RR22_FAST_PSI_2PC",
                "configuredParties": sorted(resolved),
                "kusciaContextDigest": config["contextDigest"],
                "kusciaDeploymentId": config["deploymentId"],
                "transport": config["transport"],
                "plaintextFallback": False,
            },
            sort_keys=True,
        )
    )


if __name__ == "__main__":
    main()
