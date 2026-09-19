#!/usr/bin/env python3
"""Authenticated fan-out gateway for isolated Topic4 privacy party runners.

The gateway is orchestration only.  It never reads datasets and never computes a
plaintext replacement.  Party URLs come exclusively from a read-only deployment
file.  Staging bearer tokens exist only in the POST call stack and are not logged
or persisted.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import threading
import time
import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen

ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
TERMINAL = {"SUCCEEDED", "FAILED", "ABORTED", "CANCELLED"}
PROGRESS = {"QUEUED": 0, "PREPARING": 1, "RUNNING": 2, "FINALIZING": 3, "SUCCEEDED": 4}


def canonical(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def stable_submission_digest(request: Dict[str, Any]) -> str:
    """Hash every frozen request field while ignoring scoped-token renewal data."""
    stable = dict(request)
    stable["staging"] = [
        {
            key: value for key, value in item.items()
            if key not in ("token", "expiresAt")
        }
        for item in request["staging"]
    ]
    return "sha256:" + hashlib.sha256(canonical(stable)).hexdigest()


def now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def atomic_json(path: Path, value: Any) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, sort_keys=True, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(str(temporary), str(path))


class GatewayError(RuntimeError):
    pass


class DownstreamError(GatewayError):
    def __init__(self, message: str, status: int = 502):
        super().__init__(message)
        self.status = status


class Gateway:
    def __init__(self) -> None:
        self.provider_id = os.environ["TOPIC4_PROVIDER_ID"]
        self.engine = os.environ["TOPIC4_ENGINE"]
        self.version = os.environ["TOPIC4_ENGINE_VERSION"]
        self.source_revision = os.environ["TOPIC4_SOURCE_REVISION"]
        self.image_digest = os.getenv("TOPIC4_IMAGE_DIGEST", "")
        self.auth_file = Path(os.getenv("TOPIC4_AUTH_TOKEN_FILE", "/run/secrets/topic4-runner/bearer-token"))
        self.provider_config_file = Path(os.getenv(
            "TOPIC4_PROVIDER_CONFIG", "/opt/topic4/provider.json"))
        self.endpoint_file = Path(os.getenv(
            "TOPIC4_PARTY_ENDPOINTS_FILE", "/etc/topic4-privacy/runner-endpoints.json"))
        self.runner_token_dir = Path(os.getenv(
            "TOPIC4_RUNNER_TOKEN_DIR", "/run/secrets/topic4-party-tokens"))
        self.state = Path(os.getenv("TOPIC4_GATEWAY_STATE_DIR", "/var/lib/topic4-privacy/gateway"))
        self.state.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.endpoints: Dict[str, str] = {}
        self.runner_token_files: Dict[str, Path] = {}
        self.agent_config: Dict[str, Dict[str, str]] = {}
        self.templates: Dict[str, Dict[str, Any]] = {}
        self.catalog_error: Optional[str] = None
        self._load_catalog()
        self._load_endpoints()
        self._recover()

    def _load_catalog(self) -> None:
        try:
            value = json.loads(self.provider_config_file.read_text(encoding="utf-8"))
            if value.get("providerId") != self.provider_id:
                raise ValueError("providerId differs from gateway deployment")
            templates = value.get("templates")
            if not isinstance(templates, list) or not templates:
                raise ValueError("templates are missing")
            self.templates = {item["templateId"]: item for item in templates}
        except (OSError, ValueError, KeyError, TypeError) as exc:
            self.catalog_error = "provider catalog is unavailable or invalid: %s" % type(exc).__name__

    def _load_endpoints(self) -> None:
        raw = json.loads(self.endpoint_file.read_text(encoding="utf-8"))
        if not isinstance(raw, dict) or not raw:
            raise GatewayError("fixed party endpoint map must be a non-empty object")
        for party, value in raw.items():
            if not ID_RE.fullmatch(str(party)) or not isinstance(value, dict):
                raise GatewayError("invalid fixed party endpoint map")
            runner_url = value.get("runnerUrl")
            runner_token_file = value.get("runnerTokenFile")
            agent_url = value.get("agentBaseUrl")
            node_name = value.get("nodeName")
            if not all(isinstance(item, str) and item for item in (
                    runner_url, runner_token_file, agent_url, node_name)):
                raise GatewayError(
                    "runnerUrl, runnerTokenFile, agentBaseUrl and nodeName are required for each party")
            parsed = urlparse(runner_url)
            if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.path not in ("", "/"):
                raise GatewayError("invalid fixed runner URL for party %s" % party)
            agent_parsed = urlparse(agent_url)
            if (agent_parsed.scheme not in ("http", "https") or not agent_parsed.hostname
                    or agent_parsed.path not in ("", "/")):
                raise GatewayError("invalid fixed Agent URL for party %s" % party)
            token_path = Path(os.path.normpath(runner_token_file))
            token_root = Path(os.path.normpath(str(self.runner_token_dir)))
            if (not token_path.is_absolute() or token_path.parent != token_root
                    or token_path.name in ("", ".", "..")):
                raise GatewayError("runnerTokenFile must be a direct child of the fixed secret directory")
            if runner_url.rstrip("/") in self.endpoints.values():
                raise GatewayError("fixed party runner URLs must be unique")
            self.endpoints[str(party)] = runner_url.rstrip("/")
            self.runner_token_files[str(party)] = token_path
            self.agent_config[str(party)] = {
                "agentBaseUrl": agent_url.rstrip("/"),
                "nodeName": node_name,
            }

    def _recover(self) -> None:
        for path in self.state.glob("*/status.json"):
            try:
                item = json.loads(path.read_text(encoding="utf-8"))
                if item.get("status") == "DISPATCHING":
                    children = item.get("partyJobs", {})
                    expected = item.get("participants", [])
                    identity = "%s\0%s\0%s" % (
                        item["jobId"], item["attemptId"], self.provider_id)
                    deterministic_child = hashlib.sha256(
                        identity.encode("utf-8")).hexdigest()[:32]
                    recovery_targets = {
                        party: {
                            "externalJobId": children.get(party, {}).get(
                                "externalJobId", deterministic_child),
                            "status": children.get(party, {}).get("status", "DISPATCHING"),
                        }
                        for party in expected
                    }
                    recovered = self._cancel_party_jobs(recovery_targets)
                    item.update({
                        "status": "FAILED",
                        "failureCode": "GATEWAY_RESTARTED_DURING_DISPATCH",
                        "failureMessage": "create a new attempt; staging tokens were not persisted",
                        "partyJobs": self._public_party_statuses(recovered),
                        "updatedAt": now(),
                    })
                    atomic_json(path, item)
                    try:
                        self._write_evidence(item, recovered)
                    except (GatewayError, OSError, ValueError, KeyError):
                        atomic_json(path.with_name("evidence.json"), {
                            "contractVersion": "topic4.privacy.evidence/v1",
                            "externalJobId": item.get("externalJobId"),
                            "providerId": self.provider_id,
                            "templateId": item.get("templateId"),
                            "participants": expected,
                            "finishedAt": item["updatedAt"],
                            "failureCode": item["failureCode"],
                            "plaintextFallback": False,
                        })
            except (OSError, ValueError, KeyError):
                continue

    def token(self) -> str:
        environment_token = os.getenv("TOPIC4_AUTH_TOKEN", "").strip()
        if environment_token:
            return environment_token
        try:
            value = self.auth_file.read_text(encoding="utf-8").strip()
        except OSError as exc:
            raise GatewayError("runner bearer secret is unavailable") from exc
        if not value:
            raise GatewayError("runner bearer secret is empty")
        return value

    def runner_token(self, base: str) -> str:
        party = next((name for name, endpoint in self.endpoints.items()
                      if endpoint == base.rstrip("/")), None)
        if party is None:
            raise GatewayError("downstream URL is absent from the fixed party map")
        path = self.runner_token_files[party]
        try:
            value = path.read_text(encoding="utf-8").strip()
        except OSError as exc:
            raise GatewayError("party %s runner bearer secret is unavailable" % party) from exc
        if not value:
            raise GatewayError("party %s runner bearer secret is empty" % party)
        return value

    def request(self, base: str, method: str, path: str, body: Any = None) -> Dict[str, Any]:
        data = canonical(body) if body is not None else None
        request = Request(
            base + path,
            method=method,
            data=data,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self.runner_token(base),
            },
        )
        try:
            with urlopen(request, timeout=20) as response:
                value = json.loads(response.read())
                if not isinstance(value, dict):
                    raise DownstreamError("party runner returned a non-object response")
                return value
        except HTTPError as exc:
            try:
                detail = json.loads(exc.read()).get("errorCode", "HTTP_%d" % exc.code)
            except Exception:
                detail = "HTTP_%d" % exc.code
            raise DownstreamError("party runner rejected request: %s" % detail, exc.code) from exc
        except (URLError, TimeoutError, OSError, ValueError) as exc:
            raise DownstreamError("party runner is unreachable: %s" % type(exc).__name__) from exc

    def directory(self, external_id: str) -> Path:
        return self.state / external_id

    def load(self, external_id: str) -> Optional[Dict[str, Any]]:
        path = self.directory(external_id) / "status.json"
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return None

    def save(self, status: Dict[str, Any]) -> None:
        atomic_json(self.directory(status["externalJobId"]) / "status.json", status)

    def validate(self, request: Any) -> Tuple[List[str], Dict[str, Dict[str, Any]]]:
        if not isinstance(request, dict):
            raise GatewayError("request must be a JSON object")
        if any(isinstance(key, str) and key.startswith("_topic4") for key in request):
            raise GatewayError("internal Topic4 fields are deployment-owned")
        required = ("jobId", "attemptId", "templateId", "protocolVersion", "imageDigest",
                    "specDigest", "securityProfile", "participants", "resultRecipients",
                    "timeoutSeconds", "enginePolicy", "staging")
        missing = [key for key in required if key not in request]
        if missing:
            raise GatewayError("missing required fields: " + ", ".join(missing))
        extra = sorted(set(request) - set(required))
        if extra:
            raise GatewayError("request contains fields outside the fixed contract: "
                               + ", ".join(extra))
        for key in ("jobId", "attemptId", "templateId"):
            if not isinstance(request[key], str) or not ID_RE.fullmatch(request[key]):
                raise GatewayError("%s is invalid" % key)
        if not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", str(request["imageDigest"])):
            raise GatewayError("imageDigest must be an immutable sha256 digest")
        if (self.image_digest
                and request["imageDigest"].lower() != self.image_digest.lower()):
            raise GatewayError("job imageDigest differs from the running gateway image")
        if not isinstance(request["protocolVersion"], str) or not request["protocolVersion"]:
            raise GatewayError("protocolVersion is required")
        if not isinstance(request["securityProfile"], str) or not request["securityProfile"]:
            raise GatewayError("securityProfile is required")
        template = self.templates.get(request["templateId"])
        if self.catalog_error or template is None:
            raise GatewayError("templateId is not registered by this provider gateway")
        if request["protocolVersion"] != template.get("protocolVersion"):
            raise GatewayError("protocolVersion differs from the registered template")
        if request["securityProfile"] != template.get("securityProfile"):
            raise GatewayError("securityProfile differs from the registered template")
        policies = {
            "secure-sum-3p-v1": set(),
            "private-stats-3p-v1": {"scale"},
            "private-threshold-3p-v1": {"programId", "threshold", "scale"},
            "psi-2p-v1": {"keyColumns", "outputMode"},
            "psi-3p-v1": {"keyColumns", "outputMode"},
            "pir-keyword-2p-v1": {"queryColumn", "valueColumns"},
            "he-paillier-2p-v1": {"operation", "scale"},
            "hfl-fedavg-logreg-3p-v1": {
                "labelColumn", "featureColumns", "epochs", "learningRate", "seed",
            },
            "vfl-secureboost-2p-v1": {
                "labelColumn", "featureColumns", "epochs", "learningRate", "seed",
            },
        }
        if request["templateId"] not in policies:
            raise GatewayError("templateId is not registered")
        if not isinstance(request["enginePolicy"], dict):
            raise GatewayError("enginePolicy must be an object")
        forbidden = set(request["enginePolicy"]) - policies[request["templateId"]]
        if forbidden:
            raise GatewayError("enginePolicy contains fields outside the public template contract")
        participants = request["participants"]
        if not isinstance(participants, list) or len(participants) not in (2, 3):
            raise GatewayError("participants must contain two or three parties")
        if len(participants) not in template.get("participantCounts", []):
            raise GatewayError("participant count differs from the registered template")
        timeout = request["timeoutSeconds"]
        if (not isinstance(timeout, int) or isinstance(timeout, bool) or timeout < 1
                or timeout > template.get("maxTimeoutSeconds", 0)):
            raise GatewayError("timeoutSeconds is outside the registered template limit")
        parties: List[str] = []
        participant_fields = {
            "partyId", "role", "datasetId", "datasetVersion", "datasetSha256",
            "authoritativeSizeBytes", "schemaDigest", "frozenSchema", "fields",
        }
        for item in participants:
            if not isinstance(item, dict) or set(item) - participant_fields:
                raise GatewayError("participant contains fields outside the fixed contract")
            party = item.get("partyId") or item.get("domainId")
            if not isinstance(party, str) or not ID_RE.fullmatch(party):
                raise GatewayError("participant partyId is invalid")
            if party not in self.endpoints:
                raise GatewayError("participant is absent from the fixed runner map")
            parties.append(party)
        if len(set(parties)) != len(parties):
            raise GatewayError("participant identifiers must be unique")
        required_roles = template.get("requiredRoles")
        if not isinstance(required_roles, dict) or parties != list(required_roles):
            raise GatewayError("participant party order differs from the registered template")
        for item, party in zip(participants, parties):
            if item.get("role") != required_roles[party]:
                raise GatewayError("participant role differs from the registered template")
        recipients = request["resultRecipients"]
        if (not isinstance(recipients, list) or not recipients
                or len(set(recipients)) != len(recipients)
                or any(party not in parties for party in recipients)):
            raise GatewayError("resultRecipients must be a non-empty participant subset")
        staging = request["staging"]
        if not isinstance(staging, list) or len(staging) != len(parties):
            raise GatewayError("staging must contain exactly one descriptor per participant")
        stages: Dict[str, Dict[str, Any]] = {}
        stage_fields = {
            "partyId", "datasetId", "datasetVersion", "expectedSha256", "expectedSize",
            "expectedSchema", "expectedSchemaDigest", "nodeId", "nodeName", "agentBaseUrl",
            "sourcePath", "tokenType", "token", "expiresAt",
        }
        for item in staging:
            if not isinstance(item, dict) or set(item) - stage_fields:
                raise GatewayError("staging entry contains fields outside the fixed contract")
            party = item.get("partyId") or item.get("domainId")
            if party not in parties or party in stages:
                raise GatewayError("staging party mapping is missing or duplicated")
            if not isinstance(item.get("token"), str) or not item["token"].strip():
                raise GatewayError("each staging entry requires a one-time token")
            fixed_agent = self.agent_config[party]
            if (str(item.get("nodeName", "")) != fixed_agent["nodeName"]
                    or str(item.get("agentBaseUrl", "")).rstrip("/") != fixed_agent["agentBaseUrl"]):
                raise GatewayError("staging Agent identity differs from the read-only party map")
            stages[party] = item
        return parties, stages

    def _bootstrap_statuses(self) -> Dict[str, Dict[str, Any]]:
        if (self.provider_id != "KUSCIA_SFL"
                or os.getenv("TOPIC4_SFL_BOOTSTRAP_MODE", "") != "1"):
            raise GatewayError("SFL bootstrap endpoint is disabled for this provider")
        statuses: Dict[str, Dict[str, Any]] = {}
        with ThreadPoolExecutor(max_workers=max(1, len(self.endpoints))) as executor:
            futures = {
                executor.submit(self.request, endpoint, "GET", "/bootstrap/status"): party
                for party, endpoint in self.endpoints.items()
            }
            for future in as_completed(futures):
                statuses[futures[future]] = future.result()
        for party, item in statuses.items():
            if item.get("bootstrapEnabled") is not True:
                raise GatewayError("party %s has disabled SFL bootstrap" % party)
            if item.get("approved") is True:
                raise GatewayError("SFL distributed smoke is already operator-approved")
            if item.get("baseAvailability") != "AVAILABLE":
                raise GatewayError("party %s SFL base dependencies are unavailable" % party)
        return statuses

    def submit(self, request: Any, bootstrap: bool = False) -> Dict[str, Any]:
        parties, stages = self.validate(request)
        submission_digest = stable_submission_digest(request)
        identity = "%s\0%s\0%s" % (request["jobId"], request["attemptId"], self.provider_id)
        external_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]
        with self.lock:
            existing = self.load(external_id)
            if existing:
                if not hmac.compare_digest(
                        str(existing.get("submissionDigest", "")), submission_digest):
                    raise GatewayError(
                        "jobId/attemptId already identifies a different immutable request")
                return existing
            if bootstrap:
                bootstrap_statuses = self._bootstrap_statuses()
                if any(item.get("recordPresent") is True
                       for item in bootstrap_statuses.values()):
                    raise GatewayError(
                        "an SFL smoke record already awaits operator approval")
                for status_file in self.state.glob("*/status.json"):
                    try:
                        prior = json.loads(status_file.read_text(encoding="utf-8"))
                    except (OSError, ValueError):
                        continue
                    if (prior.get("bootstrapSmoke") is True
                            and prior.get("status") not in {"FAILED", "ABORTED", "CANCELLED"}):
                        raise GatewayError("an SFL bootstrap job already exists")
            directory = self.directory(external_id)
            directory.mkdir(mode=0o700, parents=True, exist_ok=True)
            sanitized = dict(request)
            sanitized_stages = []
            for party in parties:
                stage = stages[party]
                sanitized_stages.append({
                    key: value for key, value in stage.items()
                    if key not in ("token", "agentBaseUrl", "tokenType")
                })
                sanitized_stages[-1]["tokenDigest"] = "sha256:" + hashlib.sha256(
                    stage["token"].encode("utf-8")).hexdigest()
            sanitized["staging"] = sanitized_stages
            atomic_json(directory / "request.json", sanitized)
            created = now()
            status: Dict[str, Any] = {
                "externalJobId": external_id,
                "status": "DISPATCHING",
                "providerId": self.provider_id,
                "jobId": request["jobId"],
                "attemptId": request["attemptId"],
                "templateId": request["templateId"],
                "submissionDigest": submission_digest,
                "participants": parties,
                # Party runners derive the same private ID from the immutable
                # job identity.  Persist every expected child before fan-out so
                # a runner that accepted the POST but lost its response can
                # still be cancelled during this process or after a restart.
                "partyJobs": {
                    party: {"externalJobId": external_id, "status": "DISPATCHING"}
                    for party in parties
                },
                "createdAt": created,
                "updatedAt": created,
                "resultReference": "/jobs/%s/result" % external_id,
                "resultMediaType": "application/json",
            }
            if bootstrap:
                status["bootstrapSmoke"] = True
            self.save(status)
            try:
                dispatches: Dict[str, Dict[str, Any]] = {}
                def dispatch(party: str) -> Dict[str, Any]:
                    stage = stages[party]
                    local = {key: value for key, value in request.items() if key != "staging"}
                    # Preserve the complete validated snapshot descriptor so the
                    # runner binds its own idempotency check to the same dataset,
                    # schema, Agent and node metadata.  The runner still uses its
                    # deployment-owned Agent URL rather than this advisory value.
                    local["stagedInput"] = dict(stage)
                    return self.request(
                        self.endpoints[party], "POST",
                        "/bootstrap/jobs" if bootstrap else "/jobs", local)

                dispatch_error: Optional[Exception] = None
                with ThreadPoolExecutor(max_workers=max(1, len(parties))) as executor:
                    futures = {executor.submit(dispatch, party): party for party in parties}
                    for future in as_completed(futures):
                        party = futures[future]
                        try:
                            child = future.result()
                            if not child.get("externalJobId"):
                                raise DownstreamError(
                                    "party runner did not return externalJobId")
                            if child["externalJobId"] != external_id:
                                raise DownstreamError(
                                    "party runner returned a non-deterministic externalJobId")
                            dispatches[party] = child
                        except Exception as exc:
                            if dispatch_error is None:
                                dispatch_error = exc
                for party in parties:
                    child = dispatches.get(party)
                    if child is None:
                        continue
                    status["partyJobs"][party] = {
                        "externalJobId": child["externalJobId"],
                        "status": child.get("status", "QUEUED"),
                    }
                status["updatedAt"] = now()
                self.save(status)
                if dispatch_error is not None:
                    if isinstance(dispatch_error, GatewayError):
                        raise dispatch_error
                    raise DownstreamError(
                        "party dispatch failed: %s" % type(dispatch_error).__name__)
            except GatewayError:
                party_statuses = self._cancel_party_jobs(status["partyJobs"])
                status.update({
                    "status": "FAILED", "failureCode": "PARTY_DISPATCH_FAILED",
                    "failureMessage": "at least one isolated party runner rejected or missed dispatch",
                    "updatedAt": now(),
                })
                party_statuses = self._settle_parties(party_statuses)
                status["partyJobs"] = self._public_party_statuses(party_statuses)
                self._write_evidence(status, party_statuses)
                self.save(status)
                raise
            status.update({"status": "QUEUED", "updatedAt": now()})
            self.save(status)
            return status

    def refresh(self, status: Dict[str, Any]) -> Dict[str, Any]:
        if status["status"] in TERMINAL:
            return status
        party_statuses: Dict[str, Dict[str, Any]] = {}
        children = status.get("partyJobs", {})
        with ThreadPoolExecutor(max_workers=max(1, len(children))) as executor:
            futures = {
                executor.submit(
                    self.request, self.endpoints[party], "GET",
                    "/jobs/%s" % child["externalJobId"]): (party, child)
                for party, child in children.items()
            }
            for future in as_completed(futures):
                party, child = futures[future]
                try:
                    party_statuses[party] = future.result()
                except GatewayError:
                    # A status transport failure is not a protocol failure. Keep
                    # the last acknowledged child state and retry on the next
                    # control-plane poll until the job timeout is reached.
                    party_statuses[party] = dict(child)
        status["partyJobs"] = {
            party: {
                key: value for key, value in item.items()
                if key in ("externalJobId", "status", "failureCode", "failureMessage", "resultDigest")
            }
            for party, item in party_statuses.items()
        }
        states = [item.get("status", "FAILED") for item in party_statuses.values()]
        failure = next((item for item in party_statuses.values()
                        if item.get("status") in ("FAILED", "ABORTED", "CANCELLED")), None)
        if failure:
            party_statuses = self._cancel_party_jobs(
                party_statuses, only_nonterminal=True)
            status.update({
                "status": "ABORTED" if failure.get("status") == "ABORTED" else "FAILED",
                "failureCode": failure.get("failureCode", "PARTY_FAILED"),
                "failureMessage": "all-party protocol aborted after a party failure",
                "updatedAt": now(),
            })
            party_statuses = self._settle_parties(party_statuses)
            status["partyJobs"] = self._public_party_statuses(party_statuses)
            self._write_evidence(status, party_statuses)
        elif states and all(value == "SUCCEEDED" for value in states):
            status.update({"status": "FINALIZING", "updatedAt": now()})
            self.save(status)
            try:
                result_value = self._fetch_result(status, party_statuses)
                self._write_result_reference(status, party_statuses)
                self._write_evidence(status, party_statuses)
                status.update({
                    "status": "SUCCEEDED",
                    "resultDigest": "sha256:" + hashlib.sha256(
                        canonical(result_value)).hexdigest(),
                    "completedAt": now(), "updatedAt": now(),
                })
                status.pop("finalizationFailureCode", None)
                status.pop("finalizationFailureMessage", None)
            except (GatewayError, OSError, ValueError, KeyError) as exc:
                status.update({
                    "status": "FINALIZING",
                    "finalizationFailureCode": "RESULT_FINALIZATION_RETRYABLE",
                    "finalizationFailureMessage": type(exc).__name__,
                    "updatedAt": now(),
                })
        elif states:
            status["status"] = min(states, key=lambda value: PROGRESS.get(value, 0))
            status["updatedAt"] = now()
        self.save(status)
        return status

    @staticmethod
    def _public_party_statuses(parties: Dict[str, Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
        return {
            party: {
                key: value for key, value in item.items()
                if key in ("externalJobId", "status", "failureCode", "failureMessage", "resultDigest")
            }
            for party, item in parties.items()
        }

    def _cancel_party_jobs(
        self, parties: Dict[str, Dict[str, Any]], only_nonterminal: bool = False
    ) -> Dict[str, Dict[str, Any]]:
        current = {party: dict(item) for party, item in parties.items()}
        targets = {
            party: item for party, item in current.items()
            if item.get("externalJobId")
            and (not only_nonterminal or item.get("status") not in TERMINAL)
        }
        with ThreadPoolExecutor(max_workers=max(1, len(targets))) as executor:
            futures = {
                executor.submit(
                    self.request, self.endpoints[party], "POST",
                    "/jobs/%s/cancel" % item["externalJobId"], {}): (party, item)
                for party, item in targets.items()
            }
            for future in as_completed(futures):
                party, item = futures[future]
                try:
                    cancelled = future.result()
                    cancelled.setdefault("externalJobId", item["externalJobId"])
                    current[party] = cancelled
                except DownstreamError as exc:
                    if exc.status == 404:
                        # The deterministic lookup confirms that this runner
                        # has no child to clean up.  Treat that as a completed
                        # rollback instead of polling a job that cannot exist.
                        current[party] = {
                            "externalJobId": item["externalJobId"],
                            "status": "CANCELLED",
                            "failureCode": "JOB_NOT_FOUND_DURING_CLEANUP",
                            "failureMessage": "party runner confirmed no job exists",
                        }
                except GatewayError:
                    pass
        return current

    def _settle_parties(self, parties: Dict[str, Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
        """Best-effort bounded backfill after cancellation/failure for complete evidence."""
        current = {party: dict(item) for party, item in parties.items()}
        try:
            configured_wait = float(os.getenv("TOPIC4_EVIDENCE_SETTLE_SECONDS", "5"))
        except ValueError:
            configured_wait = 5.0
        wait_seconds = min(15.0, max(0.0, configured_wait))
        deadline = time.monotonic() + wait_seconds
        while True:
            pending = [party for party, item in current.items()
                       if item.get("status") not in TERMINAL]
            if not pending or time.monotonic() >= deadline:
                return current
            with ThreadPoolExecutor(max_workers=max(1, len(pending))) as executor:
                futures = {}
                for party in pending:
                    child_id = current[party].get("externalJobId")
                    if child_id:
                        futures[executor.submit(
                            self.request, self.endpoints[party], "GET",
                            "/jobs/%s" % child_id)] = (party, child_id)
                for future in as_completed(futures):
                    party, child_id = futures[future]
                    try:
                        refreshed = future.result()
                        refreshed.setdefault("externalJobId", child_id)
                        current[party] = refreshed
                    except GatewayError:
                        # Keep the last real status; the evidence envelope records the
                        # unavailable child rather than inventing a terminal response.
                        pass
            if any(item.get("status") not in TERMINAL for item in current.values()):
                time.sleep(min(0.25, max(0.0, deadline - time.monotonic())))

    def _fetch_result(self, status: Dict[str, Any], parties: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
        request = json.loads((self.directory(status["externalJobId"]) / "request.json").read_text())
        recipient_set = set(request["resultRecipients"])
        results: Dict[str, Dict[str, Any]] = {}
        recipients = {party: item for party, item in parties.items() if party in recipient_set}
        with ThreadPoolExecutor(max_workers=max(1, len(recipients))) as executor:
            futures = {
                executor.submit(
                    self.request, self.endpoints[party], "GET",
                    "/jobs/%s/result" % item["externalJobId"]): (party, item)
                for party, item in recipients.items()
            }
            for future in as_completed(futures):
                party, item = futures[future]
                result = future.result()
                actual = "sha256:" + hashlib.sha256(canonical(result)).hexdigest()
                if not item.get("resultDigest") or not hmac.compare_digest(
                        actual, item["resultDigest"]):
                    raise GatewayError("party %s result differs from its frozen digest" % party)
                results[party] = result
        return {
            "contractVersion": "topic4.privacy.result/v1",
            "externalJobId": status["externalJobId"],
            "providerId": self.provider_id,
            "templateId": status["templateId"],
            "partyResults": results,
            "plaintextFallback": False,
        }

    def _write_result_reference(
        self, status: Dict[str, Any], parties: Dict[str, Dict[str, Any]]) -> None:
        request = json.loads((self.directory(status["externalJobId"]) / "request.json").read_text())
        recipients = set(request["resultRecipients"])
        atomic_json(self.directory(status["externalJobId"]) / "result-reference.json", {
            "contractVersion": "topic4.privacy.result-reference/v1",
            "externalJobId": status["externalJobId"],
            "providerId": self.provider_id,
            "templateId": status["templateId"],
            "recipients": {
                party: {
                    "externalJobId": item["externalJobId"],
                    "resultDigest": item.get("resultDigest"),
                    "resultReference": "/jobs/%s/result" % item["externalJobId"],
                }
                for party, item in parties.items() if party in recipients
            },
            "containsPlaintextResult": False,
        })

    def result(self, status: Dict[str, Any]) -> Dict[str, Any]:
        if status.get("status") != "SUCCEEDED":
            raise GatewayError("result is unavailable until every party succeeds")
        parties = status.get("partyJobs", {})
        value = self._fetch_result(status, parties)
        actual = "sha256:" + hashlib.sha256(canonical(value)).hexdigest()
        if not hmac.compare_digest(actual, status.get("resultDigest", "")):
            raise GatewayError("live party result differs from the frozen result digest")
        return value

    def _write_evidence(self, status: Dict[str, Any], parties: Dict[str, Dict[str, Any]]) -> None:
        request = json.loads((self.directory(status["externalJobId"]) / "request.json").read_text())
        party_evidence: Dict[str, Dict[str, Any]] = {}
        with ThreadPoolExecutor(max_workers=max(1, len(parties))) as executor:
            futures = {
                executor.submit(
                    self.request, self.endpoints[party], "GET",
                    "/jobs/%s/evidence" % item["externalJobId"]): party
                for party, item in parties.items()
            }
            for future in as_completed(futures):
                party = futures[future]
                try:
                    party_evidence[party] = future.result()
                except GatewayError as exc:
                    party_evidence[party] = {"unavailable": True, "reason": str(exc)}
        log_digests = {
            party: value.get("engineLogDigest")
            for party, value in party_evidence.items()
            if isinstance(value, dict) and value.get("engineLogDigest")
        }
        message_evidence = {
            party: value.get("engineEvidence", {}).get("protocolMessages")
            for party, value in party_evidence.items()
            if (isinstance(value, dict)
                and isinstance(value.get("engineEvidence"), dict)
                and value["engineEvidence"].get("protocolMessages") is not None)
        }
        message_summary = {
            "parties": message_evidence,
            "reportedBytes": sum(
                item.get("reportedBytes", 0)
                for item in message_evidence.values()
                if isinstance(item, dict) and isinstance(item.get("reportedBytes"), int)
            ),
        }
        message_summary["summaryDigest"] = "sha256:" + hashlib.sha256(
            canonical(message_summary)).hexdigest()
        evidence = {
            "contractVersion": "topic4.privacy.evidence/v1",
            "providerId": self.provider_id,
            "engine": self.engine,
            "engineVersion": self.version,
            "sourceRevision": self.source_revision,
            "imageDigest": self.image_digest or None,
            "templateId": status["templateId"],
            "specDigest": request["specDigest"],
            "requestDigest": "sha256:" + hashlib.sha256(canonical(request)).hexdigest(),
            "participants": status["participants"],
            "resultRecipients": request["resultRecipients"],
            "startedAt": status.get("createdAt"),
            "finishedAt": now(),
            "engineExitCode": None,
            "failureCode": status.get("failureCode"),
            "engineLogDigest": ("sha256:" + hashlib.sha256(canonical(log_digests)).hexdigest()
                                if log_digests else None),
            "engineLogBytes": sum(
                value.get("engineLogBytes", 0) for value in party_evidence.values()
                if isinstance(value, dict) and isinstance(value.get("engineLogBytes", 0), int)),
            "engineEvidence": {
                "parties": party_evidence,
                "allPartiesRequired": True,
                "protocolMessages": message_summary,
            },
            "plaintextFallback": False,
        }
        atomic_json(self.directory(status["externalJobId"]) / "evidence.json", evidence)

    def cancel(self, status: Dict[str, Any]) -> Dict[str, Any]:
        party_statuses = self._cancel_party_jobs(status.get("partyJobs", {}))
        party_statuses = self._settle_parties(party_statuses)
        status.update({
            "status": "CANCELLED", "failureCode": "CANCELLED_BY_CALLER",
            "failureMessage": "gateway cancellation was sent to every dispatched party",
            "updatedAt": now(),
        })
        status["partyJobs"] = self._public_party_statuses(party_statuses)
        self._write_evidence(status, party_statuses)
        self.save(status)
        return status

    def health(self) -> Tuple[int, Dict[str, Any]]:
        reasons = []
        if self.catalog_error:
            reasons.append(self.catalog_error)
        if not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", self.image_digest):
            reasons.append("TOPIC4_IMAGE_DIGEST is not an immutable sha256 digest")
        try:
            self.token()
        except GatewayError as exc:
            reasons.append(str(exc))
        parties = {}
        expected_templates = {
            template_id: {
                "protocolVersion": value.get("protocolVersion"),
                "securityProfile": value.get("securityProfile"),
            }
            for template_id, value in self.templates.items()
        }
        def check_party(party: str, endpoint: str) -> Tuple[str, List[str]]:
            party_reasons: List[str] = []
            try:
                item = self.request(endpoint, "GET", "/health")
                party_status = str(item.get("status", "DOWN"))
                if item.get("status") != "UP":
                    party_reasons.append("party %s is not UP" % party)
                if item.get("providerId") != self.provider_id:
                    party_reasons.append("party %s provider differs from gateway" % party)
                if item.get("version") != self.version:
                    party_reasons.append("party %s engine version differs from gateway" % party)
                if item.get("sourceRevision") != self.source_revision:
                    party_reasons.append("party %s source revision differs from gateway" % party)
                if item.get("imageDigest") != self.image_digest:
                    party_reasons.append("party %s image digest differs from gateway" % party)
                advertised = {
                    value.get("templateId"): value
                    for value in item.get("templates", [])
                    if isinstance(value, dict) and value.get("templateId")
                }
                if set(advertised) != set(expected_templates):
                    party_reasons.append("party %s template catalog differs from gateway" % party)
                for template_id, frozen in expected_templates.items():
                    actual = advertised.get(template_id, {})
                    if (actual.get("availability") != "AVAILABLE"
                            or actual.get("protocolVersion") != frozen["protocolVersion"]
                            or actual.get("securityProfile") != frozen["securityProfile"]):
                        party_reasons.append("party %s template %s is unavailable or drifted" % (
                            party, template_id))
            except GatewayError as exc:
                party_status = "DOWN"
                party_reasons.append("party %s: %s" % (party, exc))
            return party_status, party_reasons

        # Each runner can execute a bounded engine known-answer check. Probe the
        # three isolated domains concurrently so one 15-second check does not
        # turn into a serial minute-long gateway readiness request. Runner
        # /health already carries the frozen template catalog, so a second
        # /capabilities call would only repeat the same dependency check.
        with ThreadPoolExecutor(max_workers=max(1, len(self.endpoints))) as executor:
            futures = {
                executor.submit(check_party, party, endpoint): party
                for party, endpoint in self.endpoints.items()
            }
            for future in as_completed(futures):
                party = futures[future]
                try:
                    party_status, party_reasons = future.result()
                except Exception as exc:
                    party_status = "DOWN"
                    party_reasons = ["party %s health check failed: %s" % (
                        party, type(exc).__name__)]
                parties[party] = party_status
                reasons.extend(party_reasons)
        parties = {party: parties.get(party, "DOWN") for party in sorted(self.endpoints)}
        up = not reasons
        return (200 if up else 503), {
            "status": "UP" if up else "DOWN",
            "providerId": self.provider_id,
            "version": self.version,
            "imageDigest": self.image_digest or None,
            "templates": [
                {
                    "templateId": template_id,
                    "protocolVersion": value.get("protocolVersion"),
                    "securityProfile": value.get("securityProfile"),
                    "availability": "AVAILABLE" if up else "UNAVAILABLE",
                }
                for template_id, value in sorted(self.templates.items())
            ],
            "operations": {
                template_id: (
                    value["operations"] if isinstance(value.get("operations"), list)
                    else ([value["operation"]] if value.get("operation")
                          else ([value["protocol"]] if value.get("protocol") else []))
                )
                for template_id, value in sorted(self.templates.items())
            },
            "controlPlaneOperations": sorted({
                value["operation"] for value in self.templates.values()
                if isinstance(value.get("operation"), str) and value["operation"]
            }),
            "securityProfiles": sorted({
                value["securityProfile"] for value in self.templates.values()
            }),
            "partyStatus": parties,
            "reasons": reasons,
            "plaintextFallback": False,
        }


class Handler(BaseHTTPRequestHandler):
    gateway: Gateway
    server_version = "Topic4PrivacyGateway/1.0"
    sys_version = ""

    def log_message(self, fmt: str, *args: Any) -> None:
        # Request bodies and authorization headers are intentionally never logged.
        print("%s %s" % (self.log_date_time_string(), fmt % args), file=os.sys.stderr)

    def send_json(self, code: int, value: Any) -> None:
        body = canonical(value) + b"\n"
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def body(self) -> Any:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise GatewayError("invalid Content-Length") from exc
        if length < 1 or length > 1024 * 1024:
            raise GatewayError("JSON body must be between 1 byte and 1 MiB")
        try:
            return json.loads(self.rfile.read(length))
        except (UnicodeError, ValueError) as exc:
            raise GatewayError("body is not valid JSON") from exc

    def authenticated(self) -> bool:
        try:
            expected = self.gateway.token()
        except GatewayError:
            self.send_json(503, {"status": "DOWN", "failureCode": "RUNNER_AUTH_UNAVAILABLE"})
            return False
        header = self.headers.get("Authorization", "")
        presented = header[7:].strip() if header.lower().startswith("bearer ") else ""
        if not presented or not hmac.compare_digest(presented, expected):
            self.send_json(401, {"status": "UNAUTHORIZED"})
            return False
        return True

    def do_GET(self) -> None:
        path = urlparse(self.path).path.rstrip("/") or "/"
        if path == "/live":
            self.send_json(200, {"status": "RUNNING"})
            return
        if path == "/health":
            code, value = self.gateway.health()
            self.send_json(code, value)
            return
        if not self.authenticated():
            return
        if path == "/capabilities":
            code, health = self.gateway.health()
            self.send_json(code, {
                "contractVersion": "topic4.privacy.runner/v1",
                "providerId": self.gateway.provider_id,
                "engine": self.gateway.engine,
                "engineVersion": self.gateway.version,
                "imageDigest": self.gateway.image_digest or None,
                "availability": "AVAILABLE" if code == 200 else "UNAVAILABLE",
                "templates": health["templates"],
                "operations": health["operations"],
                "controlPlaneOperations": health["controlPlaneOperations"],
                "securityProfiles": health["securityProfiles"],
                "unavailableReasons": health["reasons"],
                "plaintextFallback": False,
            })
            return
        match = re.fullmatch(r"/jobs/([0-9a-f]{32})(?:/(result|evidence))?", path)
        if not match:
            self.send_json(404, {"status": "NOT_FOUND"})
            return
        status = self.gateway.load(match.group(1))
        if not status:
            self.send_json(404, {"status": "NOT_FOUND"})
            return
        status = self.gateway.refresh(status)
        resource = match.group(2)
        if not resource:
            self.send_json(200, status)
            return
        if resource == "result":
            try:
                self.send_json(200, self.gateway.result(status))
            except GatewayError as exc:
                self.send_json(409, {
                    "externalJobId": match.group(1), "status": status["status"],
                    "resultAvailable": False, "failureMessage": str(exc),
                })
            return
        artifact = self.gateway.directory(match.group(1)) / "evidence.json"
        if not artifact.is_file():
            self.send_json(409, {
                "externalJobId": match.group(1), "status": status["status"],
                resource + "Available": False,
            })
            return
        self.send_json(200, json.loads(artifact.read_text(encoding="utf-8")))

    def do_POST(self) -> None:
        if not self.authenticated():
            return
        path = urlparse(self.path).path.rstrip("/") or "/"
        try:
            if path == "/jobs":
                self.send_json(202, self.gateway.submit(self.body()))
                return
            if path == "/bootstrap/jobs":
                self.send_json(202, self.gateway.submit(self.body(), bootstrap=True))
                return
            match = re.fullmatch(r"/jobs/([0-9a-f]{32})/cancel", path)
            if match:
                status = self.gateway.load(match.group(1))
                if not status:
                    self.send_json(404, {"status": "NOT_FOUND"})
                else:
                    self.send_json(200, self.gateway.cancel(status))
                return
            self.send_json(404, {"status": "NOT_FOUND"})
        except GatewayError as exc:
            self.send_json(getattr(exc, "status", 400), {
                "status": "FAILED",
                "failureCode": "GATEWAY_REQUEST_FAILED",
                "failureMessage": str(exc),
            })
        except Exception:
            traceback.print_exc()
            self.send_json(500, {"status": "FAILED", "failureCode": "GATEWAY_INTERNAL_ERROR"})


def main() -> None:
    Handler.gateway = Gateway()
    server = ThreadingHTTPServer(
        (os.getenv("TOPIC4_BIND", "0.0.0.0"), int(os.getenv("TOPIC4_PORT", "8080"))),
        Handler,
    )
    server.daemon_threads = True
    server.serve_forever()


if __name__ == "__main__":
    main()
