import hashlib
import importlib.util
import io
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from runner.topic4_privacy_runner import (
    ContractError, ProviderConfig, Runtime, StageError, canonical_bytes,
)
from runner.topic4_privacy_gateway import (
    DownstreamError, Gateway, GatewayError, Handler as GatewayHandler,
    canonical as gateway_canonical,
)

DIGEST = "sha256:" + "a" * 64


def schema_digest(schema):
    return "sha256:" + hashlib.sha256(canonical_bytes(schema)).hexdigest()


def stage(party, token):
    schema = [{"name": "value", "type": "integer"}]
    return {
        "partyId": party,
        "nodeName": "node-" + party.lower(),
        "agentBaseUrl": "http://agent-%s.internal:8080" % party.lower(),
        "token": token,
        "datasetId": "dataset-" + party,
        "datasetVersion": "v1",
        "sourcePath": "/dataset/%s.csv" % party.lower(),
        "expectedSize": 10,
        "expectedSha256": DIGEST,
        "expectedSchema": schema,
        "expectedSchemaDigest": schema_digest(schema),
    }


def request(staging_key="staging"):
    value = {
        "jobId": "job-1",
        "attemptId": "attempt-1",
        "templateId": "secure-sum-3p-v1",
        "protocolVersion": "mp-spdz-0.4.3/malicious-rep-ring",
        "imageDigest": DIGEST,
        "specDigest": DIGEST,
        "securityProfile": "MALICIOUS_3PC_HONEST_MAJORITY",
        "participants": [
            {"partyId": party, "role": "PARTY", "fields": ["value"]}
            for party in ("A", "B", "C")
        ],
        "resultRecipients": ["A"],
        "timeoutSeconds": 60,
        "enginePolicy": {},
    }
    value[staging_key] = [stage(party, "one-time-" + party) for party in ("A", "B", "C")]
    return value


def external_id_for(value, provider_id="MP_SPDZ"):
    identity = "%s\0%s\0%s" % (value["jobId"], value["attemptId"], provider_id)
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]


def gateway_endpoint_map(root):
    token_root = root / "party-tokens"
    token_root.mkdir()
    os.environ["TOPIC4_RUNNER_TOKEN_DIR"] = str(token_root)
    endpoints = {}
    for party in ("A", "B", "C"):
        token_file = token_root / party
        token_file.write_text("runner-%s-secret\n" % party.lower(), encoding="utf-8")
        endpoints[party] = {
            "runnerUrl": "http://runner-%s.internal:8080" % party.lower(),
            "runnerTokenFile": str(token_file),
            "agentBaseUrl": "http://agent-%s.internal:8080" % party.lower(),
            "nodeName": "node-" + party.lower(),
        }
    return endpoints


class RunnerContractTest(unittest.TestCase):
    def setUp(self):
        self.old = dict(os.environ)
        self.private = tempfile.TemporaryDirectory()
        private_root = Path(self.private.name)
        os.environ["TOPIC4_IMAGE_DIGEST"] = DIGEST
        os.environ["TOPIC4_INPUT_DIR"] = str(private_root / "inputs")
        os.environ["TOPIC4_WORK_DIR"] = str(private_root / "work")

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.old)
        self.private.cleanup()

    def test_local_runner_requires_frozen_protocol_security_and_image(self):
        with tempfile.TemporaryDirectory() as state:
            runtime = Runtime(ProviderConfig(ROOT / "providers/mpspdz/provider.json"), Path(state))
            local = request()
            local["stagedInput"] = local.pop("staging")[0]
            self.assertEqual(runtime.validate(local)["templateId"], "secure-sum-3p-v1")
            for field, bad in (
                ("protocolVersion", "wrong"),
                ("securityProfile", "SEMI_HONEST"),
                ("imageDigest", "sha256:" + "b" * 64),
            ):
                invalid = dict(local)
                invalid[field] = bad
                with self.assertRaises(ContractError):
                    runtime.validate(invalid)
            reordered = dict(local)
            reordered["participants"] = list(reversed(local["participants"]))
            with self.assertRaises(ContractError):
                runtime.validate(reordered)
            wrong_role = dict(local)
            wrong_role["participants"] = [dict(item) for item in local["participants"]]
            wrong_role["participants"][1]["role"] = "RECEIVER"
            with self.assertRaises(ContractError):
                runtime.validate(wrong_role)
            injected = dict(local)
            injected["command"] = ["/bin/sh"]
            with self.assertRaises(ContractError):
                runtime.validate(injected)
            injected_stage = dict(local)
            injected_stage["stagedInput"] = dict(local["stagedInput"], upload="payload")
            with self.assertRaises(ContractError):
                runtime.validate(injected_stage)

    def test_dependency_health_command_is_single_flight_cached_and_skippable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            counter = root / "counter"
            check = root / "health.py"
            check.write_text(
                "import pathlib,sys\n"
                "p=pathlib.Path(sys.argv[1])\n"
                "p.write_text(str(int(p.read_text())+1) if p.exists() else '1')\n",
                encoding="utf-8",
            )
            catalog = {
                "providerId": "TEST", "engine": "test", "engineVersion": "1",
                "sourceRevision": "revision", "adapterExecutable": sys.executable,
                "healthCommand": [sys.executable, str(check), str(counter)],
                "templates": [{"templateId": "test", "protocolVersion": "1",
                               "securityProfile": "TEST"}],
            }
            path = root / "provider.json"
            path.write_text(json.dumps(catalog), encoding="utf-8")
            config = ProviderConfig(path)
            self.assertEqual(config.dependency_status(include_health_command=False), (True, []))
            self.assertFalse(counter.exists())
            self.assertEqual(config.dependency_status(), (True, []))
            self.assertEqual(config.dependency_status(), (True, []))
            self.assertEqual(counter.read_text(encoding="utf-8"), "1")

    def test_party_staging_consumes_token_verifies_snapshot_and_cleans_raw_input(self):
        payload = b"value\n40\n"

        class Agent(BaseHTTPRequestHandler):
            def log_message(self, *args):
                return

            def do_GET(self):
                if self.headers.get("Authorization") != "Bearer single-use-secret":
                    self.send_response(401)
                    self.end_headers()
                    return
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Agent)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                os.environ.update({
                    "TOPIC4_AGENT_BASE_URL": "http://127.0.0.1:%d" % server.server_port,
                    "TOPIC4_AGENT_ALLOWED_HOSTS": "127.0.0.1",
                    "TOPIC4_INPUT_DIR": str(root / "inputs"),
                })
                runtime = Runtime(
                    ProviderConfig(ROOT / "providers/mpspdz/provider.json"), root / "state")
                value = request()
                value["stagedInput"] = value.pop("staging")[0]
                value["stagedInput"].update({
                    "token": "single-use-secret",
                    "expectedSize": len(payload),
                    "expectedSha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
                    "expectedSchema": [{"name": "value"}],
                    "expectedSchemaDigest": schema_digest([{"name": "value"}]),
                })
                external_id = "e" * 32
                sanitized = runtime._stage_input(external_id, value)
                serialized = json.dumps(sanitized)
                self.assertNotIn("single-use-secret", serialized)
                self.assertTrue((root / "inputs" / external_id / "input.csv").is_file())
                with self.assertRaises(StageError):
                    runtime._stage_input("a" * 32, value)
                runtime._cleanup_input(external_id)
                self.assertFalse((root / "inputs" / external_id / "input.csv").exists())
        finally:
            server.shutdown()
            server.server_close()

    def test_submit_queues_slow_staging_without_persisting_token(self):
        payload = b"value\n40\n"
        entered = threading.Event()
        release = threading.Event()

        class SlowAgent(BaseHTTPRequestHandler):
            calls = 0

            def log_message(self, *args):
                return

            def do_GET(self):
                SlowAgent.calls += 1
                entered.set()
                release.wait(timeout=5)
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        server = ThreadingHTTPServer(("127.0.0.1", 0), SlowAgent)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                adapter = root / "adapter.py"
                adapter.write_text(
                    "#!/usr/bin/env python3\n"
                    "import json,pathlib,sys\n"
                    "d=pathlib.Path(sys.argv[2])\n"
                    "(d/'engine-result.json').write_text(json.dumps({'released':True,'value':40}))\n"
                    "(d/'engine-evidence.json').write_text(json.dumps({'protocol':'test'}))\n",
                    encoding="utf-8",
                )
                adapter.chmod(0o755)
                catalog = json.loads(
                    (ROOT / "providers/mpspdz/provider.json").read_text(encoding="utf-8"))
                catalog.update({
                    "adapterExecutable": str(adapter), "requiredExecutables": [],
                    "requiredFiles": [], "requiredPythonImports": [],
                    "requiredEnvironment": {}, "healthCommand": None,
                })
                config_path = root / "provider.json"
                config_path.write_text(json.dumps(catalog), encoding="utf-8")
                os.environ.update({
                    "TOPIC4_AGENT_BASE_URL": "http://127.0.0.1:%d" % server.server_port,
                    "TOPIC4_AGENT_ALLOWED_HOSTS": "127.0.0.1",
                    "TOPIC4_INPUT_DIR": str(root / "inputs"),
                    "TOPIC4_WORK_DIR": str(root / "work"),
                })
                runtime = Runtime(ProviderConfig(config_path), root / "state")
                value = request()
                value["stagedInput"] = value.pop("staging")[0]
                value["stagedInput"].update({
                    "token": "slow-single-use-token",
                    "tokenType": "Topic4Scope",
                    "expiresAt": "2030-01-01T00:00:00Z",
                    "nodeId": "node-a-id",
                    "expectedSize": len(payload),
                    "expectedSha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
                    "expectedSchema": [{"name": "value"}],
                    "expectedSchemaDigest": schema_digest([{"name": "value"}]),
                })
                started = time.monotonic()
                submitted, accepted = runtime.submit(value)
                self.assertTrue(accepted)
                self.assertLess(time.monotonic() - started, 0.5)
                external_id = submitted["externalJobId"]
                persisted = (root / "state" / external_id / "request.json").read_text()
                self.assertNotIn("slow-single-use-token", persisted)
                self.assertTrue(entered.wait(timeout=2))

                resigned = json.loads(json.dumps(value))
                resigned["stagedInput"].update({
                    "token": "fresh-signed-token",
                    "expiresAt": "2030-01-01T00:05:00Z",
                })
                duplicate, duplicate_accepted = runtime.submit(resigned)
                self.assertTrue(duplicate_accepted)
                self.assertEqual(duplicate["externalJobId"], external_id)
                persisted_after_retry = (
                    root / "state" / external_id / "request.json").read_text()
                self.assertEqual(persisted_after_retry, persisted)
                self.assertNotIn("fresh-signed-token", persisted_after_retry)
                self.assertNotIn(
                    hashlib.sha256(b"fresh-signed-token").hexdigest(),
                    persisted_after_retry,
                )
                self.assertNotIn("2030-01-01T00:05:00Z", persisted_after_retry)
                self.assertFalse((
                    root / "state" / ".consumed-stage-tokens"
                    / hashlib.sha256(b"fresh-signed-token").hexdigest()
                ).exists())

                conflicts = []
                conflict = json.loads(json.dumps(value))
                conflict["specDigest"] = "sha256:" + "b" * 64
                conflicts.append(conflict)
                for field, changed in (
                    ("expectedSha256", "sha256:" + "b" * 64),
                    ("sourcePath", "/dataset/changed.csv"),
                    ("tokenType", "DifferentScope"),
                    ("nodeId", "node-a-rebound"),
                ):
                    conflict = json.loads(json.dumps(value))
                    conflict["stagedInput"][field] = changed
                    conflicts.append(conflict)
                for conflict in conflicts:
                    with self.assertRaises(ContractError):
                        runtime.submit(conflict)
                release.set()
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    status = runtime.store.get(external_id)
                    if status and status["status"] in {"SUCCEEDED", "FAILED", "ABORTED"}:
                        break
                    time.sleep(0.02)
                self.assertEqual(runtime.store.get(external_id)["status"], "SUCCEEDED")
                self.assertEqual(SlowAgent.calls, 1)
                self.assertFalse((root / "inputs" / external_id).exists())
        finally:
            release.set()
            server.shutdown()
            server.server_close()

    def test_runner_restart_fails_job_and_purges_all_staged_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / "state"
            job = state / ("d" * 32)
            job.mkdir(parents=True)
            (job / "status.json").write_text(json.dumps({
                "externalJobId": "d" * 32, "status": "RUNNING",
            }), encoding="utf-8")
            (job / "request.json").write_text(json.dumps({
                "jobId": "job-restarted", "attemptId": "attempt-restarted",
            }), encoding="utf-8")
            artifact = job / "artifacts" / "partial-predictions.csv"
            artifact.parent.mkdir()
            artifact.write_text("derived-secret", encoding="utf-8")
            inputs = root / "inputs" / ("d" * 32)
            inputs.mkdir(parents=True)
            (inputs / "input.csv").write_text("value\nsecret\n", encoding="utf-8")
            (inputs / "input.csv.part").write_text("partial", encoding="utf-8")
            orphan = root / "inputs" / "orphan-attempt"
            orphan.mkdir()
            (orphan / "input.csv.part").write_text("partial", encoding="utf-8")
            work = root / "work" / ("d" * 32)
            (work / "Player-Data").mkdir(parents=True)
            for relative in (
                    "Player-Data/Input-P0-0", "apsi-query.txt", "apsi-db.csv",
                    "psi-launch.json", "psi.log", "protocol-output.csv", "training.csv"):
                target = work / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("derived-secret", encoding="utf-8")
            outside = root / "outside-sentinel"
            outside.write_text("must-survive", encoding="utf-8")
            (root / "work" / "orphan-link").symlink_to(outside)
            partial_model = root / "models/job-restarted/attempt-restarted/A/partial-model"
            partial_model.parent.mkdir(parents=True)
            partial_model.write_text("derived-secret", encoding="utf-8")
            os.environ["TOPIC4_INPUT_DIR"] = str(root / "inputs")
            os.environ["TOPIC4_WORK_DIR"] = str(root / "work")
            os.environ["TOPIC4_MODEL_DIR"] = str(root / "models")
            runtime = Runtime(ProviderConfig(ROOT / "providers/mpspdz/provider.json"), state)
            self.assertEqual(runtime.store.get("d" * 32)["status"], "FAILED")
            restart_evidence = json.loads((job / "evidence.json").read_text())
            self.assertEqual(restart_evidence["failureCode"], "RUNTIME_RESTARTED")
            self.assertFalse(inputs.exists())
            self.assertFalse(orphan.exists())
            self.assertFalse(work.exists())
            self.assertFalse((root / "work" / "orphan-link").exists())
            self.assertFalse(artifact.exists())
            self.assertFalse((root / "models/job-restarted/attempt-restarted").exists())
            self.assertEqual(outside.read_text(encoding="utf-8"), "must-survive")

    def test_cancel_escalates_to_sigkill_and_runner_removes_all_private_work(self):
        payload = b"value\n918273645\n"

        class Agent(BaseHTTPRequestHandler):
            def log_message(self, *args):
                return

            def do_GET(self):
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Agent)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                adapter = root / "blocking-adapter.py"
                adapter.write_text(
                    "#!/usr/bin/env python3\n"
                    "import os, pathlib, signal, sys, time\n"
                    "work = pathlib.Path(os.environ['TOPIC4_JOB_WORK_DIR'])\n"
                    "player = pathlib.Path(os.environ['MP_SPDZ_PLAYER_DATA'])\n"
                    "state = pathlib.Path(sys.argv[2])\n"
                    "def ignore_term(signum, frame):\n"
                    "    (state / 'term-seen').write_text(str(signum))\n"
                    "signal.signal(signal.SIGTERM, ignore_term)\n"
                    "player.mkdir(parents=True)\n"
                    "for name in ('Input-P0-0', 'share-cache'):\n"
                    "    (player / name).write_text('918273645')\n"
                    "for name in ('apsi-query.txt', 'apsi-db.csv', 'psi-launch.json', "
                    "'psi.log', 'protocol-output.csv', 'training.csv'):\n"
                    "    (work / name).write_text('918273645')\n"
                    "artifact = state / 'artifacts' / 'predictions.csv'\n"
                    "artifact.parent.mkdir()\n"
                    "artifact.write_text('918273645')\n"
                    "(state / 'engine-result.json').write_text('{\"released\":918273645}')\n"
                    "model = pathlib.Path(os.environ['TOPIC4_MODEL_DIR']) / 'job-1' / "
                    "'attempt-1' / 'A' / 'partial-model'\n"
                    "model.parent.mkdir(parents=True)\n"
                    "model.write_text('918273645')\n"
                    "(state / 'adapter-started').write_text('ready')\n"
                    "print('adapter-log-918273645', flush=True)\n"
                    "while True: time.sleep(1)\n",
                    encoding="utf-8",
                )
                adapter.chmod(0o755)
                config_value = json.loads(
                    (ROOT / "providers/mpspdz/provider.json").read_text(encoding="utf-8"))
                config_value.update({
                    "adapterExecutable": str(adapter), "requiredExecutables": [],
                    "requiredFiles": [], "healthCommand": None,
                })
                config_path = root / "provider.json"
                config_path.write_text(json.dumps(config_value), encoding="utf-8")
                os.environ.update({
                    "TOPIC4_AGENT_BASE_URL": "http://127.0.0.1:%d" % server.server_port,
                    "TOPIC4_AGENT_ALLOWED_HOSTS": "127.0.0.1",
                    "TOPIC4_INPUT_DIR": str(root / "inputs"),
                    "TOPIC4_WORK_DIR": str(root / "work"),
                    "TOPIC4_MODEL_DIR": str(root / "models"),
                    "TOPIC4_TERMINATION_GRACE_SECONDS": "0.1",
                })
                runtime = Runtime(ProviderConfig(config_path), root / "state")
                value = request()
                value["stagedInput"] = value.pop("staging")[0]
                value["stagedInput"].update({
                    "token": "cancel-token",
                    "expectedSize": len(payload),
                    "expectedSha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
                    "expectedSchema": [{"name": "value"}],
                    "expectedSchemaDigest": schema_digest([{"name": "value"}]),
                })
                submitted, accepted = runtime.submit(value)
                self.assertTrue(accepted)
                external_id = submitted["externalJobId"]
                marker = root / "state" / external_id / "adapter-started"
                deadline = time.monotonic() + 5
                while not marker.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(marker.exists(), "adapter did not start")
                cancelled = runtime.cancel(external_id)
                self.assertEqual(cancelled["status"], "CANCELLED")
                self.assertTrue((root / "state" / external_id / "term-seen").is_file())
                deadline = time.monotonic() + 5
                job = root / "state" / external_id
                while ((root / "inputs" / external_id).exists()
                       or (root / "work" / external_id).exists()
                       or (root / "models/job-1/attempt-1").exists()
                       or (job / "artifacts").exists()
                       or (job / "engine-result.json").exists()) and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertFalse((root / "inputs" / external_id).exists())
                self.assertFalse((root / "work" / external_id).exists())
                self.assertFalse((root / "models/job-1/attempt-1").exists())
                self.assertFalse((job / "artifacts").exists())
                self.assertFalse((job / "engine-result.json").exists())
                self.assertFalse((job / "engine.log").exists())
                persisted = "".join(
                    path.read_text(encoding="utf-8", errors="replace")
                    for path in job.rglob("*") if path.is_file())
                self.assertNotIn("adapter-log-918273645", persisted)
        finally:
            server.shutdown()
            server.server_close()

    def test_gateway_fanout_uses_fixed_urls_and_never_persists_tokens(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            endpoints = gateway_endpoint_map(root)
            (root / "endpoints.json").write_text(json.dumps(endpoints), encoding="utf-8")
            (root / "token").write_text("gateway-secret\n", encoding="utf-8")
            os.environ.update({
                "TOPIC4_PROVIDER_ID": "MP_SPDZ",
                "TOPIC4_ENGINE": "MP-SPDZ",
                "TOPIC4_ENGINE_VERSION": "0.4.3",
                "TOPIC4_SOURCE_REVISION": "26a605368e40fed3a7e9cee78c9a3f4390b85eb5",
                "TOPIC4_IMAGE_DIGEST": DIGEST,
                "TOPIC4_AUTH_TOKEN_FILE": str(root / "token"),
                "TOPIC4_PARTY_ENDPOINTS_FILE": str(root / "endpoints.json"),
                "TOPIC4_GATEWAY_STATE_DIR": str(root / "state"),
                "TOPIC4_PROVIDER_CONFIG": str(ROOT / "providers/mpspdz/provider.json"),
            })
            gateway = Gateway()
            self.assertEqual(gateway.token(), "gateway-secret")
            self.assertEqual(gateway.runner_token(endpoints["A"]["runnerUrl"]),
                             "runner-a-secret")
            self.assertNotEqual(gateway.token(), gateway.runner_token(
                endpoints["A"]["runnerUrl"]))
            calls = []
            dispatch_barrier = threading.Barrier(3)

            def fake(base, method, path, body=None):
                calls.append((base, method, path, body))
                if path == "/health":
                    catalog = json.loads((ROOT / "providers/mpspdz/provider.json").read_text())
                    return {"status": "UP", "providerId": "MP_SPDZ",
                            "version": "0.4.3", "imageDigest": DIGEST,
                            "sourceRevision":
                                "26a605368e40fed3a7e9cee78c9a3f4390b85eb5",
                            "templates": [
                        dict(item, availability="AVAILABLE") for item in catalog["templates"]
                    ]}
                if path == "/jobs":
                    dispatch_barrier.wait(timeout=2)
                    return {"externalJobId": external_id_for(body), "status": "QUEUED"}
                if path.endswith("/evidence"):
                    return {"contractVersion": "topic4.privacy.evidence/v1"}
                if method == "GET" and re.fullmatch(r"/jobs/[0-9a-f]{32}", path):
                    return {"externalJobId": path.rsplit("/", 1)[1], "status": "QUEUED"}
                return {"status": "CANCELLED"}

            gateway.request = fake
            value = request()
            for index, item in enumerate(value["staging"]):
                item.update({
                    "tokenType": "Topic4Scope",
                    "expiresAt": "2030-01-01T00:00:00Z",
                    "nodeId": "node-%d-id" % index,
                })
            submitted = gateway.submit(value)
            self.assertEqual(submitted["status"], "QUEUED")
            persisted = (gateway.directory(submitted["externalJobId"]) / "request.json").read_text()
            self.assertNotIn("one-time-", persisted)
            self.assertNotIn("agentBaseUrl", persisted)
            posts = [call for call in calls if call[2] == "/jobs"]
            self.assertEqual(len(posts), 3)
            self.assertEqual(
                {call[0] for call in posts},
                {"http://runner-a.internal:8080", "http://runner-b.internal:8080",
                 "http://runner-c.internal:8080"},
            )
            self.assertTrue(all("stagedInput" in call[3] and "staging" not in call[3] for call in posts))
            self.assertTrue(all({
                "partyId", "nodeId", "nodeName", "agentBaseUrl", "tokenType",
                "token", "expiresAt", "datasetId", "datasetVersion", "sourcePath",
                "expectedSize", "expectedSha256", "expectedSchema",
                "expectedSchemaDigest",
            }.issubset(call[3]["stagedInput"]) for call in posts))

            resigned = json.loads(json.dumps(value))
            for index, item in enumerate(resigned["staging"]):
                item["token"] = "fresh-signed-token-%d" % index
                item["expiresAt"] = "2030-01-01T00:05:00Z"
            duplicate = gateway.submit(resigned)
            self.assertEqual(duplicate["externalJobId"], submitted["externalJobId"])
            self.assertEqual(len([call for call in calls if call[2] == "/jobs"]), 3)
            persisted_after_retry = (
                gateway.directory(submitted["externalJobId"]) / "request.json").read_text()
            self.assertEqual(persisted_after_retry, persisted)
            self.assertNotIn("fresh-signed-token-", persisted_after_retry)
            self.assertNotIn("2030-01-01T00:05:00Z", persisted_after_retry)
            for index in range(3):
                self.assertNotIn(hashlib.sha256(
                    ("fresh-signed-token-%d" % index).encode("utf-8")
                ).hexdigest(), persisted_after_retry)

            conflicts = []
            conflict = json.loads(json.dumps(value))
            conflict["specDigest"] = "sha256:" + "b" * 64
            conflicts.append(conflict)
            for field, changed in (
                ("expectedSha256", "sha256:" + "b" * 64),
                ("sourcePath", "/dataset/changed.csv"),
                ("tokenType", "DifferentScope"),
                ("nodeId", "node-a-rebound"),
            ):
                conflict = json.loads(json.dumps(value))
                conflict["staging"][0][field] = changed
                conflicts.append(conflict)
            for conflict in conflicts:
                with self.assertRaises(GatewayError):
                    gateway.submit(conflict)
            self.assertEqual(len([call for call in calls if call[2] == "/jobs"]), 3)
            code, health = gateway.health()
            self.assertEqual((code, health["status"]), (200, "UP"))
            self.assertEqual(len([call for call in calls if call[2] == "/health"]), 3)
            self.assertFalse(any(call[2] == "/capabilities" for call in calls))
            cancelled = gateway.cancel(submitted)
            self.assertEqual(cancelled["status"], "CANCELLED")
            self.assertTrue((gateway.directory(submitted["externalJobId"]) / "evidence.json").is_file())
            evidence = json.loads(
                (gateway.directory(submitted["externalJobId"]) / "evidence.json").read_text())
            self.assertEqual(evidence["imageDigest"], DIGEST)

    def test_gateway_rejects_control_plane_agent_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            endpoints = gateway_endpoint_map(root)
            (root / "endpoints.json").write_text(json.dumps(endpoints), encoding="utf-8")
            (root / "token").write_text("secret", encoding="utf-8")
            os.environ.update({
                "TOPIC4_PROVIDER_ID": "MP_SPDZ", "TOPIC4_ENGINE": "MP-SPDZ",
                "TOPIC4_ENGINE_VERSION": "0.4.3", "TOPIC4_SOURCE_REVISION": "revision",
                "TOPIC4_IMAGE_DIGEST": DIGEST, "TOPIC4_AUTH_TOKEN_FILE": str(root / "token"),
                "TOPIC4_PARTY_ENDPOINTS_FILE": str(root / "endpoints.json"),
                "TOPIC4_GATEWAY_STATE_DIR": str(root / "state"),
                "TOPIC4_PROVIDER_CONFIG": str(ROOT / "providers/mpspdz/provider.json"),
            })
            gateway = Gateway()
            invalid = request()
            invalid["staging"][0]["agentBaseUrl"] = "http://attacker.invalid:8080"
            with self.assertRaises(GatewayError):
                gateway.validate(invalid)
            invalid_order = request()
            invalid_order["participants"] = list(reversed(invalid_order["participants"]))
            with self.assertRaises(GatewayError):
                gateway.validate(invalid_order)
            injected = request()
            injected["image"] = "attacker/image:latest"
            with self.assertRaises(GatewayError):
                gateway.validate(injected)
            injected_party = request()
            injected_party["participants"][0]["command"] = ["/bin/sh"]
            with self.assertRaises(GatewayError):
                gateway.validate(injected_party)
            injected_stage = request()
            injected_stage["staging"][0]["upload"] = "payload"
            with self.assertRaises(GatewayError):
                gateway.validate(injected_stage)

    def test_gateway_cancels_all_deterministic_children_after_accepted_response_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            endpoints = gateway_endpoint_map(root)
            (root / "endpoints.json").write_text(json.dumps(endpoints), encoding="utf-8")
            (root / "token").write_text("gateway-secret\n", encoding="utf-8")
            os.environ.update({
                "TOPIC4_PROVIDER_ID": "MP_SPDZ", "TOPIC4_ENGINE": "MP-SPDZ",
                "TOPIC4_ENGINE_VERSION": "0.4.3",
                "TOPIC4_SOURCE_REVISION": "26a605368e40fed3a7e9cee78c9a3f4390b85eb5",
                "TOPIC4_IMAGE_DIGEST": DIGEST,
                "TOPIC4_AUTH_TOKEN_FILE": str(root / "token"),
                "TOPIC4_PARTY_ENDPOINTS_FILE": str(root / "endpoints.json"),
                "TOPIC4_GATEWAY_STATE_DIR": str(root / "state"),
                "TOPIC4_PROVIDER_CONFIG": str(ROOT / "providers/mpspdz/provider.json"),
                "TOPIC4_EVIDENCE_SETTLE_SECONDS": "0",
            })
            gateway = Gateway()
            calls = []
            accepted = set()

            def fake(base, method, path, body=None):
                party = base.split("runner-", 1)[1][0].upper()
                calls.append((party, method, path))
                if method == "POST" and path == "/jobs":
                    if party == "C":
                        raise DownstreamError("party runner rejected request: HTTP_503", 503)
                    accepted.add(party)
                    if party == "A":
                        # Model a runner that durably accepted the job before
                        # the HTTP response was lost at the gateway.
                        raise DownstreamError("party runner is unreachable: TimeoutError")
                    return {"externalJobId": external_id_for(body), "status": "QUEUED"}
                if method == "POST" and path.endswith("/cancel"):
                    if party not in accepted:
                        raise DownstreamError("party runner rejected request: HTTP_404", 404)
                    accepted.remove(party)
                    return {"externalJobId": path.split("/")[2], "status": "CANCELLED"}
                if path.endswith("/evidence"):
                    raise DownstreamError("party runner rejected request: HTTP_404", 404)
                raise GatewayError("unexpected request")

            gateway.request = fake
            with self.assertRaises(DownstreamError):
                gateway.submit(request())

            child_id = external_id_for(request())
            status = gateway.load(child_id)
            self.assertEqual(status["status"], "FAILED")
            self.assertEqual(status["failureCode"], "PARTY_DISPATCH_FAILED")
            self.assertEqual(set(status["partyJobs"]), {"A", "B", "C"})
            self.assertTrue(all(
                item["externalJobId"] == child_id
                for item in status["partyJobs"].values()
            ))
            self.assertTrue(all(
                item["status"] == "CANCELLED"
                for item in status["partyJobs"].values()
            ))
            self.assertEqual(
                status["partyJobs"]["C"]["failureCode"],
                "JOB_NOT_FOUND_DURING_CLEANUP",
            )
            cancel_calls = [item for item in calls if item[2].endswith("/cancel")]
            self.assertEqual({item[0] for item in cancel_calls}, {"A", "B", "C"})
            self.assertTrue(all(item[2] == "/jobs/%s/cancel" % child_id
                                for item in cancel_calls))
            self.assertEqual(accepted, set())
            self.assertTrue((gateway.directory(child_id) / "evidence.json").is_file())

    def test_gateway_does_not_persist_recipient_plaintext_result(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            endpoints = gateway_endpoint_map(root)
            (root / "endpoints.json").write_text(json.dumps(endpoints), encoding="utf-8")
            (root / "token").write_text("gateway-secret\n", encoding="utf-8")
            os.environ.update({
                "TOPIC4_PROVIDER_ID": "MP_SPDZ", "TOPIC4_ENGINE": "MP-SPDZ",
                "TOPIC4_ENGINE_VERSION": "0.4.3",
                "TOPIC4_SOURCE_REVISION": "26a605368e40fed3a7e9cee78c9a3f4390b85eb5",
                "TOPIC4_IMAGE_DIGEST": DIGEST,
                "TOPIC4_AUTH_TOKEN_FILE": str(root / "token"),
                "TOPIC4_PARTY_ENDPOINTS_FILE": str(root / "endpoints.json"),
                "TOPIC4_GATEWAY_STATE_DIR": str(root / "state"),
                "TOPIC4_PROVIDER_CONFIG": str(ROOT / "providers/mpspdz/provider.json"),
            })
            gateway = Gateway()
            result = {"released": True, "value": 918273645}
            result_digest = "sha256:" + hashlib.sha256(gateway_canonical(result)).hexdigest()
            result_reads = {"count": 0}

            def fake(base, method, path, body=None):
                if method == "POST" and path == "/jobs":
                    return {"externalJobId": external_id_for(body), "status": "QUEUED"}
                child_id = path.split("/")[2] if path.startswith("/jobs/") else ""
                if path.endswith("/result"):
                    result_reads["count"] += 1
                    if result_reads["count"] == 1:
                        raise GatewayError("transient result read")
                    return result
                if path.endswith("/evidence"):
                    return {"contractVersion": "topic4.privacy.evidence/v1"}
                return {
                    "externalJobId": child_id, "status": "SUCCEEDED",
                    "resultDigest": result_digest,
                }

            gateway.request = fake
            submitted = gateway.submit(request())
            finalizing = gateway.refresh(submitted)
            self.assertEqual(finalizing["status"], "FINALIZING")
            self.assertEqual(finalizing["finalizationFailureCode"],
                             "RESULT_FINALIZATION_RETRYABLE")
            succeeded = gateway.refresh(finalizing)
            self.assertEqual(succeeded["status"], "SUCCEEDED")
            job_dir = gateway.directory(succeeded["externalJobId"])
            self.assertFalse((job_dir / "result.json").exists())
            self.assertTrue((job_dir / "result-reference.json").is_file())
            persisted = "".join(
                path.read_text(encoding="utf-8") for path in job_dir.glob("*.json"))
            self.assertNotIn("918273645", persisted)
            self.assertEqual(gateway.result(succeeded)["partyResults"]["A"], result)

    def test_gateway_restart_cancels_every_deterministic_partial_dispatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            endpoints = gateway_endpoint_map(root)
            (root / "endpoints.json").write_text(json.dumps(endpoints), encoding="utf-8")
            (root / "token").write_text("gateway-secret\n", encoding="utf-8")
            state = root / "state"
            external_id = "d" * 32
            job = state / external_id
            job.mkdir(parents=True)
            value = request()
            sanitized = dict(value)
            sanitized["staging"] = [{
                key: item[key] for key in (
                    "partyId", "datasetId", "datasetVersion", "sourcePath",
                    "expectedSize", "expectedSha256", "expectedSchema",
                    "expectedSchemaDigest")
            } for item in value["staging"]]
            (job / "request.json").write_text(json.dumps(sanitized), encoding="utf-8")
            (job / "status.json").write_text(json.dumps({
                "externalJobId": external_id, "status": "DISPATCHING",
                "providerId": "MP_SPDZ", "jobId": value["jobId"],
                "attemptId": value["attemptId"], "templateId": value["templateId"],
                "participants": ["A", "B", "C"],
                "partyJobs": {"A": {"externalJobId": "a" * 32, "status": "QUEUED"}},
                "createdAt": "2026-09-19T00:00:00Z",
            }), encoding="utf-8")
            os.environ.update({
                "TOPIC4_PROVIDER_ID": "MP_SPDZ", "TOPIC4_ENGINE": "MP-SPDZ",
                "TOPIC4_ENGINE_VERSION": "0.4.3",
                "TOPIC4_SOURCE_REVISION": "26a605368e40fed3a7e9cee78c9a3f4390b85eb5",
                "TOPIC4_IMAGE_DIGEST": DIGEST,
                "TOPIC4_AUTH_TOKEN_FILE": str(root / "token"),
                "TOPIC4_PARTY_ENDPOINTS_FILE": str(root / "endpoints.json"),
                "TOPIC4_GATEWAY_STATE_DIR": str(state),
                "TOPIC4_PROVIDER_CONFIG": str(ROOT / "providers/mpspdz/provider.json"),
            })
            calls = []

            class RecoveryGateway(Gateway):
                def request(self, base, method, path, body=None):
                    calls.append((base, method, path))
                    if path.endswith("/cancel"):
                        return {"status": "CANCELLED"}
                    if path.endswith("/evidence"):
                        return {"contractVersion": "topic4.privacy.evidence/v1"}
                    raise GatewayError("unexpected recovery request")

            gateway = RecoveryGateway()
            recovered = gateway.load(external_id)
            self.assertEqual(recovered["status"], "FAILED")
            self.assertEqual(recovered["failureCode"],
                             "GATEWAY_RESTARTED_DURING_DISPATCH")
            self.assertEqual(len([item for item in calls if item[2].endswith("/cancel")]), 3)
            self.assertTrue((job / "evidence.json").is_file())

    def test_gateway_send_json_writes_one_framed_document(self):
        handler = object.__new__(GatewayHandler)
        handler.wfile = io.BytesIO()
        handler.send_response = lambda code: None
        handler.send_header = lambda name, value: None
        handler.end_headers = lambda: None
        value = {"status": "UP", "templates": ["one"]}
        GatewayHandler.send_json(handler, 200, value)
        self.assertEqual(handler.wfile.getvalue(), gateway_canonical(value) + b"\n")


class AdapterPolicyTest(unittest.TestCase):
    def run_adapter(self, adapter, value, env):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            request_path = root / "request.json"
            request_path.write_text(json.dumps(value), encoding="utf-8")
            (root / "job").mkdir()
            (root / "work").mkdir()
            complete_env = dict(os.environ)
            complete_env.update(env)
            complete_env["TOPIC4_JOB_WORK_DIR"] = str(root / "work")
            complete_env["MP_SPDZ_PLAYER_DATA"] = str(root / "work/Player-Data")
            return subprocess.run(
                [sys.executable, str(adapter), str(request_path), str(root / "job")],
                env=complete_env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                check=False, text=True,
            )

    def test_mpspdz_public_policy_is_accepted_before_missing_tls(self):
        value = request()
        value.pop("staging")
        completed = self.run_adapter(
            ROOT / "providers/mpspdz/run-engine.py", value,
            {"TOPIC4_PARTY_INDEX": "0", "TOPIC4_PARTY_ID": "A",
             "TOPIC4_JOB_INPUT": str(ROOT / "fixtures/secure-sum/a.csv")},
        )
        self.assertEqual(completed.returncode, 69, completed.stderr)
        value["enginePolicy"] = {"command": "no"}
        rejected = self.run_adapter(
            ROOT / "providers/mpspdz/run-engine.py", value,
            {"TOPIC4_PARTY_INDEX": "0", "TOPIC4_PARTY_ID": "A",
             "TOPIC4_JOB_INPUT": str(ROOT / "fixtures/secure-sum/a.csv")},
        )
        self.assertEqual(rejected.returncode, 64)

    def test_psi_public_policy_is_accepted_before_missing_tls(self):
        value = request()
        value.pop("staging")
        value.update({
            "templateId": "psi-2p-v1",
            "participants": [
                {"partyId": "A", "role": "RECEIVER", "fields": ["id"]},
                {"partyId": "B", "role": "PROVIDER", "fields": ["id"]},
            ],
            "resultRecipients": ["A"],
            "enginePolicy": {"keyColumns": ["id"], "outputMode": "RECEIVER_ONLY"},
        })
        with tempfile.TemporaryDirectory() as directory:
            endpoint_file = Path(directory) / "endpoints.json"
            endpoint_file.write_text(json.dumps({
                "A": "psi-a.internal:5300", "B": "psi-b.internal:5300"}), encoding="utf-8")
            completed = self.run_adapter(
                ROOT / "providers/psi/run-engine.py", value,
                {"TOPIC4_PARTY_ID": "A", "TOPIC4_ENDPOINT_MAP": str(endpoint_file),
                 "TOPIC4_JOB_INPUT": str(ROOT / "fixtures/psi/a.csv"),
                 "TOPIC4_PSI_TLS_DIR": str(Path(directory) / "absent")},
            )
        self.assertEqual(completed.returncode, 69, completed.stderr)

    def test_mpspdz_converter_honors_frozen_field_order_and_ignores_noise(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "input.csv"
            output = root / "Input-P0-0"
            source.write_text("noise,v2,party_id,v1\n999,22,A,11\n", encoding="utf-8")
            completed = subprocess.run([
                sys.executable, str(ROOT / "providers/mpspdz/csv_to_input.py"),
                "private-stats-3p-v1", str(source), str(output), '["v1","v2"]',
            ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
            self.assertEqual(completed.returncode, 0, completed.stderr)
            self.assertEqual(output.read_text().split(), ["11", "22", "0", "0", "0", "0", "0", "0"])

    def test_mpspdz_converter_rejects_missing_or_duplicate_bindings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "input.csv"
            source.write_text("value,noise\n40,999\n", encoding="utf-8")
            for fields in ('["missing"]', '["value","value"]'):
                completed = subprocess.run([
                    sys.executable, str(ROOT / "providers/mpspdz/csv_to_input.py"),
                    "secure-sum-3p-v1", str(source), str(root / "out"), fields,
                ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
                self.assertEqual(completed.returncode, 64)

    def test_apsi_preparation_uses_exact_headers_and_rejects_bad_keys(self):
        adapter = ROOT / "providers/psi/run-engine.py"
        spec = importlib.util.spec_from_file_location("topic4_psi_adapter", adapter)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            server = root / "server.csv"
            client = root / "client.csv"
            server.write_text("lookup,payload,noise\nalpha,A,ignored\nbeta,B,ignored\n", encoding="utf-8")
            client.write_text("lookup,noise\nalpha,ignored\nmissing,ignored\n", encoding="utf-8")
            server_out, client_out = root / "server.out", root / "client.out"
            module.prepare_apsi_input(server, server_out, True, "lookup", ["payload"])
            module.prepare_apsi_input(client, client_out, False, "lookup", ["payload"])
            self.assertEqual(server_out.read_text().splitlines()[0], "key,value")
            self.assertEqual(client_out.read_text().splitlines()[0], "key")
            duplicate = root / "duplicate.csv"
            duplicate.write_text("lookup,payload\nalpha,A\nalpha,B\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                module.prepare_apsi_input(duplicate, root / "bad", True, "lookup", ["payload"])

    def test_sfl_adapter_rejects_role_field_and_policy_drift(self):
        adapter = ROOT / "providers/sfl/hfl_fedavg_logreg.py"
        spec = importlib.util.spec_from_file_location("topic4_sfl_adapter", adapter)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        old_party = os.environ.get("TOPIC4_PARTY_ID")
        os.environ["TOPIC4_PARTY_ID"] = "A"
        try:
            value = {
                "templateId": "hfl-fedavg-logreg-3p-v1",
                "protocolVersion": "sfl-c383e40f/fedavg-logreg",
                "securityProfile": "SEMI_HONEST_FL",
                "participants": [
                    {"partyId": party, "role": "TRAINER", "fields": ["x1", "x2", "label"]}
                    for party in ("A", "B", "C")
                ],
                "resultRecipients": ["A"],
                "enginePolicy": {
                    "labelColumn": "label", "featureColumns": ["x1", "x2"],
                    "epochs": 1, "learningRate": 0.05, "seed": 20260919,
                },
            }
            self.assertEqual(module.validate_request(value), ("A", 1))
            for mutator in (
                lambda item: item["participants"][0].update(role="ACTIVE"),
                lambda item: item["participants"][1].update(fields=["x2", "x1", "label"]),
                lambda item: item["enginePolicy"].update(seed=1),
            ):
                invalid = json.loads(json.dumps(value))
                mutator(invalid)
                with self.assertRaises(ValueError):
                    module.validate_request(invalid)
        finally:
            if old_party is None:
                os.environ.pop("TOPIC4_PARTY_ID", None)
            else:
                os.environ["TOPIC4_PARTY_ID"] = old_party

    def test_kuscia_context_derives_fixed_psi_fed_and_spu_endpoints(self):
        entry = ROOT / "runner/kuscia-runtime-entry.py"
        spec = importlib.util.spec_from_file_location("topic4_kuscia_entry", entry)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        parties = []
        for domain in ("domain-a", "domain-b", "domain-c"):
            parties.append({"name": domain, "services": [
                {"portName": "runner", "endpoints": ["topic4-sfl-runner.%s.svc:18080" % domain]},
                {"portName": "psi", "endpoints": ["topic4-sfl-psi.%s.svc" % domain]},
                {"portName": "fed", "endpoints": ["topic4-sfl-fed.%s.svc" % domain]},
                {"portName": "spu", "endpoints": ["topic4-sfl-spu.%s.svc" % domain]},
            ]})
        cluster = {"parties": parties, "selfPartyIdx": 0, "selfEndpointIdx": 0}
        ports = {"ports": [
            {"name": "runner", "port": 18080, "scope": "Domain", "protocol": "HTTP"},
            {"name": "psi", "port": 15300, "scope": "Cluster", "protocol": "GRPC"},
            {"name": "fed", "port": 16101, "scope": "Cluster", "protocol": "GRPC"},
            {"name": "spu", "port": 16201, "scope": "Cluster", "protocol": "GRPC"},
        ]}
        deployment_input = {
            "partyMap": {"domain-a": "A", "domain-b": "B", "domain-c": "C"},
            "agentMap": {
                domain: {"baseUrl": "http://agent-%s.internal:8080" % party.lower(),
                         "nodeName": "node-" + party.lower()}
                for domain, party in (("domain-a", "A"), ("domain-b", "B"), ("domain-c", "C"))
            },
        }
        outer = {
            "servingId": "topic4-sfl", "domainId": "domain-a",
            "inputConfig": json.dumps(deployment_input),
            "clusterDefine": json.dumps(cluster), "allocatedPorts": json.dumps(ports),
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "context.json"
            path.write_text(json.dumps(outer), encoding="utf-8")
            keys = ("KUSCIA_DOMAIN_ID", "SERVING_ID", "INPUT_CONFIG", "CLUSTER_DEFINE", "ALLOCATED_PORTS")
            saved = {key: os.environ.get(key) for key in keys}
            os.environ.update({
                "KUSCIA_DOMAIN_ID": "domain-a", "SERVING_ID": "topic4-sfl",
                "INPUT_CONFIG": json.dumps(deployment_input),
                "CLUSTER_DEFINE": json.dumps(cluster), "ALLOCATED_PORTS": json.dumps(ports),
            })
            try:
                context = module.validate_context(path)
            finally:
                for key, value in saved.items():
                    if value is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = value
        self.assertEqual(context["engineEndpoints"]["B"], "http://topic4-sfl-psi.domain-b.svc:80")
        self.assertEqual(context["secretflowEndpoints"]["fed"]["B"], "topic4-sfl-fed.domain-b.svc:80")
        self.assertEqual(context["secretflowEndpoints"]["spu"]["C"], "http://topic4-sfl-spu.domain-c.svc:80")
        self.assertEqual(context["sflClusterConfig"]["self_party"], "A")
        psi_path = ROOT / "providers/psi/run-engine.py"
        psi_spec = importlib.util.spec_from_file_location("topic4_psi_context_adapter", psi_path)
        psi_module = importlib.util.module_from_spec(psi_spec)
        psi_spec.loader.exec_module(psi_module)
        links = psi_module.parties({"participants": [
            {"partyId": party} for party in ("A", "B", "C")
        ]}, context)
        self.assertEqual(links[1]["host"], "http://topic4-sfl-psi.domain-b.svc:80")


class ArtifactTest(unittest.TestCase):
    def test_json_catalogs_and_schema_parse(self):
        paths = [ROOT / "dependencies.lock.json", ROOT / "contract/job-request.schema.json"]
        paths.extend(ROOT.glob("providers/*/provider*.json"))
        for path in paths:
            with self.subTest(path=path):
                json.loads(path.read_text(encoding="utf-8"))

    def test_sfl_image_uses_only_the_registered_tensorflow_runtime(self):
        requirements = {
            line.strip()
            for line in (ROOT / "providers/sfl/runtime-requirements.txt").read_text(
                encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        }
        self.assertEqual(requirements, {
            "secretflow-lite==1.13.0b0",
            "secretflow-rayfed==0.2.1a2",
            "ray[tune]==2.52.0",
            "tensorflow==2.12.0",
            "dp-accounting==0.4.4",
        })
        self.assertFalse(any(
            requirement.startswith(("torch", "torchvision", "torchaudio", "xgboost"))
            for requirement in requirements))
        dockerfile = (ROOT / "providers/sfl/Dockerfile").read_text(encoding="utf-8")
        self.assertIn("--requirement /tmp/sfl-runtime-requirements.txt", dockerfile)
        self.assertIn("--no-cache-dir --no-deps /opt/sfl", dockerfile)
        lock = json.loads((ROOT / "dependencies.lock.json").read_text(encoding="utf-8"))
        sfl = next(item for item in lock["dependencies"] if item["name"] == "SFL")
        self.assertIn(sfl["sourceArchive"], dockerfile)
        self.assertIn(sfl["sourceArchiveSha256"], dockerfile)
        self.assertIn("sha256sum --check --strict", dockerfile)
        self.assertNotIn("git fetch", dockerfile)
        self.assertNotIn("git clone", dockerfile)

    def test_psi_image_uses_verified_cached_source_archive(self):
        dockerfile = (ROOT / "providers/secretflow/Dockerfile").read_text(encoding="utf-8")
        lock = json.loads((ROOT / "dependencies.lock.json").read_text(encoding="utf-8"))
        psi = next(item for item in lock["dependencies"] if item["name"] == "SecretFlow PSI")
        registry = next(item for item in lock["dependencies"]
                        if item["name"] == "SecretFlow Bazel Registry")
        self.assertIn(psi["sourceArchive"], dockerfile)
        self.assertIn(psi["sourceArchiveSha256"], dockerfile)
        self.assertIn(registry["sourceArchive"], dockerfile)
        self.assertIn(registry["sourceArchiveSha256"], dockerfile)
        self.assertIn("common --registry=file:///opt/bazel-registry", dockerfile)
        self.assertIn("sha256sum --check --strict", dockerfile)
        self.assertNotIn("git clone", dockerfile)

    def test_generated_mpspdz_registry_is_current(self):
        before = {
            path.name: path.read_bytes()
            for path in (ROOT / "providers/mpspdz/programs").glob("*_r*.mpc")
        }
        subprocess.run([sys.executable, str(ROOT / "providers/mpspdz/generate_programs.py")],
                       check=True)
        after = {
            path.name: path.read_bytes()
            for path in (ROOT / "providers/mpspdz/programs").glob("*_r*.mpc")
        }
        self.assertEqual(before, after)
        self.assertEqual(len(after), 21)

    def test_kuscia_provider_manifests_use_cri_config_id_separately(self):
        manifest_digest = "sha256:" + "a" * 64
        config_id = "sha256:" + "b" * 64
        with tempfile.TemporaryDirectory() as directory:
            for provider in ("apsi", "secretflow", "sfl"):
                output = Path(directory) / (provider + ".json")
                subprocess.run([
                    sys.executable, str(ROOT / "k8s/generate_kuscia_provider.py"),
                    "--provider", provider,
                    "--image", "registry.local/topic4-privacy:v-" + "1" * 40,
                    "--image-digest", manifest_digest, "--image-id", config_id,
                    "--node-a", "node-a", "--node-b", "node-b", "--node-c", "node-c",
                    "--agent-url-a", "http://agent-a:8080",
                    "--agent-url-b", "http://agent-b:8080",
                    "--agent-url-c", "http://agent-c:8080",
                    "--agent-node-a", "node-a", "--agent-node-b", "node-b",
                    "--agent-node-c", "node-c", "--output", str(output),
                ], check=True)
                value = json.loads(output.read_text(encoding="utf-8"))
                app = value["items"][0]
                self.assertEqual(app["spec"]["image"]["id"], config_id)
                env = app["spec"]["deployTemplates"][0]["spec"]["containers"][0]["env"]
                self.assertIn({"name": "TOPIC4_IMAGE_DIGEST", "value": manifest_digest}, env)
                self.assertIn({"name": "TOPIC4_WORK_DIR", "value": "/var/run/topic4-work"}, env)
                pod_spec = app["spec"]["deployTemplates"][0]["spec"]
                self.assertNotIn("volumes", pod_spec)
                self.assertNotIn("volumeMounts", pod_spec["containers"][0])

    def test_provider_deploy_patches_logical_domains_through_master(self):
        script = (ROOT / "k8s/deploy-kuscia-providers.sh").read_text(encoding="utf-8")
        function = script.split("patch_party_storage() {", 1)[1].split(
            "ensure_outer_runner_service() {", 1)[0]
        self.assertIn("kubectl -n kuscia-master exec deploy/kuscia-master --", function)
        self.assertNotIn("exec deploy/kuscia-lite", function)
        self.assertIn('patch_party_storage "$provider" domain-a', function)
        self.assertIn("/state/jobs /state/models /state/smoke", function)
        self.assertIn("chmod 0700 /state/jobs /state/models /state/smoke", function)
        self.assertNotIn("chmod 0700 /state\"", function)
        apply_kd = script.index('kubectl apply -f -')
        patch_storage = script.index('patch_party_storage "$provider" domain-a')
        available = script.index('jsonpath={.status.phase}=Available')
        self.assertLess(apply_kd, patch_storage)
        self.assertLess(patch_storage, available)


if __name__ == "__main__":
    unittest.main()
