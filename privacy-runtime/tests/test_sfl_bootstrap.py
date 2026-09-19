import hashlib
import json
import os
import stat
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from runner.topic4_privacy_gateway import Gateway, GatewayError
from runner.topic4_privacy_runner import ContractError, ProviderConfig, Runtime, canonical_bytes
from runner.topic4_sfl_smoke import (
    approve_record, approval_locked, load_and_validate_record, require_approved_record,
)

DIGEST = "sha256:" + "a" * 64
CONTEXT_DIGEST = "sha256:" + "b" * 64
REVISION = "c383e40f665063d7f7d87e437a73e015f87c435c"


def schema_digest(schema):
    return "sha256:" + hashlib.sha256(canonical_bytes(schema)).hexdigest()


def sfl_request(token="sfl-one-time-A"):
    schema = [
        {"name": "x1", "type": "INTEGER", "nullable": False},
        {"name": "x2", "type": "INTEGER", "nullable": False},
        {"name": "label", "type": "INTEGER", "nullable": False},
    ]
    participants = [
        {"partyId": party, "role": "TRAINER", "fields": ["x1", "x2", "label"]}
        for party in ("A", "B", "C")
    ]
    staging = [{
        "partyId": party,
        "nodeName": "node-" + party.lower(),
        "agentBaseUrl": "http://agent-%s.internal:8080" % party.lower(),
        "token": token + "-" + party,
        "datasetId": "dataset-" + party,
        "datasetVersion": "v1",
        "sourcePath": "/dataset/%s.csv" % party.lower(),
        "expectedSize": 32,
        "expectedSha256": DIGEST,
        "expectedSchema": schema,
        "expectedSchemaDigest": schema_digest(schema),
    } for party in ("A", "B", "C")]
    return {
        "jobId": "sfl-distributed-smoke",
        "attemptId": "attempt-1",
        "templateId": "hfl-fedavg-logreg-3p-v1",
        "protocolVersion": "sfl-c383e40f/fedavg-logreg",
        "imageDigest": DIGEST,
        "specDigest": DIGEST,
        "securityProfile": "SEMI_HONEST_FL",
        "participants": participants,
        "resultRecipients": ["A"],
        "timeoutSeconds": 60,
        "enginePolicy": {
            "labelColumn": "label", "featureColumns": ["x1", "x2"],
            "epochs": 1, "learningRate": 0.05, "seed": 20260919,
        },
        "staging": staging,
    }


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


class SflBootstrapRunnerTest(unittest.TestCase):
    def setUp(self):
        self.old = dict(os.environ)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.old)

    def test_real_job_marker_record_and_separate_approval_close_health_loop(self):
        payload = b"x1,x2,label\n0,0,0\n1,1,1\n"

        class Agent(BaseHTTPRequestHandler):
            calls = 0

            def log_message(self, *args):
                return

            def do_GET(self):
                type(self).calls += 1
                self.send_response(200)
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Agent)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                adapter = root / "adapter.py"
                adapter.write_text(
                    "#!/usr/bin/env python3\n"
                    "import json, pathlib, sys\n"
                    "request=json.load(open(sys.argv[1], encoding='utf-8'))\n"
                    "job=pathlib.Path(sys.argv[2])\n"
                    "json.dump({'released': True, 'partyId': 'A'}, "
                    "open(job/'engine-result.json','w',encoding='utf-8'), sort_keys=True)\n"
                    "json.dump({'sourceRevision': '%s', "
                    "'transport': 'KUSCIA_CLUSTER_MTLS_ENVOY', "
                    "'servingId': 'serving-1', 'kusciaContextDigest': '%s'}, "
                    "open(job/'engine-evidence.json','w',encoding='utf-8'), sort_keys=True)\n"
                    "sys.exit(0)\n" % (REVISION, CONTEXT_DIGEST),
                    encoding="utf-8",
                )
                adapter.chmod(adapter.stat().st_mode | stat.S_IXUSR)
                health = root / "health.py"
                health.write_text(
                    "import hashlib,json,os,pathlib,sys\n"
                    "record=json.load(open(os.environ['TOPIC4_SFL_DISTRIBUTED_SMOKE_FILE']))\n"
                    "actual='sha256:'+hashlib.sha256(json.dumps(record,sort_keys=True,"
                    "separators=(',',':')).encode()).hexdigest()\n"
                    "approval=pathlib.Path(os.environ["
                    "'TOPIC4_SFL_DISTRIBUTED_SMOKE_APPROVAL_FILE']).read_text()\n"
                    "sys.exit(0 if approval in (actual,actual+'\\n') else 1)\n",
                    encoding="utf-8",
                )
                config = json.loads(
                    (ROOT / "providers/sfl/provider.json").read_text(encoding="utf-8"))
                config.update({
                    "adapterExecutable": str(adapter), "requiredFiles": [],
                    "requiredExecutables": [], "requiredPythonImports": [],
                    "requiredEnvironment": {},
                    "bootstrapHealthCommand": [sys.executable, "-c", "raise SystemExit(0)"],
                    "healthCommand": [sys.executable, str(health)],
                })
                config_path = root / "provider.json"
                config_path.write_text(json.dumps(config), encoding="utf-8")
                smoke = root / "persistent/smoke/distributed.json"
                approval = root / "persistent/smoke/distributed.sha256"
                os.environ.update({
                    "TOPIC4_IMAGE_DIGEST": DIGEST,
                    "TOPIC4_SFL_BOOTSTRAP_MODE": "1",
                    "TOPIC4_SFL_DISTRIBUTED_SMOKE_FILE": str(smoke),
                    "TOPIC4_SFL_DISTRIBUTED_SMOKE_APPROVAL_FILE": str(approval),
                    "TOPIC4_HEALTH_FAILURE_CACHE_SECONDS": "0",
                    "TOPIC4_HEALTH_CACHE_SECONDS": "0",
                    "TOPIC4_PARTY_ID": "A",
                    "TOPIC4_AGENT_BASE_URL": "http://127.0.0.1:%d" % server.server_port,
                    "TOPIC4_AGENT_ALLOWED_HOSTS": "127.0.0.1",
                    "TOPIC4_INPUT_DIR": str(root / "input"),
                    "TOPIC4_WORK_DIR": str(root / "work"),
                })
                runtime = Runtime(ProviderConfig(config_path), root / "state")
                local = sfl_request()
                local["stagedInput"] = local.pop("staging")[0]
                local["stagedInput"].update({
                    "expectedSize": len(payload),
                    "expectedSha256": "sha256:" + hashlib.sha256(payload).hexdigest(),
                })

                unavailable, accepted = runtime.submit(local)
                self.assertFalse(accepted)
                self.assertEqual(unavailable["status"], "UNAVAILABLE")
                self.assertEqual(Agent.calls, 0, "normal submit must not consume staging")
                self.assertEqual(runtime.bootstrap_status()["baseAvailability"], "AVAILABLE")

                injected = dict(local)
                injected["_topic4BootstrapSmoke"] = True
                with self.assertRaises(ContractError):
                    runtime.submit(injected, bootstrap=True)
                self.assertEqual(Agent.calls, 0)

                submitted, accepted = runtime.submit(local, bootstrap=True)
                self.assertTrue(accepted)
                external_id = submitted["externalJobId"]
                deadline = time.monotonic() + 5
                while time.monotonic() < deadline:
                    status = runtime.store.get(external_id)
                    if status and status["status"] in {"SUCCEEDED", "FAILED", "ABORTED"}:
                        break
                    time.sleep(0.02)
                self.assertEqual(runtime.store.get(external_id)["status"], "SUCCEEDED")
                self.assertTrue(smoke.is_file())
                persisted_request = json.loads(
                    (root / "state" / external_id / "request.json").read_text())
                self.assertIs(persisted_request["_topic4BootstrapSmoke"], True)
                duplicate = dict(local)
                duplicate["attemptId"] = "attempt-2"
                duplicate["stagedInput"] = dict(local["stagedInput"], token="fresh-token")
                with self.assertRaises(ContractError):
                    runtime.submit(duplicate, bootstrap=True)

                context = {
                    "partyId": "A", "servingId": "serving-1",
                    "contextDigest": CONTEXT_DIGEST,
                }
                record, digest = load_and_validate_record(context)
                self.assertEqual(record["resultDigest"], runtime.store.get(external_id)["resultDigest"])
                with self.assertRaises(OSError):
                    require_approved_record(context)
                self.assertFalse(approval_locked())
                self.assertEqual(approve_record(context), digest)
                self.assertTrue(approval_locked())
                self.assertEqual(require_approved_record(context)[1], digest)
                self.assertTrue(runtime.config.dependency_status()[0],
                                "health must become UP without restarting the runner")
                duplicate, duplicate_accepted = runtime.submit(local, bootstrap=True)
                self.assertTrue(duplicate_accepted)
                self.assertEqual(duplicate["externalJobId"], external_id)
                self.assertEqual(Agent.calls, 1, "approved bootstrap must reject before staging")

                # The public request cannot manufacture or replace approval. A
                # changed record is immediately detected against the digest file.
                changed = dict(record)
                changed["resultDigest"] = "sha256:" + "c" * 64
                smoke.write_text(json.dumps(changed), encoding="utf-8")
                with self.assertRaises(ValueError):
                    require_approved_record(context)
                self.assertFalse(runtime.config.dependency_status()[0])
        finally:
            server.shutdown()
            server.server_close()


class SflBootstrapGatewayTest(unittest.TestCase):
    def setUp(self):
        self.old = dict(os.environ)

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.old)

    def test_gateway_uses_only_authenticated_sfl_bootstrap_path_and_locks_after_approval(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            endpoints = gateway_endpoint_map(root)
            (root / "endpoints.json").write_text(json.dumps(endpoints), encoding="utf-8")
            (root / "token").write_text("gateway-secret\n", encoding="utf-8")
            os.environ.update({
                "TOPIC4_PROVIDER_ID": "KUSCIA_SFL", "TOPIC4_ENGINE": "SFL",
                "TOPIC4_ENGINE_VERSION": "commit:" + REVISION,
                "TOPIC4_SOURCE_REVISION": REVISION, "TOPIC4_IMAGE_DIGEST": DIGEST,
                "TOPIC4_AUTH_TOKEN_FILE": str(root / "token"),
                "TOPIC4_PARTY_ENDPOINTS_FILE": str(root / "endpoints.json"),
                "TOPIC4_GATEWAY_STATE_DIR": str(root / "state"),
                "TOPIC4_PROVIDER_CONFIG": str(ROOT / "providers/sfl/provider.json"),
                "TOPIC4_SFL_BOOTSTRAP_MODE": "1",
            })
            gateway = Gateway()
            calls = []
            approved = {"value": False}

            def fake(base, method, path, body=None):
                calls.append((base, method, path, body))
                party = base.split("runner-", 1)[1][0].upper()
                if path == "/bootstrap/status":
                    return {
                        "bootstrapEnabled": True, "approved": approved["value"],
                        "baseAvailability": "AVAILABLE",
                    }
                if method == "POST" and path in ("/bootstrap/jobs", "/jobs"):
                    identity = "%s\0%s\0KUSCIA_SFL" % (
                        body["jobId"], body["attemptId"])
                    child_id = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]
                    return {"externalJobId": child_id, "status": "QUEUED"}
                raise AssertionError("unexpected fake request %s %s" % (method, path))

            gateway.request = fake
            value = sfl_request()
            submitted = gateway.submit(value, bootstrap=True)
            self.assertEqual(submitted["status"], "QUEUED")
            self.assertTrue(submitted["bootstrapSmoke"])
            self.assertEqual(len([item for item in calls if item[2] == "/bootstrap/status"]), 3)
            bootstrap_posts = [item for item in calls if item[2] == "/bootstrap/jobs"]
            self.assertEqual(len(bootstrap_posts), 3)
            self.assertTrue(all("_topic4BootstrapSmoke" not in item[3]
                                for item in bootstrap_posts))

            retry_before_approval = sfl_request("retry-before-approval")
            retry_before_approval["attemptId"] = "attempt-2"
            with self.assertRaises(GatewayError):
                gateway.submit(retry_before_approval, bootstrap=True)

            injected = sfl_request("injected")
            injected["_topic4BootstrapSmoke"] = True
            with self.assertRaises(GatewayError):
                gateway.validate(injected)

            approved["value"] = True
            retry = sfl_request("retry")
            retry["attemptId"] = "attempt-2"
            count = len(calls)
            with self.assertRaises(GatewayError):
                gateway.submit(retry, bootstrap=True)
            self.assertFalse(any(item[2] == "/bootstrap/jobs" for item in calls[count:]))

            # A non-SFL gateway cannot expose the bootstrap control path even if
            # an environment variable was copied to it accidentally.
            gateway.provider_id = "MP_SPDZ"
            with self.assertRaises(GatewayError):
                gateway.submit(retry, bootstrap=True)


if __name__ == "__main__":
    unittest.main()
