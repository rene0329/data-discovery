#!/usr/bin/env python3
"""Contract checks for gateway/party bearer credential isolation."""
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
K8S = ROOT / "k8s"
IMAGE = "registry.internal/topic4-privacy:v-" + "1" * 40
DIGEST = "sha256:" + "a" * 64
IMAGE_ID = "sha256:" + "b" * 64


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def deployment(items, name):
    return next(item for item in items
                if item["kind"] == "Deployment" and item["metadata"]["name"] == name)


def named(items):
    return {item["name"]: item for item in items}


class DeploymentAuthIsolationTest(unittest.TestCase):
    def test_kuscia_parties_reference_namespace_local_runner_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            for provider in ("apsi", "secretflow", "sfl"):
                output = Path(directory) / (provider + ".json")
                subprocess.run([
                    sys.executable, str(K8S / "generate_kuscia_provider.py"),
                    "--provider", provider, "--image", IMAGE,
                    "--image-digest", DIGEST, "--image-id", IMAGE_ID,
                    "--node-a", "node-a", "--node-b", "node-b", "--node-c", "node-c",
                    "--agent-url-a", "http://agent-a:8080",
                    "--agent-url-b", "http://agent-b:8080",
                    "--agent-url-c", "http://agent-c:8080",
                    "--agent-node-a", "node-a", "--agent-node-b", "node-b",
                    "--agent-node-c", "node-c", "--output", str(output),
                ], check=True)
                manifest = json.loads(output.read_text(encoding="utf-8"))
                container = manifest["items"][0]["spec"]["deployTemplates"][0]["spec"][
                    "containers"][0]
                self.assertNotIn("envFrom", container)
                token_env = next(item for item in container["env"]
                                 if item["name"] == "TOPIC4_AUTH_TOKEN")
                self.assertEqual(token_env["valueFrom"]["secretKeyRef"], {
                    "name": "privacy-%s-runner-auth" % provider,
                    "key": "bearer-token",
                })
                self.assertNotIn("privacy-%s-gateway-auth" % provider,
                                 json.dumps(container, sort_keys=True))

    def test_provider_gateways_mount_separate_ingress_and_three_runner_tokens(self):
        module = load_module("topic4_provider_gateways", K8S / "generate_provider_gateways.py")
        for provider in ("apsi", "secretflow", "sfl"):
            dep = deployment(module.gateway(provider, IMAGE, DIGEST),
                             "topic4-privacy-%s-gateway" % provider)
            pod = dep["spec"]["template"]["spec"]
            container = pod["containers"][0]
            self.assertEqual(container["image"], "%s@%s" % (IMAGE, DIGEST))
            self.assertNotIn("envFrom", container)
            env = {item["name"]: item["value"] for item in container["env"]
                   if "value" in item}
            self.assertEqual(env["TOPIC4_AUTH_TOKEN_FILE"],
                             "/run/secrets/topic4-gateway/bearer-token")
            self.assertEqual(env["TOPIC4_RUNNER_TOKEN_DIR"],
                             "/run/secrets/topic4-party-tokens")

            volumes = named(pod["volumes"])
            self.assertEqual(volumes["gateway-auth"]["secret"]["secretName"],
                             "privacy-%s-gateway-auth" % provider)
            sources = volumes["runner-tokens"]["projected"]["sources"]
            self.assertEqual([
                (item["secret"]["name"], item["secret"]["items"])
                for item in sources
            ], [
                ("privacy-%s-runner-%s-auth" % (provider, party.lower()),
                 [{"key": "bearer-token", "path": party}])
                for party in ("A", "B", "C")
            ])
            mounts = named(container["volumeMounts"])
            self.assertEqual(mounts["gateway-auth"]["mountPath"],
                             "/run/secrets/topic4-gateway")
            self.assertEqual(mounts["runner-tokens"]["mountPath"],
                             "/run/secrets/topic4-party-tokens")

    def test_mpspdz_manifest_uses_party_local_and_gateway_projected_secrets(self):
        module = load_module("topic4_mpspdz_manifest", K8S / "generate_mpspdz_manifest.py")
        items = []
        for index, party in enumerate(("A", "B", "C")):
            party_items = module.party(party, index)
            items.extend(party_items)
            dep = deployment(party_items, "topic4-mpspdz")
            volumes = named(dep["spec"]["template"]["spec"]["volumes"])
            self.assertEqual(volumes["auth"]["secret"]["secretName"],
                             "privacy-mpspdz-runner-auth")
        gateway_items = module.gateway()
        items.extend(gateway_items)
        dep = deployment(gateway_items, "topic4-privacy-mpspdz-gateway")
        pod = dep["spec"]["template"]["spec"]
        container = pod["containers"][0]
        self.assertEqual(container["image"],
                         "__MPSPDZ_IMAGE__@__MPSPDZ_IMAGE_DIGEST__")
        env = {item["name"]: item["value"] for item in container["env"]
               if "value" in item}
        self.assertEqual(env["TOPIC4_AUTH_TOKEN_FILE"],
                         "/run/secrets/topic4-gateway/bearer-token")
        self.assertEqual(env["TOPIC4_RUNNER_TOKEN_DIR"],
                         "/run/secrets/topic4-party-tokens")
        volumes = named(pod["volumes"])
        self.assertEqual(volumes["gateway-auth"]["secret"]["secretName"],
                         "privacy-mpspdz-gateway-auth")
        sources = volumes["runner-tokens"]["projected"]["sources"]
        self.assertEqual([item["secret"]["name"] for item in sources], [
            "privacy-mpspdz-runner-a-auth",
            "privacy-mpspdz-runner-b-auth",
            "privacy-mpspdz-runner-c-auth",
        ])
        self.assertEqual([item["secret"]["items"][0]["path"] for item in sources],
                         ["A", "B", "C"])

        generated = json.loads((K8S / "mpspdz.json.tpl").read_text(encoding="utf-8"))
        self.assertEqual(generated["items"], items)

    def test_deploy_scripts_generate_distinct_file_backed_credentials(self):
        kuscia = (K8S / "deploy-kuscia-providers.sh").read_text(encoding="utf-8")
        mpspdz = (K8S / "deploy-mpspdz.sh").read_text(encoding="utf-8")
        for script in (kuscia, mpspdz):
            self.assertIn("openssl rand -hex 32", script)
            self.assertIn("--from-file=bearer-token=", script)
            self.assertIn('"runnerTokenFile": "/run/secrets/topic4-party-tokens/%s" % party',
                          script)
            self.assertNotIn("--from-env-file", script)
            self.assertNotIn("--from-literal=bearer-token", script)
            self.assertNotIn("TOPIC4_AUTH_TOKEN=", script)
        self.assertIn('"privacy-${provider}-gateway-auth"', kuscia)
        self.assertIn('"privacy-${provider}-runner-auth"', kuscia)
        self.assertIn('"privacy-${provider}-runner-${lower}-auth"', kuscia)
        self.assertIn("privacy-mpspdz-gateway-auth", mpspdz)
        self.assertIn("privacy-mpspdz-runner-auth", mpspdz)
        self.assertIn('"privacy-mpspdz-runner-${lower}-auth"', mpspdz)
        self.assertIn('export MPSPDZ_IMAGE MPSPDZ_IMAGE_DIGEST', mpspdz)
        self.assertIn('"MPSPDZ_IMAGE_DIGEST", "NODE_A"', mpspdz)

    def test_sfl_smoke_uses_gateway_ingress_secret(self):
        script = (K8S / "run-and-approve-sfl-smoke.sh").read_text(encoding="utf-8")
        self.assertIn("privacy-sfl-gateway-auth", script)
        self.assertIn('index .data "bearer-token"', script)
        self.assertNotIn("privacy-sfl-auth", script)


if __name__ == "__main__":
    unittest.main()
