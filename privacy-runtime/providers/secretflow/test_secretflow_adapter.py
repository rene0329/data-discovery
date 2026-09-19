import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock


DIRECTORY = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, DIRECTORY / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


adapter = load("topic4_secretflow_adapter_test", "run-engine.py")
dispatcher = load("topic4_secretflow_dispatch_test", "unified-dispatch.py")


def kuscia_context(local_party="A"):
    fed = {"A": "domain-a-fed.example:80", "B": "domain-b-fed.example:80", "C": "domain-c-fed.example:80"}
    spu = {
        "A": "http://domain-a-spu.example:80",
        "B": "http://domain-b-spu.example:80",
        "C": "http://domain-c-spu.example:80",
    }
    fed[local_party] = "0.0.0.0:61001"
    spu[local_party] = "0.0.0.0:62001"
    psi = {
        "A": "http://domain-a-psi.example:80",
        "B": "http://domain-b-psi.example:80",
        "C": "http://domain-c-psi.example:80",
    }
    psi[local_party] = "0.0.0.0:5300"
    value = {
        "contractVersion": "topic4.kuscia.runtime-context/v1",
        "servingId": "secretflow-parties",
        "domainId": "domain-" + local_party.lower(),
        "partyId": local_party,
        "transport": "KUSCIA_CLUSTER_MTLS_ENVOY",
        "engineEndpoints": psi,
        "secretflowEndpoints": {"fed": fed, "spu": spu},
    }
    value["contextDigest"] = "sha256:" + adapter.hashlib.sha256(adapter.canonical(value)).hexdigest()
    return value


def base_request(template):
    value = {
        "jobId": "job-1",
        "attemptId": "attempt-1",
        "templateId": template,
        "securityProfile": "SEMI_HONEST_HE",
        "protocolVersion": "secretflow-1.11.0b1/heu-paillier",
        "resultRecipients": ["A"],
        "timeoutSeconds": 1800,
    }
    if template == "he-paillier-2p-v1":
        value.update(
            {
                "participants": [
                    {"partyId": "A", "role": "KEY_HOLDER", "fields": ["value"]},
                    {"partyId": "B", "role": "DATA_HOLDER", "fields": ["value"]},
                ],
                "enginePolicy": {"operation": "ADD", "scale": 1000},
            }
        )
    else:
        value.update(
            {
                "protocolVersion": "secretflow-1.11.0b1/secureboost-heu",
                "timeoutSeconds": 3600,
                "participants": [
                    {
                        "partyId": "A",
                        "role": "ACTIVE",
                        "fields": ["id", "x1", "label"],
                    },
                    {"partyId": "B", "role": "PASSIVE", "fields": ["id", "x2"]},
                ],
                "enginePolicy": {
                    "labelColumn": "label",
                    "featureColumns": ["x1", "x2"],
                    "epochs": 1,
                    "learningRate": 0.1,
                    "seed": 20260919,
                },
            }
        )
    return value


class AdapterContractTest(unittest.TestCase):
    def test_he_contract_fixes_roles_protocol_operations_and_fields(self):
        request = adapter.validate_request(base_request("he-paillier-2p-v1"), "A")
        self.assertEqual(request["normalizedPolicy"], {"operation": "ADD", "scale": 1000})

        for field, replacement in (
            ("securityProfile", "PLAINTEXT"),
            ("protocolVersion", "latest"),
        ):
            invalid = base_request("he-paillier-2p-v1")
            invalid[field] = replacement
            with self.assertRaises(adapter.ContractError):
                adapter.validate_request(invalid, "A")

        invalid = base_request("he-paillier-2p-v1")
        invalid["enginePolicy"]["operation"] = "FHE_BOOTSTRAP"
        with self.assertRaises(adapter.ContractError):
            adapter.validate_request(invalid, "A")
        invalid = base_request("he-paillier-2p-v1")
        invalid["participants"][1]["fields"] = ["other"]
        with self.assertRaises(adapter.ContractError):
            adapter.validate_request(invalid, "B")

    def test_vfl_contract_fixes_label_owner_and_requires_both_feature_owners(self):
        request = adapter.validate_request(base_request("vfl-secureboost-2p-v1"), "B")
        self.assertEqual(request["normalizedPolicy"]["featureOwners"], {"x1": "A", "x2": "B"})

        invalid = base_request("vfl-secureboost-2p-v1")
        invalid["participants"][0]["fields"].remove("label")
        invalid["participants"][1]["fields"].append("label")
        with self.assertRaises(adapter.ContractError):
            adapter.validate_request(invalid, "A")

        invalid = base_request("vfl-secureboost-2p-v1")
        invalid["enginePolicy"]["featureColumns"] = ["x1"]
        with self.assertRaises(adapter.ContractError):
            adapter.validate_request(invalid, "A")

    def test_he_csv_uses_exact_fixed_point_encoding(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.csv"
            source.write_text("value\n1.25\n-2.5\n", encoding="utf-8")
            self.assertEqual(adapter.read_he_values(source, 100), [125, -250])
            with self.assertRaises(adapter.ContractError):
                adapter.read_he_values(source, 3)

    def test_vfl_csv_rejects_duplicate_ids_non_numeric_features_and_bad_labels(self):
        request = adapter.validate_request(base_request("vfl-secureboost-2p-v1"), "A")
        policy = request["normalizedPolicy"]
        participant = request["participantsByParty"]["A"]
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "a.csv"
            source.write_text("id,x1,label\nu1,0,0\nu2,1,1\n", encoding="utf-8")
            self.assertEqual(adapter.validate_vfl_csv(source, "A", participant, policy), 2)
            source.write_text("id,x1,label\nu1,0,0\nu1,1,1\n", encoding="utf-8")
            with self.assertRaises(adapter.ContractError):
                adapter.validate_vfl_csv(source, "A", participant, policy)
            source.write_text("id,x1,label\nu1,nope,0\nu2,1,1\n", encoding="utf-8")
            with self.assertRaises(adapter.ContractError):
                adapter.validate_vfl_csv(source, "A", participant, policy)

    def test_release_envelopes_contain_results_or_references_without_model_data(self):
        try:
            import numpy as np
        except ImportError:
            self.skipTest("numpy is not installed in the host test environment")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            he_release = root / "he.json"
            adapter._save_he_release(
                np.asarray([3000, 4500]),
                str(he_release),
                1000,
                {"operation": "ADD", "scale": 1000},
            )
            self.assertEqual(json.loads(he_release.read_text())["value"], [3, 4.5])

            vfl_release = root / "vfl.json"
            predictions = root / "predictions.csv"
            adapter._save_vfl_release(
                np.asarray([0.25, 0.75]),
                {"accuracy": 1.0},
                [
                    {"partyId": "A", "reference": "party-model://A/job/attempt/secureboost"},
                    {"partyId": "B", "reference": "party-model://B/job/attempt/secureboost"},
                ],
                str(vfl_release),
                str(predictions),
                "party-artifact://A/job/attempt/predictions.csv",
                {"algorithm": "SecureBoost"},
            )
            result = json.loads(vfl_release.read_text())
            self.assertNotIn("predictions", result)
            self.assertTrue(result["predictionReference"].startswith("party-artifact://A/"))
            self.assertEqual([item["partyId"] for item in result["modelReferences"]], ["A", "B"])

    def test_missing_engine_configuration_returns_unavailable_not_plaintext(self):
        request = base_request("he-paillier-2p-v1")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            request_path = root / "request.json"
            input_path = root / "input.csv"
            request_path.write_text(json.dumps(request), encoding="utf-8")
            input_path.write_text("value\n40\n", encoding="utf-8")
            with mock.patch.dict(
                os.environ,
                {
                    "TOPIC4_PARTY_ID": "A",
                    "TOPIC4_PARTY_INDEX": "0",
                    "TOPIC4_JOB_INPUT": str(input_path),
                    "TOPIC4_KUSCIA_CONTEXT_FILE": str(root / "missing.json"),
                    "TOPIC4_KUSCIA_TRANSPORT": "KUSCIA_CLUSTER_MTLS_ENVOY",
                    "TOPIC4_KUSCIA_DEPLOYMENT_ID": "secretflow-parties",
                },
                clear=False,
            ):
                self.assertEqual(adapter.main(["run-engine.py", str(request_path), str(root)]), 69)
            self.assertFalse((root / "engine-result.json").exists())

    def test_kuscia_context_is_digest_bound_and_has_separate_fed_spu_routes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "context.json"
            context = kuscia_context("A")
            path.write_text(json.dumps(context), encoding="utf-8")
            with mock.patch.dict(
                os.environ,
                {
                    "TOPIC4_KUSCIA_TRANSPORT": "KUSCIA_CLUSTER_MTLS_ENVOY",
                    "TOPIC4_KUSCIA_DEPLOYMENT_ID": "secretflow-parties",
                },
                clear=False,
            ):
                loaded = adapter.load_kuscia_context(path, "A")
                self.assertEqual(loaded["fed"]["B"], "domain-b-fed.example:80")
                self.assertEqual(loaded["spu"]["B"], "http://domain-b-spu.example:80")
                context["secretflowEndpoints"]["fed"]["B"] = "attacker.example:80"
                path.write_text(json.dumps(context), encoding="utf-8")
                with self.assertRaises(adapter.EngineUnavailable):
                    adapter.load_kuscia_context(path, "A")

    def test_unified_dispatch_has_only_fixed_image_owned_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            request_path = Path(directory) / "request.json"
            request_path.write_text(json.dumps({"templateId": "shell"}), encoding="utf-8")
            with mock.patch.object(dispatcher.sys, "argv", ["dispatch", str(request_path), directory]):
                self.assertEqual(dispatcher.main(), 64)

    def test_unified_dispatch_routes_apsi_to_the_pinned_psi_adapter(self):
        with tempfile.TemporaryDirectory() as directory:
            request_path = Path(directory) / "request.json"
            request_path.write_text(
                json.dumps({"templateId": "pir-keyword-2p-v1"}), encoding="utf-8"
            )
            with (
                mock.patch.object(dispatcher.sys, "argv", ["dispatch", str(request_path), directory]),
                mock.patch.object(dispatcher.Path, "is_file", return_value=True),
                mock.patch.object(dispatcher.os, "access", return_value=True),
                mock.patch.object(dispatcher.os, "execv") as execv,
            ):
                self.assertEqual(dispatcher.main(), 70)
            self.assertEqual(execv.call_args.args[0], "/opt/topic4/bin/psi-run-engine.py")


class ImageContractTest(unittest.TestCase):
    def test_provider_catalog_and_image_are_immutable_and_fail_closed(self):
        provider = json.loads((DIRECTORY / "provider.json").read_text(encoding="utf-8"))
        templates = {item["templateId"] for item in provider["templates"]}
        self.assertEqual(
            templates,
            {"psi-2p-v1", "psi-3p-v1", "he-paillier-2p-v1", "vfl-secureboost-2p-v1"},
        )
        self.assertEqual(
            {item["templateId"]: item["operation"] for item in provider["templates"]},
            {
                "psi-2p-v1": "PSI_2P",
                "psi-3p-v1": "PSI_3P",
                "he-paillier-2p-v1": "HE_PAILLIER",
                "vfl-secureboost-2p-v1": "VFL_SECUREBOOST",
            },
        )
        self.assertEqual(
            {item["templateId"]: item["requiredRoles"] for item in provider["templates"]},
            {
                "psi-2p-v1": {"A": "RECEIVER", "B": "PROVIDER"},
                "psi-3p-v1": {"A": "PARTY", "B": "PARTY", "C": "PARTY"},
                "he-paillier-2p-v1": {"A": "KEY_HOLDER", "B": "DATA_HOLDER"},
                "vfl-secureboost-2p-v1": {"A": "ACTIVE", "B": "PASSIVE"},
            },
        )
        vfl = next(item for item in provider["templates"] if item["templateId"] == "vfl-secureboost-2p-v1")
        self.assertEqual(vfl["alignment"], "RR22_FAST_PSI_2PC")
        self.assertTrue(all(item.get("plaintextFallback") is False for item in provider["templates"]))
        dockerfile = (DIRECTORY / "Dockerfile").read_text(encoding="utf-8")
        self.assertIn(
            "ghcr.io/rene0329/data-discovery-practice-server:privacy-secretflow-lite-1.11.0b1-amd64@sha256:f0033a79e71f1f76656230d0f3cd31cbddc8d3bdcdb39fe2fd2afc8b000ec178",
            dockerfile,
        )
        self.assertIn(
            "privacy-secretflow-release-ci-20250228-amd64@sha256:b84f9cacf93a20963811addc794ef1b92ccc3a86c7e94c7994a91e2f10f305c0",
            dockerfile,
        )
        self.assertIn("/opt/psi/main", dockerfile)
        self.assertIn("/opt/psi/parameters/100K-1-16.json", dockerfile)
        self.assertIn("/opt/topic4/provider-apsi.json", dockerfile)
        self.assertIn("/opt/topic4/provider-secretflow.json", dockerfile)
        self.assertNotIn("/etc/topic4-secretflow/cluster.json", dockerfile)
        self.assertNotIn("/run/secrets/topic4-secretflow", json.dumps(provider))


if __name__ == "__main__":
    unittest.main()
