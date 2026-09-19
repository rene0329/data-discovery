#!/usr/bin/env python3
"""Behavior and ordering checks for Kuscia Master Gateway cleanup."""
import os
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
KUSCIA = ROOT / "k8s" / "kuscia"
CLEANUP = KUSCIA / "cleanup-stale-master-gateways.sh"
BOOTSTRAP = KUSCIA / "bootstrap.sh"


class KusciaBootstrapGatewayCleanupTest(unittest.TestCase):
    def run_cleanup(self, outer_pods, inner_gateways, pod_ref="pod/master-current"):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            log = temp / "kubectl.log"
            fake = temp / "kubectl"
            fake.write_text(textwrap.dedent("""\
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\n' "$*" >> "$FAKE_KUBECTL_LOG"
                if [[ "$*" == *" get pods "* ]]; then
                  printf '%s' "$FAKE_OUTER_PODS"
                elif [[ "$*" == *" get gateways.kuscia.secretflow "* ]]; then
                  printf '%s' "$FAKE_INNER_GATEWAYS"
                elif [[ "$*" == *" delete gateways.kuscia.secretflow "* ]]; then
                  :
                else
                  echo "unexpected kubectl invocation: $*" >&2
                  exit 99
                fi
            """), encoding="utf-8")
            fake.chmod(0o755)
            env = os.environ.copy()
            env.update({
                "PATH": str(temp) + os.pathsep + env.get("PATH", ""),
                "FAKE_KUBECTL_LOG": str(log),
                "FAKE_OUTER_PODS": outer_pods,
                "FAKE_INNER_GATEWAYS": inner_gateways,
            })
            result = subprocess.run(
                ["bash", str(CLEANUP), pod_ref, "topic4-master"],
                env=env, text=True, capture_output=True, check=False,
            )
            calls = log.read_text(encoding="utf-8") if log.exists() else ""
            return result, calls

    def test_deletes_only_gateways_without_a_corresponding_outer_pod(self):
        result, calls = self.run_cleanup(
            "master-current\nmaster-rolling\n",
            "master-current\nmaster-rolling\nmaster-stale\n",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        deletes = [line for line in calls.splitlines()
                   if " delete gateways.kuscia.secretflow " in line]
        self.assertEqual(len(deletes), 1)
        self.assertIn("master-stale", deletes[0])
        self.assertNotIn("delete gateways.kuscia.secretflow master-current", deletes[0])
        self.assertNotIn("delete gateways.kuscia.secretflow master-rolling", deletes[0])
        self.assertIn("--ignore-not-found=true --wait=true", deletes[0])

    def test_accepts_plain_pod_name_and_keeps_current_gateway(self):
        result, calls = self.run_cleanup(
            "master-current\n", "master-current\n", pod_ref="master-current")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(" delete gateways.kuscia.secretflow ", calls)

    def test_fails_closed_when_current_pod_is_missing(self):
        result, calls = self.run_cleanup(
            "master-other\n", "master-current\nmaster-stale\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("current Master pod is absent", result.stderr)
        self.assertNotIn(" get gateways.kuscia.secretflow ", calls)
        self.assertNotIn(" delete gateways.kuscia.secretflow ", calls)

    def test_rejects_malformed_gateway_name_before_delete(self):
        result, calls = self.run_cleanup(
            "master-current\n", "master-current\ninvalid/name\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid Gateway name", result.stderr)
        self.assertNotIn(" delete gateways.kuscia.secretflow ", calls)

    def test_bootstrap_cleans_after_master_ready_and_before_lite_tokens(self):
        script = BOOTSTRAP.read_text(encoding="utf-8")
        wait = script.index('wait --for=condition=Ready "$master_pod"')
        cleanup = script.index('cleanup-stale-master-gateways.sh')
        lite = script.index("for letter in a b c; do", cleanup)
        self.assertLess(wait, cleanup)
        self.assertLess(cleanup, lite)
        self.assertIn('"$master_pod" "$master_domain"', script[cleanup:lite])

    def test_explicit_rotation_clears_old_domain_trust_before_new_tokens(self):
        script = BOOTSTRAP.read_text(encoding="utf-8")
        rotation = script.index('if [[ "$rotate_domain_credentials" == 1 ]]')
        scale_down = script.index("scale deploy/kuscia-lite --replicas=0", rotation)
        delete_routes = script.index("kubectl delete clusterdomainroutes", rotation)
        delete_domain_routes = script.index("delete domainroutes --all", delete_routes)
        delete_domains = script.index("kubectl delete domains domain-a domain-b domain-c",
                                      delete_domain_routes)
        mint_tokens = script.index("for letter in a b c; do", delete_domains)
        self.assertLess(scale_down, delete_routes)
        self.assertLess(delete_routes, delete_domain_routes)
        self.assertLess(delete_domain_routes, delete_domains)
        self.assertLess(delete_domains, mint_tokens)
        self.assertIn('route_names+=("domain-${source}-${master_domain}")',
                      script[rotation:delete_routes])
        self.assertIn('for domain_namespace in "$master_domain" domain-a domain-b domain-c',
                      script[delete_routes:delete_domains])
        self.assertIn("--ignore-not-found=true --wait=true",
                      script[delete_routes:mint_tokens])
        scale_up = script.index("scale deploy/kuscia-lite --replicas=1", mint_tokens)
        self.assertGreater(scale_up, mint_tokens)

    def test_bootstrap_waits_for_lite_master_routes_before_cross_routes(self):
        script = BOOTSTRAP.read_text(encoding="utf-8")
        restarts = script.index('rollout status deploy/kuscia-lite')
        master_route = script.index('route="domain-${source}-${master_domain}"', restarts)
        cross_routes = script.index('create_cluster_domain_route.sh', master_route)
        self.assertLess(restarts, master_route)
        self.assertLess(master_route, cross_routes)


if __name__ == "__main__":
    unittest.main()
