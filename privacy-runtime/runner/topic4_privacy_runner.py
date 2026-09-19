#!/usr/bin/env python3
"""Topic4 privacy engine HTTP adapter.

This process does not implement cryptography. It validates a narrow job contract and
launches one fixed, image-owned adapter for a pinned third-party engine. Missing
engines are reported as UNAVAILABLE; there is deliberately no plaintext fallback.
"""
from __future__ import annotations

import hashlib
import hmac
import importlib.util
import json
import os
import queue
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import traceback
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlparse
from urllib.request import Request, urlopen

ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
SHA256_RE = re.compile(r"^(?:sha256:)?[0-9a-f]{64}$")
FORBIDDEN_POLICY_KEYS = {
    "args", "command", "cmd", "entrypoint", "env", "executable", "image",
    "path", "script", "shell", "workingDirectory",
}
TERMINAL = {"SUCCEEDED", "FAILED", "ABORTED", "CANCELLED"}
PRIVATE_JOB_ID_RE = re.compile(r"^[0-9a-f]{32}$")


def now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def stable_submission_digest(request: Dict[str, Any]) -> str:
    """Hash every frozen request field while ignoring scoped-token renewal data."""
    stable = dict(request)
    stable_stage = dict(request["stagedInput"])
    stable_stage.pop("token", None)
    stable_stage.pop("expiresAt", None)
    stable["stagedInput"] = stable_stage
    return "sha256:" + hashlib.sha256(canonical_bytes(stable)).hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return "sha256:" + h.hexdigest()


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(str(temporary), str(path))


def nested_forbidden_keys(value: Any) -> List[str]:
    found: List[str] = []
    if isinstance(value, dict):
        for key, item in value.items():
            if key in FORBIDDEN_POLICY_KEYS:
                found.append(key)
            found.extend(nested_forbidden_keys(item))
    elif isinstance(value, list):
        for item in value:
            found.extend(nested_forbidden_keys(item))
    return found


def runtime_auth_token() -> str:
    environment_token = os.getenv("TOPIC4_AUTH_TOKEN", "").strip()
    if environment_token:
        return environment_token
    token_file = Path(os.getenv(
        "TOPIC4_AUTH_TOKEN_FILE", "/run/secrets/topic4-runner/bearer-token"))
    return token_file.read_text(encoding="utf-8").strip()


class ContractError(ValueError):
    pass


class StageError(RuntimeError):
    pass


class ProviderConfig:
    def __init__(self, path: Path):
        self.path = path
        self.data = json.loads(path.read_text(encoding="utf-8"))
        self.provider_id = self.data["providerId"]
        self.engine = self.data["engine"]
        self.engine_version = self.data["engineVersion"]
        self.source_revision = self.data["sourceRevision"]
        self.adapter = Path(self.data["adapterExecutable"])
        self.templates = {item["templateId"]: item for item in self.data["templates"]}
        self._health_lock = threading.Lock()
        # Full health includes the SFL smoke-approval gate; bootstrap health
        # deliberately does not. Their outcomes must never share a cache entry.
        self._health_cache: Dict[str, Tuple[float, bool, Tuple[str, ...]]] = {}

    @staticmethod
    def _health_cache_seconds(success: bool) -> float:
        name = ("TOPIC4_HEALTH_CACHE_SECONDS" if success
                else "TOPIC4_HEALTH_FAILURE_CACHE_SECONDS")
        default = 30.0 if success else 2.0
        try:
            value = float(os.getenv(name, str(default)))
        except ValueError:
            value = default
        return min(max(value, 0.0), 300.0)

    def dependency_status(self, include_health_command: bool = True) -> Tuple[bool, List[str]]:
        reasons: List[str] = []
        forced = os.getenv("TOPIC4_FORCE_UNAVAILABLE", "").strip()
        if forced:
            reasons.append(forced)
        if not self.adapter.is_file() or not os.access(str(self.adapter), os.X_OK):
            reasons.append("fixed engine adapter is not executable: %s" % self.adapter)
        for name in self.data.get("requiredExecutables", []):
            path = Path(name)
            if not path.is_file() or not os.access(str(path), os.X_OK):
                reasons.append("required executable is missing: %s" % path)
        for name in self.data.get("requiredFiles", []):
            if not Path(name).is_file():
                reasons.append("required file is missing: %s" % name)
        for module in self.data.get("requiredPythonImports", []):
            if importlib.util.find_spec(module) is None:
                reasons.append("required Python module is unavailable: %s" % module)
        for name, expected in self.data.get("requiredEnvironment", {}).items():
            if os.getenv(name) != expected:
                reasons.append("required deployment setting is absent: %s" % name)
        # SFL's bootstrap command verifies the exact packages and Kuscia
        # topology while deliberately omitting only the record-approval gate.
        # Other providers have no bootstrapHealthCommand and keep the existing
        # static dependency behavior when a caller explicitly skips health.
        command = self.data.get(
            "healthCommand" if include_health_command else "bootstrapHealthCommand")
        if command and not reasons:
            # Kubelet, the gateway and the public capabilities endpoint can ask
            # concurrently. Run one bounded known-answer check and briefly cache
            # its outcome instead of repeatedly generating Paillier keys. A
            # failed SFL pre-approval check has a short cache so operator
            # approval is reflected promptly.
            with self._health_lock:
                cache_key = "full" if include_health_command else "bootstrap"
                cached = self._health_cache.get(cache_key)
                if cached is not None and time.monotonic() < cached[0]:
                    reasons.extend(cached[2])
                else:
                    health_reasons: List[str] = []
                    try:
                        completed = subprocess.run(
                            command,
                            stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT,
                            timeout=15,
                            check=False,
                            text=True,
                        )
                        if completed.returncode != 0:
                            health_reasons.append(
                                "engine self-check exited %d" % completed.returncode)
                    except (OSError, subprocess.TimeoutExpired) as exc:
                        health_reasons.append(
                            "engine self-check failed: %s" % type(exc).__name__)
                    success = not health_reasons
                    self._health_cache[cache_key] = (
                        time.monotonic() + self._health_cache_seconds(success),
                        success, tuple(health_reasons))
                    reasons.extend(health_reasons)
        return not reasons, reasons

    def public_capabilities(self) -> Dict[str, Any]:
        available, reasons = self.dependency_status()
        templates = []
        for item in self.data["templates"]:
            public = {key: value for key, value in item.items() if key != "internal"}
            public["availability"] = "AVAILABLE" if available else "UNAVAILABLE"
            if reasons:
                public["unavailableReasons"] = reasons
            templates.append(public)
        return {
            "contractVersion": "topic4.privacy.runner/v1",
            "providerId": self.provider_id,
            "engine": self.engine,
            "engineVersion": self.engine_version,
            "sourceRevision": self.source_revision,
            "availability": "AVAILABLE" if available else "UNAVAILABLE",
            "unavailableReasons": reasons,
            "templates": templates,
            "operations": {
                item["templateId"]: (
                    item["operations"] if isinstance(item.get("operations"), list)
                    else ([item["operation"]] if item.get("operation")
                          else ([item["protocol"]] if item.get("protocol") else []))
                )
                for item in self.data["templates"]
            },
            "controlPlaneOperations": sorted({
                item["operation"] for item in self.data["templates"]
                if isinstance(item.get("operation"), str) and item["operation"]
            }),
            "securityProfiles": sorted({
                item["securityProfile"] for item in self.data["templates"]
            }),
            "plaintextFallback": False,
        }


class JobStore:
    def __init__(self, root: Path):
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.jobs: Dict[str, Dict[str, Any]] = {}
        self.processes: Dict[str, subprocess.Popen] = {}
        # The scoped Agent token is intentionally kept only in this in-memory
        # queue.  POST /jobs persists a redacted descriptor and returns before
        # a potentially large CSV is downloaded.
        self.work: "queue.Queue[Tuple[str, Dict[str, Any], bool]]" = queue.Queue()
        self._load()

    def _load(self) -> None:
        for status_file in self.root.glob("*/status.json"):
            try:
                status = json.loads(status_file.read_text(encoding="utf-8"))
                external_id = status["externalJobId"]
                if not isinstance(external_id, str) or not PRIVATE_JOB_ID_RE.fullmatch(external_id):
                    raise ValueError("invalid persisted externalJobId")
                if status.get("status") not in TERMINAL:
                    status.update({
                        "status": "FAILED",
                        "failureCode": "RUNTIME_RESTARTED",
                        "failureMessage": "runner restarted before the engine process completed",
                        "updatedAt": now(),
                    })
                    atomic_json(status_file, status)
                    evidence_file = status_file.with_name("evidence.json")
                    if not evidence_file.exists():
                        atomic_json(evidence_file, {
                            "contractVersion": "topic4.privacy.evidence/v1",
                            "externalJobId": external_id,
                            "providerId": status.get("providerId"),
                            "templateId": status.get("templateId"),
                            "startedAt": status.get("createdAt"),
                            "finishedAt": status["updatedAt"],
                            "failureCode": "RUNTIME_RESTARTED",
                            "plaintextFallback": False,
                        })
                self.jobs[external_id] = status
            except Exception:
                continue

    def directory(self, external_id: str) -> Path:
        return self.root / external_id

    def save(self, status: Dict[str, Any]) -> None:
        with self.lock:
            self.jobs[status["externalJobId"]] = dict(status)
            atomic_json(self.directory(status["externalJobId"]) / "status.json", status)

    def get(self, external_id: str) -> Optional[Dict[str, Any]]:
        with self.lock:
            item = self.jobs.get(external_id)
            return dict(item) if item else None


class Runtime:
    def __init__(self, config: ProviderConfig, state_dir: Path):
        self.config = config
        self.store = JobStore(state_dir)
        self.input_root = self._private_root("TOPIC4_INPUT_DIR", "/var/run/topic4-inputs")
        self.work_root = self._private_root("TOPIC4_WORK_DIR", "/var/run/topic4-work")
        self.model_roots = self._model_roots()
        self._validate_root_boundaries()
        self._purge_recovered_private_data()
        self.submit_lock = threading.Lock()
        self.worker = threading.Thread(target=self._worker, name="privacy-engine-worker", daemon=True)
        self.worker.start()

    @staticmethod
    def _private_root(environment_name: str, default: str) -> Path:
        root = Path(os.getenv(environment_name, default))
        if not root.is_absolute():
            raise RuntimeError("%s must be an absolute directory" % environment_name)
        if root.is_symlink():
            raise RuntimeError("%s must not be a symbolic link" % environment_name)
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        if not root.is_dir():
            raise RuntimeError("%s must be a real directory" % environment_name)
        os.chmod(root, 0o700)
        return root.resolve()

    @staticmethod
    def _model_roots() -> List[Path]:
        roots: List[Path] = []
        for name in ("TOPIC4_MODEL_DIR", "TOPIC4_SECRETFLOW_MODEL_ROOT"):
            value = os.getenv(name, "").strip()
            if not value:
                continue
            root = Path(value)
            if not root.is_absolute() or root.is_symlink():
                raise RuntimeError("%s must be an absolute, real directory" % name)
            root.mkdir(mode=0o700, parents=True, exist_ok=True)
            resolved = root.resolve()
            if resolved not in roots:
                roots.append(resolved)
        return roots

    def _validate_root_boundaries(self) -> None:
        state = self.store.root.resolve()
        roots = [("TOPIC4_STATE_DIR", state), ("TOPIC4_INPUT_DIR", self.input_root),
                 ("TOPIC4_WORK_DIR", self.work_root)]
        roots.extend(("MODEL_OUTPUT_ROOT", root) for root in self.model_roots)
        for index, (left_name, left) in enumerate(roots):
            for right_name, right in roots[index + 1:]:
                common = Path(os.path.commonpath((str(left), str(right))))
                if common == left or common == right:
                    raise RuntimeError("%s and %s must be non-overlapping directories" % (
                        left_name, right_name))

    @staticmethod
    def _remove_private_path(path: Path) -> None:
        """Delete one runner-owned path without ever traversing a replacement symlink."""
        if path.is_symlink() or path.is_file():
            path.unlink(missing_ok=True)
            return
        if not path.exists():
            return
        if not path.is_dir():
            raise RuntimeError("runner private path has an unsupported file type")

        def make_writable(function, target, _error):
            os.chmod(target, 0o700)
            function(target)

        shutil.rmtree(path, onerror=make_writable)

    @staticmethod
    def _job_private_path(root: Path, external_id: str) -> Path:
        if not PRIVATE_JOB_ID_RE.fullmatch(external_id):
            raise RuntimeError("invalid runner private directory identifier")
        path = root / external_id
        if path.parent.resolve() != root:
            raise RuntimeError("runner private directory escaped its configured root")
        return path

    def _purge_private_root(self, root: Path) -> None:
        for child in root.iterdir():
            self._remove_private_path(child)

    def _purge_recovered_private_data(self) -> None:
        """No child survives a runner restart, so no staged or derived byte may survive it."""
        self._purge_private_root(self.input_root)
        self._purge_private_root(self.work_root)
        for external_id, status in list(self.store.jobs.items()):
            if status.get("status") != "SUCCEEDED":
                self._cleanup_failed_outputs(external_id)

    def _prepare_work_directory(self, external_id: str) -> Path:
        work = self._job_private_path(self.work_root, external_id)
        self._remove_private_path(work)
        work.mkdir(mode=0o700)
        return work

    def validate(self, request: Any) -> Dict[str, Any]:
        if not isinstance(request, dict):
            raise ContractError("request body must be a JSON object")
        if any(isinstance(key, str) and key.startswith("_topic4") for key in request):
            raise ContractError("internal Topic4 fields are deployment-owned")
        required = [
            "jobId", "attemptId", "templateId", "protocolVersion", "imageDigest",
            "specDigest", "securityProfile", "participants", "resultRecipients",
            "timeoutSeconds", "enginePolicy",
        ]
        missing = [name for name in required if name not in request]
        if missing:
            raise ContractError("missing required fields: " + ", ".join(missing))
        allowed_request = set(required) | {"stagedInput"}
        extra = sorted(set(request) - allowed_request)
        if extra:
            raise ContractError("request contains fields outside the fixed contract: "
                                + ", ".join(extra))
        for field in ("jobId", "attemptId"):
            if not isinstance(request[field], str) or not ID_RE.fullmatch(request[field]):
                raise ContractError("%s is not a valid opaque identifier" % field)
        template_id = request["templateId"]
        if template_id not in self.config.templates:
            raise ContractError("templateId is not registered by this provider")
        if not isinstance(request["specDigest"], str) or not SHA256_RE.fullmatch(request["specDigest"]):
            raise ContractError("specDigest must be a SHA-256 digest")
        participants = request["participants"]
        if not isinstance(participants, list):
            raise ContractError("participants must be an array")
        template = self.config.templates[template_id]
        if request["securityProfile"] != template["securityProfile"]:
            raise ContractError("securityProfile differs from the registered template")
        if request["protocolVersion"] != template["protocolVersion"]:
            raise ContractError("protocolVersion differs from the registered template")
        running_digest = os.getenv("TOPIC4_IMAGE_DIGEST", "")
        if (not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", request["imageDigest"])
                or not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", running_digest)
                or not hmac.compare_digest(request["imageDigest"].lower(), running_digest.lower())):
            raise ContractError("imageDigest differs from the immutable runtime image")
        allowed_counts = template["participantCounts"]
        if len(participants) not in allowed_counts:
            raise ContractError("template requires participant count in %s" % allowed_counts)
        party_ids = []
        participant_fields = {
            "partyId", "role", "datasetId", "datasetVersion", "datasetSha256",
            "authoritativeSizeBytes", "schemaDigest", "frozenSchema", "fields",
        }
        for participant in participants:
            if not isinstance(participant, dict) or set(participant) - participant_fields:
                raise ContractError("participant contains fields outside the fixed contract")
            party_id = participant.get("partyId") or participant.get("domainId")
            if not isinstance(party_id, str) or not ID_RE.fullmatch(party_id):
                raise ContractError("each participant requires a valid partyId or domainId")
            party_ids.append(party_id)
        if len(set(party_ids)) != len(party_ids):
            raise ContractError("participant identifiers must be unique")
        required_roles = template.get("requiredRoles")
        if not isinstance(required_roles, dict) or party_ids != list(required_roles):
            raise ContractError("participant party order differs from the registered template")
        for participant, party_id in zip(participants, party_ids):
            if participant.get("role") != required_roles[party_id]:
                raise ContractError("participant role differs from the registered template")
        recipients = request["resultRecipients"]
        if not isinstance(recipients, list) or not recipients or not all(item in party_ids for item in recipients):
            raise ContractError("resultRecipients must be a non-empty subset of participants")
        timeout = request["timeoutSeconds"]
        if not isinstance(timeout, int) or isinstance(timeout, bool) or timeout < 1 or timeout > template["maxTimeoutSeconds"]:
            raise ContractError("timeoutSeconds is outside the registered template limit")
        if not isinstance(request["enginePolicy"], dict):
            raise ContractError("enginePolicy must be an object")
        forbidden = sorted(set(nested_forbidden_keys(request["enginePolicy"])))
        if forbidden:
            raise ContractError("enginePolicy contains forbidden execution fields: " + ", ".join(forbidden))
        staged = request.get("stagedInput")
        if not isinstance(staged, dict):
            raise ContractError("stagedInput is required for the local party")
        stage_fields = {
            "token", "datasetId", "datasetVersion", "sourcePath", "expectedSize",
            "expectedSha256", "expectedSchema", "expectedSchemaDigest", "partyId",
            "nodeId", "nodeName", "agentBaseUrl", "tokenType", "expiresAt",
        }
        if set(staged) - stage_fields:
            raise ContractError("stagedInput contains fields outside the fixed contract")
        required_stage = [
            "token", "datasetId", "datasetVersion", "sourcePath", "expectedSize",
            "expectedSha256", "expectedSchema", "expectedSchemaDigest",
        ]
        stage_missing = [name for name in required_stage if name not in staged]
        if stage_missing:
            raise ContractError("stagedInput is missing: " + ", ".join(stage_missing))
        if not isinstance(staged["token"], str) or not staged["token"].strip():
            raise ContractError("stagedInput.token is required")
        allowed_prefixes = [
            item.rstrip("/") + "/"
            for item in os.getenv("TOPIC4_AGENT_ALLOWED_PATH_PREFIXES", "/dataset/").split(",")
            if item.strip() and item.strip().startswith("/")
        ]
        if (not isinstance(staged["sourcePath"], str)
                or not any(staged["sourcePath"].startswith(prefix) for prefix in allowed_prefixes)):
            raise ContractError("stagedInput.sourcePath is outside the configured data roots")
        if ".." in staged["sourcePath"].split("/"):
            raise ContractError("stagedInput.sourcePath contains path traversal")
        if not isinstance(staged["expectedSize"], int) or isinstance(staged["expectedSize"], bool) or staged["expectedSize"] < 0:
            raise ContractError("stagedInput.expectedSize must be a non-negative integer")
        maximum = int(os.getenv("TOPIC4_MAX_INPUT_BYTES", str(512 * 1024 * 1024)))
        if staged["expectedSize"] > maximum:
            raise ContractError("staged input exceeds TOPIC4_MAX_INPUT_BYTES")
        for field in ("expectedSha256", "expectedSchemaDigest"):
            if not isinstance(staged[field], str) or not SHA256_RE.fullmatch(staged[field]):
                raise ContractError("stagedInput.%s must be a SHA-256 digest" % field)
        schema = staged["expectedSchema"]
        if not isinstance(schema, list) or not schema:
            raise ContractError("stagedInput.expectedSchema must be a non-empty array")
        names = []
        for column in schema:
            if not isinstance(column, dict) or not isinstance(column.get("name"), str):
                raise ContractError("each expectedSchema column requires a name")
            names.append(column["name"])
        if len(set(names)) != len(names):
            raise ContractError("expectedSchema column names must be unique")
        actual_schema_digest = "sha256:" + hashlib.sha256(canonical_bytes(schema)).hexdigest()
        if actual_schema_digest.removeprefix("sha256:") != staged["expectedSchemaDigest"].removeprefix("sha256:"):
            raise ContractError("expectedSchemaDigest does not match expectedSchema")
        return request

    def _stage_input(self, external_id: str, request: Dict[str, Any]) -> Dict[str, Any]:
        staged = request["stagedInput"]
        token = staged["token"].strip()
        token_digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
        consumed_dir = self.store.root / ".consumed-stage-tokens"
        consumed_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        consumed = consumed_dir / token_digest
        try:
            descriptor = os.open(str(consumed), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            os.close(descriptor)
        except FileExistsError as exc:
            raise StageError("staged input token was already consumed") from exc
        agent_base = os.getenv("TOPIC4_AGENT_BASE_URL", "").rstrip("/")
        parsed = urlparse(agent_base)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise StageError("TOPIC4_AGENT_BASE_URL is not configured")
        allowed_hosts = [item.strip() for item in os.getenv("TOPIC4_AGENT_ALLOWED_HOSTS", "").split(",") if item.strip()]
        if allowed_hosts and parsed.hostname not in allowed_hosts:
            raise StageError("configured agent host is outside TOPIC4_AGENT_ALLOWED_HOSTS")
        encoded_path = quote(staged["sourcePath"].lstrip("/"), safe="/")
        url = agent_base + "/data-discovery/download/" + encoded_path
        directory = self._job_private_path(self.input_root, external_id)
        if directory.is_symlink():
            raise StageError("runner input directory was replaced by a symbolic link")
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        destination = directory / "input.csv"
        temporary = directory / "input.csv.part"
        digest = hashlib.sha256()
        size = 0
        try:
            download = Request(url, method="GET", headers={"Authorization": "Bearer " + token})
            with urlopen(download, timeout=int(os.getenv("TOPIC4_AGENT_TIMEOUT_SECONDS", "60"))) as response, temporary.open("wb") as output:
                os.chmod(str(temporary), 0o600)
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > staged["expectedSize"]:
                        raise StageError("agent returned more bytes than the frozen snapshot")
                    digest.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
        except (HTTPError, URLError, TimeoutError, OSError) as exc:
            temporary.unlink(missing_ok=True)
            raise StageError("scoped agent download failed: %s" % type(exc).__name__) from exc
        actual = digest.hexdigest()
        expected = staged["expectedSha256"].removeprefix("sha256:")
        if size != staged["expectedSize"] or not hmac.compare_digest(actual.lower(), expected.lower()):
            temporary.unlink(missing_ok=True)
            raise StageError("agent payload does not match frozen size/SHA-256")
        try:
            import csv
            with temporary.open(newline="", encoding="utf-8-sig") as stream:
                header = next(csv.reader(stream), None)
        except (OSError, UnicodeError, csv.Error) as exc:
            temporary.unlink(missing_ok=True)
            raise StageError("staged payload is not a readable CSV") from exc
        expected_names = [item["name"] for item in staged["expectedSchema"]]
        if header != expected_names:
            temporary.unlink(missing_ok=True)
            raise StageError("CSV header does not match the frozen schema")
        os.replace(str(temporary), str(destination))
        sanitized = dict(request)
        sanitized_stage = dict(staged)
        sanitized_stage.pop("token", None)
        sanitized_stage["tokenDigest"] = "sha256:" + token_digest
        sanitized_stage["stagedDigest"] = "sha256:" + actual
        sanitized["stagedInput"] = sanitized_stage
        return sanitized

    def _cleanup_input(self, external_id: str) -> None:
        self._remove_private_path(self._job_private_path(self.input_root, external_id))

    def _cleanup_private_data(self, external_id: str) -> None:
        self._cleanup_input(external_id)
        self._remove_private_path(self._job_private_path(self.work_root, external_id))

    def _cleanup_failed_outputs(self, external_id: str) -> None:
        directory = self.store.directory(external_id)
        # An adapter can finish writing a recipient-visible result immediately
        # before a concurrent cancellation wins the terminal-state race.  The
        # job is not successful in that case, so retaining the result on the
        # persistent state volume would publish a partial/cancelled output.
        result_path = directory / "engine-result.json"
        if result_path.is_symlink():
            result_path.unlink(missing_ok=True)
        elif result_path.is_file():
            result_path.unlink()
        self._remove_private_path(directory / "artifacts")
        request_path = directory / "request.json"
        if not request_path.is_file():
            return
        try:
            request = json.loads(request_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return
        job_id = request.get("jobId")
        attempt_id = request.get("attemptId")
        if (not isinstance(job_id, str) or not ID_RE.fullmatch(job_id)
                or not isinstance(attempt_id, str) or not ID_RE.fullmatch(attempt_id)):
            raise RuntimeError("stored job identifiers cannot address model output cleanup")
        for root in self.model_roots:
            job_root = root / job_id
            if job_root.is_symlink():
                job_root.unlink(missing_ok=True)
                continue
            attempt_root = job_root / attempt_id
            if attempt_root.parent.resolve() != job_root.resolve():
                raise RuntimeError("model output path escaped its configured root")
            self._remove_private_path(attempt_root)
            try:
                job_root.rmdir()
            except OSError:
                pass

    def _finalize_private_cleanup(self, external_id: str) -> None:
        failure = None
        try:
            self._cleanup_private_data(external_id)
        except (OSError, RuntimeError) as exc:
            failure = exc
        status = self.store.get(external_id)
        if not status or status.get("status") != "SUCCEEDED":
            try:
                self._cleanup_failed_outputs(external_id)
            except (OSError, RuntimeError) as exc:
                failure = failure or exc
        if failure is not None:
            self._record_cleanup_failure(external_id)
            try:
                self._cleanup_failed_outputs(external_id)
            except (OSError, RuntimeError):
                pass

    def _record_cleanup_failure(self, external_id: str) -> None:
        status = self.store.get(external_id)
        if not status:
            return
        status.update({
            "status": "FAILED",
            "updatedAt": now(),
            "failureCode": "PRIVATE_DATA_CLEANUP_FAILED",
            "failureMessage": "runner could not remove all staged or derived private data",
        })
        self.store.save(status)

    @staticmethod
    def _terminate_process_group(
        process: subprocess.Popen, grace_seconds: Optional[float] = None
    ) -> int:
        if grace_seconds is None:
            try:
                grace_seconds = float(os.getenv("TOPIC4_TERMINATION_GRACE_SECONDS", "10"))
            except ValueError:
                grace_seconds = 10.0
            grace_seconds = min(max(grace_seconds, 0.1), 30.0)
        if process.poll() is not None:
            return int(process.returncode)
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            return int(process.wait(timeout=grace_seconds))
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            return int(process.wait())

    def _sfl_bootstrap_enabled(self) -> bool:
        return (self.config.provider_id == "KUSCIA_SFL"
                and os.getenv("TOPIC4_SFL_BOOTSTRAP_MODE", "") == "1")

    @staticmethod
    def _sfl_smoke_approval_locked() -> bool:
        path = Path(os.getenv(
            "TOPIC4_SFL_DISTRIBUTED_SMOKE_APPROVAL_FILE",
            "/var/lib/topic4-privacy/smoke/distributed.sha256"))
        return os.path.lexists(str(path))

    def bootstrap_status(self) -> Dict[str, Any]:
        enabled = self._sfl_bootstrap_enabled()
        ready, reasons = self.config.dependency_status(include_health_command=False)
        return {
            "providerId": self.config.provider_id,
            "bootstrapEnabled": enabled,
            "approved": self._sfl_smoke_approval_locked() if enabled else False,
            "recordPresent": (Path(os.getenv(
                "TOPIC4_SFL_DISTRIBUTED_SMOKE_FILE",
                "/var/lib/topic4-privacy/smoke/distributed.json")).is_file()
                              if enabled else False),
            "baseAvailability": "AVAILABLE" if ready else "UNAVAILABLE",
            "unavailableReasons": reasons,
        }

    def submit(self, request: Any, bootstrap: bool = False) -> Tuple[Dict[str, Any], bool]:
        with self.submit_lock:
            return self._submit(request, bootstrap=bootstrap)

    def _submit(self, request: Any, bootstrap: bool) -> Tuple[Dict[str, Any], bool]:
        validated = self.validate(request)
        submission_digest = stable_submission_digest(validated)
        identity = "%s\0%s\0%s" % (validated["jobId"], validated["attemptId"], self.config.provider_id)
        external_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]
        existing = self.store.get(external_id)
        if existing:
            if not hmac.compare_digest(
                    str(existing.get("submissionDigest", "")), submission_digest):
                raise ContractError(
                    "jobId/attemptId already identifies a different immutable request")
            return existing, True
        if bootstrap:
            if not self._sfl_bootstrap_enabled():
                raise ContractError("SFL bootstrap endpoint is disabled for this provider")
            if self._sfl_smoke_approval_locked():
                raise ContractError("SFL distributed smoke is already operator-approved")
            ready, reasons = self.config.dependency_status(include_health_command=False)
        else:
            ready, reasons = self.config.dependency_status()
        if not ready:
            return {
                "providerId": self.config.provider_id,
                "status": "UNAVAILABLE",
                "unavailableReasons": reasons,
                "plaintextFallback": False,
            }, False
        if bootstrap:
            if Path(os.getenv(
                    "TOPIC4_SFL_DISTRIBUTED_SMOKE_FILE",
                    "/var/lib/topic4-privacy/smoke/distributed.json")).is_file():
                raise ContractError(
                    "an SFL smoke record already awaits operator approval")
            with self.store.lock:
                prior = [item for item in self.store.jobs.values()
                         if item.get("bootstrapSmoke") is True
                         and item.get("status") not in {"FAILED", "ABORTED", "CANCELLED"}]
            if prior:
                raise ContractError("an SFL bootstrap job already exists")
        directory = self.store.directory(external_id)
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        sanitized = dict(validated)
        sanitized_stage = dict(validated["stagedInput"])
        token = sanitized_stage.pop("token")
        sanitized_stage["tokenDigest"] = "sha256:" + hashlib.sha256(
            token.strip().encode("utf-8")).hexdigest()
        sanitized["stagedInput"] = sanitized_stage
        if bootstrap:
            # This marker is added only after the public request has passed the
            # exact same template, image, policy and staging validation as a
            # normal job. Both HTTP entry points reject caller-supplied markers.
            sanitized["_topic4BootstrapSmoke"] = True
        atomic_json(directory / "request.json", sanitized)
        created = now()
        status = {
            "contractVersion": "topic4.privacy.runner/v1",
            "providerId": self.config.provider_id,
            "externalJobId": external_id,
            "jobId": validated["jobId"],
            "attemptId": validated["attemptId"],
            "templateId": validated["templateId"],
            "submissionDigest": submission_digest,
            "status": "QUEUED",
            "createdAt": created,
            "updatedAt": created,
            "resultReference": "/jobs/%s/result" % external_id,
            "evidenceReference": "/jobs/%s/evidence" % external_id,
        }
        if bootstrap:
            status["bootstrapSmoke"] = True
        self.store.save(status)
        self.store.work.put((external_id, validated, bootstrap))
        return status, True

    def cancel(self, external_id: str) -> Optional[Dict[str, Any]]:
        with self.store.lock:
            status = self.store.get(external_id)
            if not status:
                return None
            if status["status"] in TERMINAL:
                return status
            previous_status = status["status"]
            status.update({"status": "CANCELLED", "updatedAt": now(),
                           "failureCode": "CANCELLED_BY_CALLER"})
            self.store.save(status)
            process = self.store.processes.get(external_id)
        if process and process.poll() is None:
            self._terminate_process_group(process)
        request_path = self.store.directory(external_id) / "request.json"
        if process is None and previous_status in {"QUEUED", "PREPARING"} and request_path.is_file():
            try:
                request = json.loads(request_path.read_text(encoding="utf-8"))
                self._write_evidence(
                    external_id, request, status.get("startedAt", status.get("createdAt", now())),
                    None, "CANCELLED_BY_CALLER")
            except (OSError, ValueError, KeyError):
                pass
        if process is None and previous_status in {"QUEUED", "PREPARING"}:
            self._finalize_private_cleanup(external_id)
            return self.store.get(external_id)
        return status

    def _worker(self) -> None:
        while True:
            external_id, validated, bootstrap = self.store.work.get()
            try:
                with self.store.lock:
                    status = self.store.get(external_id)
                    if not status or status.get("status") in TERMINAL:
                        continue
                    status.update({"status": "PREPARING", "updatedAt": now()})
                    self.store.save(status)
                sanitized = self._stage_input(external_id, validated)
                if bootstrap:
                    sanitized["_topic4BootstrapSmoke"] = True
                atomic_json(self.store.directory(external_id) / "request.json", sanitized)
                current = self.store.get(external_id)
                if not current or current.get("status") in TERMINAL:
                    continue
                self._execute(external_id)
            except Exception as exc:
                status = self.store.get(external_id)
                if status and status.get("status") not in TERMINAL:
                    staging_failure = isinstance(exc, StageError)
                    status.update({
                        "status": "FAILED",
                        "updatedAt": now(),
                        "failureCode": ("INPUT_STAGING_FAILED" if staging_failure
                                        else "RUNNER_INTERNAL_ERROR"),
                        "failureMessage": ("scoped input staging failed"
                                           if staging_failure
                                           else "runner failed before engine completion"),
                    })
                    self.store.save(status)
                    request_path = self.store.directory(external_id) / "request.json"
                    if request_path.is_file():
                        try:
                            request = json.loads(request_path.read_text(encoding="utf-8"))
                            self._write_evidence(
                                external_id, request,
                                status.get("startedAt", status.get("createdAt", now())),
                                None, status["failureCode"])
                        except (OSError, ValueError, KeyError):
                            pass
            finally:
                self._finalize_private_cleanup(external_id)
                self.store.work.task_done()

    def _execute(self, external_id: str) -> None:
        with self.store.lock:
            status = self.store.get(external_id)
            if not status or status["status"] in TERMINAL:
                return
            if status["status"] != "PREPARING":
                status.update({"status": "PREPARING", "updatedAt": now()})
                self.store.save(status)
        directory = self.store.directory(external_id)
        request_path = directory / "request.json"
        request = json.loads(request_path.read_text(encoding="utf-8"))
        timeout = request["timeoutSeconds"]
        work_directory = self._prepare_work_directory(external_id)
        log_path = work_directory / "adapter.log"
        started_at = now()
        return_code: Optional[int] = None
        timed_out = False
        with log_path.open("wb") as log:
            child_env = dict(os.environ)
            child_env["TOPIC4_JOB_INPUT"] = str(
                self._job_private_path(self.input_root, external_id) / "input.csv")
            child_env["TOPIC4_JOB_WORK_DIR"] = str(work_directory)
            # The MP-SPDZ adapter must not inherit the image-level shared
            # Player-Data location. Each attempt receives a runner-owned tree.
            child_env["MP_SPDZ_PLAYER_DATA"] = str(work_directory / "Player-Data")
            with self.store.lock:
                current = self.store.get(external_id)
                if not current or current["status"] == "CANCELLED":
                    return
                status.update({"status": "RUNNING", "startedAt": started_at,
                               "updatedAt": started_at})
                self.store.save(status)
                process = subprocess.Popen(
                    [str(self.config.adapter), str(request_path), str(directory)],
                    stdin=subprocess.DEVNULL,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    close_fds=True,
                    start_new_session=True,
                    env=child_env,
                )
                self.store.processes[external_id] = process
            try:
                return_code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
                return_code = self._terminate_process_group(process)
            finally:
                with self.store.lock:
                    self.store.processes.pop(external_id, None)
        current = self.store.get(external_id)
        if current and current["status"] == "CANCELLED":
            self._write_evidence(external_id, request, started_at, return_code, "CANCELLED_BY_CALLER")
            return
        if timed_out:
            status.update({
                "status": "FAILED", "updatedAt": now(), "failureCode": "ENGINE_TIMEOUT",
                "failureMessage": "fixed engine command exceeded timeoutSeconds",
            })
            self.store.save(status)
            self._write_evidence(external_id, request, started_at, return_code, "ENGINE_TIMEOUT")
            return
        if return_code != 0:
            failure_code = "ENGINE_UNAVAILABLE" if return_code == 69 else "ENGINE_EXECUTION_FAILED"
            final_status = "ABORTED" if return_code in (64, 70) else "FAILED"
            status.update({
                "status": final_status, "updatedAt": now(), "failureCode": failure_code,
                "failureMessage": "fixed engine adapter exited with code %s" % return_code,
            })
            self.store.save(status)
            self._write_evidence(external_id, request, started_at, return_code, failure_code)
            return
        result_path = directory / "engine-result.json"
        if not result_path.is_file():
            status.update({
                "status": "FAILED", "updatedAt": now(), "failureCode": "ENGINE_RESULT_MISSING",
                "failureMessage": "engine exited successfully without a signed result envelope",
            })
            self.store.save(status)
            self._write_evidence(external_id, request, started_at, return_code, "ENGINE_RESULT_MISSING")
            return
        status.update({"status": "FINALIZING", "updatedAt": now()})
        self.store.save(status)
        result_digest = "sha256:" + hashlib.sha256(
            canonical_bytes(json.loads(result_path.read_text(encoding="utf-8")))).hexdigest()
        if request.get("_topic4BootstrapSmoke") is True:
            try:
                self._write_sfl_smoke_record(external_id, request, result_digest)
            except (OSError, ValueError, KeyError, TypeError):
                status.update({
                    "status": "FAILED", "updatedAt": now(),
                    "failureCode": "BOOTSTRAP_RECORD_FAILED",
                    "failureMessage": "SFL distributed smoke evidence could not be frozen",
                })
                self.store.save(status)
                self._write_evidence(
                    external_id, request, started_at, return_code, "BOOTSTRAP_RECORD_FAILED")
                return
        self._write_evidence(external_id, request, started_at, return_code, None)
        with self.store.lock:
            current = self.store.get(external_id)
            if current and current["status"] == "CANCELLED":
                self._write_evidence(
                    external_id, request, started_at, return_code, "CANCELLED_BY_CALLER")
                return
            status.update({
                "status": "SUCCEEDED", "updatedAt": now(), "completedAt": now(),
                "resultDigest": result_digest,
                "resultMediaType": "application/json",
            })
            self.store.save(status)

    def _write_sfl_smoke_record(
        self, external_id: str, request: Dict[str, Any], result_digest: str
    ) -> None:
        if self.config.provider_id != "KUSCIA_SFL" or not self._sfl_bootstrap_enabled():
            raise ValueError("bootstrap record is restricted to the configured SFL runtime")
        evidence_path = self.store.directory(external_id) / "engine-evidence.json"
        evidence = json.loads(evidence_path.read_text(encoding="utf-8"))
        parties = [item.get("partyId") or item.get("domainId")
                   for item in request["participants"]]
        party_id = os.getenv("TOPIC4_PARTY_ID", "")
        if (request.get("templateId") != "hfl-fedavg-logreg-3p-v1"
                or request.get("protocolVersion") != "sfl-c383e40f/fedavg-logreg"
                or parties != ["A", "B", "C"] or party_id not in parties
                or evidence.get("sourceRevision") != self.config.source_revision
                or evidence.get("transport") != "KUSCIA_CLUSTER_MTLS_ENVOY"):
            raise ValueError("adapter evidence does not identify the fixed SFL A/B/C smoke")
        try:
            from topic4_sfl_smoke import write_record
        except ImportError:  # Repository imports use the runner namespace.
            from runner.topic4_sfl_smoke import write_record
        write_record({
            "contractVersion": "topic4.privacy.sfl-smoke/v1",
            "status": "SUCCEEDED",
            "templateId": request["templateId"],
            "protocolVersion": request["protocolVersion"],
            "parties": parties,
            "partyId": party_id,
            "sourceRevision": self.config.source_revision,
            "transport": evidence["transport"],
            "servingId": evidence["servingId"],
            "contextDigest": evidence["kusciaContextDigest"],
            "resultDigest": result_digest,
            "externalJobId": external_id,
            "completedAt": now(),
        })

    def _write_evidence(
        self,
        external_id: str,
        request: Dict[str, Any],
        started_at: str,
        return_code: Optional[int],
        failure_code: Optional[str],
    ) -> None:
        directory = self.store.directory(external_id)
        engine_evidence_path = directory / "engine-evidence.json"
        engine_evidence: Any = None
        if engine_evidence_path.is_file():
            try:
                engine_evidence = json.loads(engine_evidence_path.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                engine_evidence = {"invalidEngineEvidence": True}
        log_path = self._job_private_path(self.work_root, external_id) / "adapter.log"
        evidence = {
            "contractVersion": "topic4.privacy.evidence/v1",
            "externalJobId": external_id,
            "providerId": self.config.provider_id,
            "engine": self.config.engine,
            "engineVersion": self.config.engine_version,
            "sourceRevision": self.config.source_revision,
            "imageDigest": os.getenv("TOPIC4_IMAGE_DIGEST") or None,
            "templateId": request["templateId"],
            "specDigest": request["specDigest"],
            "requestDigest": "sha256:" + hashlib.sha256(canonical_bytes(request)).hexdigest(),
            "participants": [item.get("partyId") or item.get("domainId") for item in request["participants"]],
            "resultRecipients": request["resultRecipients"],
            "startedAt": started_at,
            "finishedAt": now(),
            "engineExitCode": return_code,
            "failureCode": failure_code,
            "engineLogDigest": sha256_file(log_path) if log_path.is_file() else None,
            "engineLogBytes": log_path.stat().st_size if log_path.is_file() else 0,
            "engineEvidence": engine_evidence,
            "plaintextFallback": False,
        }
        atomic_json(directory / "evidence.json", evidence)


class Handler(BaseHTTPRequestHandler):
    runtime: Runtime
    server_version = "Topic4PrivacyRunner/1.0"
    sys_version = ""

    def log_message(self, fmt: str, *args: Any) -> None:
        sys.stderr.write("%s %s\n" % (self.log_date_time_string(), fmt % args))

    def send_json(self, code: int, value: Any) -> None:
        body = canonical_bytes(value) + b"\n"
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> Any:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise ContractError("invalid Content-Length") from exc
        if length < 1 or length > 1024 * 1024:
            raise ContractError("JSON body must be between 1 byte and 1 MiB")
        try:
            return json.loads(self.rfile.read(length))
        except (UnicodeDecodeError, ValueError) as exc:
            raise ContractError("body is not valid UTF-8 JSON") from exc

    def authenticated(self) -> bool:
        try:
            expected = runtime_auth_token()
        except OSError:
            self.send_json(503, {"status": "UNAVAILABLE", "errorCode": "RUNNER_AUTH_UNAVAILABLE"})
            return False
        authorization = self.headers.get("Authorization", "")
        presented = authorization[7:].strip() if authorization.lower().startswith("bearer ") else ""
        if not expected or not presented or not hmac.compare_digest(expected, presented):
            self.send_json(401, {"status": "UNAUTHORIZED"})
            return False
        return True

    def do_GET(self) -> None:
        path = urlparse(self.path).path.rstrip("/") or "/"
        if path == "/live":
            self.send_json(200, {"status": "RUNNING"})
            return
        if path == "/health":
            available, reasons = self.runtime.config.dependency_status()
            try:
                auth_token = runtime_auth_token()
            except OSError:
                auth_token = ""
            if not auth_token:
                available = False
                reasons = list(reasons) + ["runner bearer token secret is not mounted"]
            image_digest = os.getenv("TOPIC4_IMAGE_DIGEST", "")
            if not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", image_digest):
                available = False
                reasons = list(reasons) + ["TOPIC4_IMAGE_DIGEST is not an immutable sha256 digest"]
            self.send_json(200 if available else 503, {
                "status": "UP" if available else "DOWN",
                "providerId": self.runtime.config.provider_id,
                "engine": self.runtime.config.engine,
                "version": self.runtime.config.engine_version,
                "engineVersion": self.runtime.config.engine_version,
                "sourceRevision": self.runtime.config.source_revision,
                "imageDigest": image_digest or None,
                "templates": [
                    {
                        "templateId": item["templateId"],
                        "protocolVersion": item["protocolVersion"],
                        "securityProfile": item["securityProfile"],
                        "availability": "AVAILABLE" if available else "UNAVAILABLE",
                    }
                    for item in self.runtime.config.data["templates"]
                ],
                "operations": {
                    item["templateId"]: (
                        item["operations"] if isinstance(item.get("operations"), list)
                        else ([item["operation"]] if item.get("operation")
                              else ([item["protocol"]] if item.get("protocol") else []))
                    )
                    for item in self.runtime.config.data["templates"]
                },
                "controlPlaneOperations": sorted({
                    item["operation"] for item in self.runtime.config.data["templates"]
                    if isinstance(item.get("operation"), str) and item["operation"]
                }),
                "securityProfiles": sorted({
                    item["securityProfile"] for item in self.runtime.config.data["templates"]
                }),
                "unavailableReasons": reasons,
                "plaintextFallback": False,
            })
            return
        if not self.authenticated():
            return
        if path == "/bootstrap/status":
            status = self.runtime.bootstrap_status()
            if not status["bootstrapEnabled"]:
                self.send_json(404, {"status": "NOT_FOUND"})
            else:
                self.send_json(200, status)
            return
        if path == "/capabilities":
            payload = self.runtime.config.public_capabilities()
            self.send_json(200 if payload["availability"] == "AVAILABLE" else 503, payload)
            return
        match = re.fullmatch(r"/jobs/([0-9a-f]{32})(?:/(result|evidence))?", path)
        if not match:
            self.send_json(404, {"status": "NOT_FOUND"})
            return
        external_id, resource = match.groups()
        status = self.runtime.store.get(external_id)
        if not status:
            self.send_json(404, {"status": "NOT_FOUND"})
            return
        if not resource:
            self.send_json(200, status)
            return
        if resource == "result":
            if status["status"] != "SUCCEEDED":
                self.send_json(409, {"externalJobId": external_id, "status": status["status"], "resultAvailable": False})
                return
            result_path = self.runtime.store.directory(external_id) / "engine-result.json"
            try:
                self.send_json(200, json.loads(result_path.read_text(encoding="utf-8")))
            except (OSError, ValueError):
                self.send_json(500, {"externalJobId": external_id, "status": "FAILED", "failureCode": "RESULT_CORRUPT"})
            return
        evidence_path = self.runtime.store.directory(external_id) / "evidence.json"
        if not evidence_path.is_file():
            self.send_json(409, {"externalJobId": external_id, "status": status["status"], "evidenceAvailable": False})
            return
        try:
            self.send_json(200, json.loads(evidence_path.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            self.send_json(500, {"externalJobId": external_id, "status": "FAILED", "failureCode": "EVIDENCE_CORRUPT"})

    def do_POST(self) -> None:
        path = urlparse(self.path).path.rstrip("/") or "/"
        if not self.authenticated():
            return
        try:
            if path == "/jobs":
                result, accepted = self.runtime.submit(self.read_json())
                self.send_json(202 if accepted else 503, result)
                return
            if path == "/bootstrap/jobs":
                result, accepted = self.runtime.submit(self.read_json(), bootstrap=True)
                self.send_json(202 if accepted else 503, result)
                return
            match = re.fullmatch(r"/jobs/([0-9a-f]{32})/cancel", path)
            if match:
                status = self.runtime.cancel(match.group(1))
                self.send_json(200 if status else 404, status or {"status": "NOT_FOUND"})
                return
            self.send_json(404, {"status": "NOT_FOUND"})
        except ContractError as exc:
            self.send_json(400, {"status": "REJECTED", "errorCode": "INVALID_JOB_SPEC", "message": str(exc)})
        except StageError as exc:
            self.send_json(422, {"status": "REJECTED", "errorCode": "INPUT_STAGING_FAILED", "message": str(exc)})
        except Exception:
            traceback.print_exc(file=sys.stderr)
            self.send_json(500, {"status": "FAILED", "errorCode": "RUNNER_INTERNAL_ERROR"})


def main() -> None:
    config_path = Path(os.getenv("TOPIC4_PROVIDER_CONFIG", "/opt/topic4/provider.json"))
    state_dir = Path(os.getenv("TOPIC4_STATE_DIR", "/var/lib/topic4-privacy/jobs"))
    bind = os.getenv("TOPIC4_BIND", "0.0.0.0")
    port = int(os.getenv("TOPIC4_PORT", "8080"))
    runtime = Runtime(ProviderConfig(config_path), state_dir)
    Handler.runtime = runtime
    server = ThreadingHTTPServer((bind, port), Handler)
    server.daemon_threads = True
    server.serve_forever()


if __name__ == "__main__":
    main()
