#!/usr/bin/env python3
"""Generate pinned APSI/SecretFlow/SFL AppImage and KusciaDeployment resources."""
import argparse
import json
import re
from pathlib import Path
from urllib.parse import urlparse

PARTIES = (("A", "domain-a"), ("B", "domain-b"), ("C", "domain-c"))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--provider", required=True, choices=("apsi", "secretflow", "sfl"))
    parser.add_argument("--image", required=True)
    parser.add_argument("--image-digest", required=True)
    parser.add_argument("--image-id", required=True,
                        help="OCI config digest from docker image inspect .Id")
    parser.add_argument("--node-a", required=True)
    parser.add_argument("--node-b", required=True)
    parser.add_argument("--node-c", required=True)
    parser.add_argument("--agent-url-a", required=True)
    parser.add_argument("--agent-url-b", required=True)
    parser.add_argument("--agent-url-c", required=True)
    parser.add_argument("--agent-node-a", required=True)
    parser.add_argument("--agent-node-b", required=True)
    parser.add_argument("--agent-node-c", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    image_match = re.fullmatch(r"(.+):(v-[0-9a-f]{40})", args.image)
    if not image_match:
        parser.error("--image must use an immutable v-<full-git-sha> tag")
    if not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", args.image_digest):
        parser.error("--image-digest must be sha256:<64 hex>")
    if not re.fullmatch(r"sha256:[0-9a-fA-F]{64}", args.image_id):
        parser.error("--image-id must be the sha256 OCI config digest")

    nodes = {party: getattr(args, "node_" + party.lower()) for party, _ in PARTIES}
    agent_map = {}
    party_map = {}
    for party, domain in PARTIES:
        url = getattr(args, "agent_url_" + party.lower()).rstrip("/")
        parsed = urlparse(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.path not in ("", "/"):
            parser.error("invalid Agent URL for party %s" % party)
        agent_map[domain] = {
            "baseUrl": url,
            "nodeName": getattr(args, "agent_node_" + party.lower()),
            "allowedPathPrefixes": ["/dataset/", "/data/"],
        }
        party_map[domain] = party

    provider = args.provider
    app_name = "topic4-privacy-%s-image" % provider
    kd_name = "topic4-privacy-%s-parties" % provider
    service_prefix = "topic4-%s" % provider
    # Every Lite domain owns a Secret with this same object name but a
    # different random value.  The control-plane gateway never uses this
    # Secret for its inbound authentication.
    runner_auth_secret = "privacy-%s-runner-auth" % provider
    context_template = json.dumps({
        "servingId": "{{.SERVING_ID}}",
        "domainId": "{{.KUSCIA_DOMAIN_ID}}",
        "inputConfig": "{{.INPUT_CONFIG}}",
        "clusterDefine": "{{.CLUSTER_DEFINE}}",
        "allocatedPorts": "{{.ALLOCATED_PORTS}}",
    }, indent=2) + "\n"
    ports = [{"name": "runner", "protocol": "HTTP", "scope": "Domain"}]
    if provider in ("apsi", "secretflow"):
        ports.append({"name": "psi", "protocol": "GRPC", "scope": "Cluster"})
    if provider in ("secretflow", "sfl"):
        ports.extend([
            {"name": "spu", "protocol": "GRPC", "scope": "Cluster"},
            {"name": "fed", "protocol": "GRPC", "scope": "Cluster"},
        ])
    provider_config = {
        "apsi": "/opt/topic4/provider-apsi.json",
        "secretflow": "/opt/topic4/provider-secretflow.json",
        "sfl": "/opt/topic4/provider.json",
    }[provider]
    readiness_path = "/live" if provider == "sfl" else "/health"
    app_image = {
        "apiVersion": "kuscia.secretflow/v1alpha1", "kind": "AppImage",
        "metadata": {"name": app_name},
        "spec": {
            "configTemplates": {"runtime-context.json": context_template},
            "deployTemplates": [{
                "name": "runner", "replicas": 1,
                "spec": {
                    "restartPolicy": "Always",
                    "containers": [{
                        "name": "runner",
                        "command": ["python3", "/opt/topic4/bin/kuscia-runtime-entry.py"],
                        "args": ["serve", "/work/kuscia/runtime-context.json"],
                        "workingDir": "/work",
                        "configVolumeMounts": [{
                            "mountPath": "/work/kuscia/runtime-context.json",
                            "subPath": "runtime-context.json",
                        }],
                        "ports": ports,
                        "env": [
                            {"name": "TOPIC4_AUTH_TOKEN", "valueFrom": {
                                "secretKeyRef": {
                                    "name": runner_auth_secret, "key": "bearer-token",
                                },
                            }},
                            {"name": "TOPIC4_IMAGE_DIGEST", "value": args.image_digest.lower()},
                            {"name": "TOPIC4_PROVIDER_CONFIG", "value": provider_config},
                            {"name": "TOPIC4_STATE_DIR", "value": "/var/lib/topic4-privacy/jobs"},
                            {"name": "TOPIC4_INPUT_DIR", "value": "/var/run/topic4-inputs"},
                            {"name": "TOPIC4_WORK_DIR", "value": "/var/run/topic4-work"},
                            *([{"name": "TOPIC4_SFL_BOOTSTRAP_MODE", "value": "1"}]
                              if provider == "sfl" else []),
                            {"name": "PYTHONDONTWRITEBYTECODE", "value": "1"},
                        ],
                        "readinessProbe": {
                            "httpGet": {"path": readiness_path, "port": "runner"},
                            "periodSeconds": 15, "timeoutSeconds": 20,
                            "failureThreshold": 12,
                        },
                        "startupProbe": {
                            "httpGet": {"path": "/live", "port": "runner"},
                            "periodSeconds": 5, "timeoutSeconds": 3,
                            "failureThreshold": 60,
                        },
                        "livenessProbe": {
                            "httpGet": {"path": "/live", "port": "runner"},
                            "periodSeconds": 20, "timeoutSeconds": 3,
                            "failureThreshold": 3,
                        },
                        "resources": {
                            "requests": {"cpu": "500m", "memory": "1Gi"},
                            "limits": {"cpu": "4", "memory": "8Gi"},
                        },
                        "imagePullPolicy": "IfNotPresent",
                        "securityContext": {
                            "allowPrivilegeEscalation": False,
                            "capabilities": {"drop": ["ALL"]},
                        },
                    }],
                },
            }],
            "image": {
                "name": image_match.group(1), "tag": image_match.group(2),
                # Kuscia v1.2 compares AppImage image.id with CRI Image.Id,
                # which is the OCI config digest, not a registry manifest digest.
                "id": args.image_id.lower(),
            },
        },
    }
    kd = {
        "apiVersion": "kuscia.secretflow/v1alpha1", "kind": "KusciaDeployment",
        "metadata": {
            "name": kd_name, "namespace": "cross-domain",
            "labels": {"kuscia.secretflow/app-type": "serving"},
        },
        "spec": {
            "initiator": "domain-a",
            "inputConfig": json.dumps({
                "contractVersion": "topic4.kuscia.input/v1",
                "partyMap": party_map, "agentMap": agent_map,
                "nodeMap": {domain: nodes[party] for party, domain in PARTIES},
            }, sort_keys=True, separators=(",", ":")),
            "parties": [{
                "domainID": domain, "appImageRef": app_name,
                "serviceNamePrefix": service_prefix,
                # The operator adds party-local persistent storage to the
                # concrete RunK Deployment after Kuscia materializes it.
                # Recreate also makes updates deterministic for one runner.
                "template": {
                    "replicas": 1,
                    "strategy": {"type": "Recreate"},
                    # Outer-cluster node affinity belongs to the Lite RunK
                    # provider configuration. Putting outer node names here
                    # would constrain the embedded Kuscia scheduler instead.
                    "spec": {},
                },
            } for party, domain in PARTIES],
        },
    }
    Path(args.output).write_text(json.dumps({
        "apiVersion": "v1", "kind": "List", "items": [app_image, kd],
    }, sort_keys=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
