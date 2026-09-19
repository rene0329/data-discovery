#!/usr/bin/env python3
"""Persistent evidence and operator approval for the real SFL A/B/C smoke.

The runtime writes ``distributed.json`` only after the fixed SFL adapter has
returned a valid result.  Health remains fail-closed until an operator creates
the separate digest file with ``approve_record``.  The approval is therefore
not an environment marker and cannot be produced through the job API.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from pathlib import Path
from typing import Any, Dict, Tuple

REVISION = "c383e40f665063d7f7d87e437a73e015f87c435c"
PARTIES = ["A", "B", "C"]
TEMPLATE_ID = "hfl-fedavg-logreg-3p-v1"
PROTOCOL_VERSION = "sfl-c383e40f/fedavg-logreg"
TRANSPORT = "KUSCIA_CLUSTER_MTLS_ENVOY"
SHA256_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


def canonical(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def smoke_path() -> Path:
    return Path(os.getenv(
        "TOPIC4_SFL_DISTRIBUTED_SMOKE_FILE",
        "/var/lib/topic4-privacy/smoke/distributed.json",
    ))


def approval_path() -> Path:
    return Path(os.getenv(
        "TOPIC4_SFL_DISTRIBUTED_SMOKE_APPROVAL_FILE",
        "/var/lib/topic4-privacy/smoke/distributed.sha256",
    ))


def _prepare_target(path: Path) -> None:
    if not path.is_absolute():
        raise ValueError("SFL smoke paths must be absolute")
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.parent.is_symlink() or not path.parent.is_dir():
        raise ValueError("SFL smoke parent must be a real directory")
    if path.is_symlink():
        raise ValueError("SFL smoke file must not be a symbolic link")


def _atomic_bytes(path: Path, payload: bytes) -> None:
    _prepare_target(path)
    temporary = path.with_name(path.name + ".tmp")
    if temporary.is_symlink():
        temporary.unlink()
    descriptor = os.open(
        str(temporary), os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        try:
            os.close(descriptor)
        except OSError:
            pass
        temporary.unlink(missing_ok=True)
        raise
    os.replace(str(temporary), str(path))


def record_digest(record: Dict[str, Any]) -> str:
    return "sha256:" + hashlib.sha256(canonical(record)).hexdigest()


def write_record(record: Dict[str, Any]) -> str:
    digest = record_digest(record)
    _atomic_bytes(smoke_path(), json.dumps(
        record, sort_keys=True, indent=2).encode("utf-8") + b"\n")
    return digest


def _read_regular(path: Path) -> bytes:
    metadata = path.lstat()
    if stat.S_ISLNK(metadata.st_mode) or not stat.S_ISREG(metadata.st_mode):
        raise ValueError("SFL smoke evidence must be a regular file")
    return path.read_bytes()


def approval_locked() -> bool:
    """Any approval-path filesystem object locks out subsequent bootstrap jobs."""
    return os.path.lexists(str(approval_path()))


def load_and_validate_record(context: Dict[str, Any]) -> Tuple[Dict[str, Any], str]:
    raw = _read_regular(smoke_path())
    record = json.loads(raw.decode("utf-8"))
    if not isinstance(record, dict):
        raise ValueError("distributed smoke record must be an object")
    required = {
        "contractVersion": "topic4.privacy.sfl-smoke/v1",
        "status": "SUCCEEDED",
        "templateId": TEMPLATE_ID,
        "protocolVersion": PROTOCOL_VERSION,
        "parties": PARTIES,
        "partyId": context.get("partyId"),
        "sourceRevision": REVISION,
        "transport": TRANSPORT,
        "servingId": context.get("servingId"),
        "contextDigest": context.get("contextDigest"),
    }
    if any(record.get(key) != value for key, value in required.items()):
        raise ValueError("distributed smoke record does not match this SFL party context")
    if not SHA256_RE.fullmatch(str(record.get("resultDigest", ""))):
        raise ValueError("distributed smoke result digest is invalid")
    if not re.fullmatch(r"[0-9a-f]{32}", str(record.get("externalJobId", ""))):
        raise ValueError("distributed smoke external job identifier is invalid")
    return record, record_digest(record)


def require_approved_record(context: Dict[str, Any]) -> Tuple[Dict[str, Any], str]:
    record, digest = load_and_validate_record(context)
    raw_approval = _read_regular(approval_path()).decode("ascii")
    # One optional POSIX newline is accepted. Whitespace, comments and multiple
    # lines are rejected so the approval names exactly one canonical record.
    if raw_approval not in (digest, digest + "\n"):
        raise ValueError("distributed smoke approval digest is absent or mismatched")
    return record, digest


def approve_record(context: Dict[str, Any]) -> str:
    _, digest = load_and_validate_record(context)
    target = approval_path()
    if os.path.lexists(str(target)):
        raw = _read_regular(target).decode("ascii")
        if raw not in (digest, digest + "\n"):
            raise ValueError("existing SFL smoke approval differs from the current record")
        return digest
    _atomic_bytes(target, (digest + "\n").encode("ascii"))
    return digest
