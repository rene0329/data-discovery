#!/usr/bin/env python3
"""Generate isolated, provider-specific gateway Services and Deployments."""
import argparse
import json
import re
from pathlib import Path


PROVIDERS = {
    "apsi": {
        "providerId": "KUSCIA_APSI", "engine": "SecretFlow PSI",
        "version": "0.6.0.dev260105",
        "revision": "72f3312fd9142ea567f3a170d0808a2118b75af8",
    },
    "secretflow": {
        "providerId": "KUSCIA_SECRETFLOW", "engine": "SecretFlow PSI / HEU / SecureBoost",
        "version": "1.11.0b1",
        "revision": "b9d6fd5ca9dbdfc95cfda5c282e93022e1caebc4",
    },
    "sfl": {
        "providerId": "KUSCIA_SFL", "engine": "SFL",
        "version": "commit:c383e40f665063d7f7d87e437a73e015f87c435c",
        "revision": "c383e40f665063d7f7d87e437a73e015f87c435c",
    },
}


def gateway(provider, image, digest):
    metadata = PROVIDERS[provider]
    name = "topic4-privacy-%s-gateway" % provider
    labels = {"app": name}
    provider_config = {
        "apsi": "/opt/topic4/provider-apsi.json",
        "secretflow": "/opt/topic4/provider-secretflow.json",
        "sfl": "/opt/topic4/provider.json",
    }[provider]
    readiness_path = "/live" if provider == "sfl" else "/health"
    gateway_auth_secret = "privacy-%s-gateway-auth" % provider
    runner_token_sources = [{
        "secret": {
            "name": "privacy-%s-runner-%s-auth" % (provider, party.lower()),
            "items": [{"key": "bearer-token", "path": party}],
        },
    } for party in ("A", "B", "C")]
    return [
        {
            "apiVersion": "v1", "kind": "PersistentVolumeClaim",
            "metadata": {"name": name + "-state", "namespace": "kuscia-master"},
            "spec": {
                "accessModes": ["ReadWriteOnce"], "storageClassName": "monitor-storage",
                "resources": {"requests": {"storage": "1Gi"}},
            },
        },
        {
            "apiVersion": "v1", "kind": "Service",
            "metadata": {"name": name, "namespace": "kuscia-master"},
            "spec": {
                "type": "ClusterIP", "selector": labels,
                "ports": [{"name": "http", "port": 8080, "targetPort": 8080}],
            },
        },
        {
            "apiVersion": "apps/v1", "kind": "Deployment",
            "metadata": {"name": name, "namespace": "kuscia-master"},
            "spec": {
                "replicas": 1, "strategy": {"type": "Recreate"},
                "selector": {"matchLabels": labels},
                "template": {
                    "metadata": {"labels": labels},
                    "spec": {
                        "affinity": {"nodeAffinity": {
                            "requiredDuringSchedulingIgnoredDuringExecution": {
                                "nodeSelectorTerms": [{"matchExpressions": [{
                                    "key": "kubernetes.io/hostname", "operator": "NotIn",
                                    "values": ["master-88"],
                                }]}],
                            },
                        }},
                        "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "fsGroup": 10001},
                        "initContainers": [{
                            "name": "prepare-gateway-state", "image": "%s@%s" % (image, digest),
                            "imagePullPolicy": "IfNotPresent",
                            "command": ["sh", "-c",
                                        "chown 10001:10001 /state && chmod 0700 /state"],
                            "securityContext": {
                                "runAsNonRoot": False, "runAsUser": 0, "runAsGroup": 0,
                                "allowPrivilegeEscalation": False,
                                "capabilities": {
                                    "drop": ["ALL"],
                                    "add": ["CHOWN", "DAC_OVERRIDE", "FOWNER"],
                                },
                            },
                            "volumeMounts": [{"name": "state", "mountPath": "/state"}],
                        }],
                        "containers": [{
                            "name": "gateway", "image": "%s@%s" % (image, digest),
                            "imagePullPolicy": "IfNotPresent",
                            "command": ["python3", "/opt/topic4/bin/topic4_privacy_gateway.py"],
                            "ports": [{"name": "http", "containerPort": 8080}],
                            "env": [
                                {"name": "TOPIC4_PROVIDER_ID", "value": metadata["providerId"]},
                                {"name": "TOPIC4_ENGINE", "value": metadata["engine"]},
                                {"name": "TOPIC4_ENGINE_VERSION", "value": metadata["version"]},
                                {"name": "TOPIC4_SOURCE_REVISION", "value": metadata["revision"]},
                                {"name": "TOPIC4_IMAGE_DIGEST", "value": digest},
                                {"name": "TOPIC4_AUTH_TOKEN_FILE",
                                 "value": "/run/secrets/topic4-gateway/bearer-token"},
                                {"name": "TOPIC4_RUNNER_TOKEN_DIR",
                                 "value": "/run/secrets/topic4-party-tokens"},
                                {"name": "TOPIC4_PROVIDER_CONFIG", "value": provider_config},
                                {"name": "TOPIC4_PARTY_ENDPOINTS_FILE",
                                 "value": "/etc/topic4-privacy/runner-endpoints.json"},
                                {"name": "TOPIC4_GATEWAY_STATE_DIR",
                                 "value": "/var/lib/topic4-privacy/gateway"},
                                *([{"name": "TOPIC4_SFL_BOOTSTRAP_MODE", "value": "1"}]
                                  if provider == "sfl" else []),
                                {"name": "PYTHONDONTWRITEBYTECODE", "value": "1"},
                            ],
                            "readinessProbe": {
                                "httpGet": {"path": readiness_path, "port": "http"},
                                "periodSeconds": 15, "timeoutSeconds": 25,
                                "failureThreshold": 12,
                            },
                            "startupProbe": {
                                "httpGet": {"path": "/live", "port": "http"},
                                "periodSeconds": 5, "timeoutSeconds": 3,
                                "failureThreshold": 60,
                            },
                            "livenessProbe": {
                                "httpGet": {"path": "/live", "port": "http"},
                                "periodSeconds": 20, "timeoutSeconds": 3,
                                "failureThreshold": 3,
                            },
                            "resources": {
                                "requests": {"cpu": "100m", "memory": "128Mi"},
                                "limits": {"cpu": "1", "memory": "512Mi"},
                            },
                            "volumeMounts": [
                                {"name": "state", "mountPath": "/var/lib/topic4-privacy/gateway"},
                                {"name": "endpoints", "mountPath": "/etc/topic4-privacy", "readOnly": True},
                                {"name": "gateway-auth", "mountPath": "/run/secrets/topic4-gateway",
                                 "readOnly": True},
                                {"name": "runner-tokens",
                                 "mountPath": "/run/secrets/topic4-party-tokens",
                                 "readOnly": True},
                            ],
                            "securityContext": {
                                "allowPrivilegeEscalation": False,
                                "capabilities": {"drop": ["ALL"]},
                            },
                        }],
                        "volumes": [
                            {"name": "state", "persistentVolumeClaim": {"claimName": name + "-state"}},
                            {"name": "endpoints", "configMap": {"name": name + "-config"}},
                            {"name": "gateway-auth", "secret": {
                                "secretName": gateway_auth_secret, "defaultMode": 288,
                            }},
                            {"name": "runner-tokens", "projected": {
                                "defaultMode": 288, "sources": runner_token_sources,
                            }},
                        ],
                    },
                },
            },
        },
    ]


def main():
    parser = argparse.ArgumentParser()
    for provider in PROVIDERS:
        parser.add_argument("--%s-image" % provider, required=True)
        parser.add_argument("--%s-digest" % provider, required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    items = []
    for provider in PROVIDERS:
        image = getattr(args, provider + "_image")
        digest = getattr(args, provider + "_digest").lower()
        if not re.fullmatch(r".+:v-[0-9a-f]{40}", image):
            parser.error("%s image must use v-<full-git-sha>" % provider)
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            parser.error("%s digest must be immutable sha256" % provider)
        items.extend(gateway(provider, image, digest))
    Path(args.output).write_text(json.dumps({
        "apiVersion": "v1", "kind": "List", "items": items,
    }, sort_keys=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
