#!/usr/bin/env python3
"""Generate the explicit three-party MP-SPDZ Kubernetes manifest."""
import json
from pathlib import Path


def affinity(node=None):
    expressions = []
    if node:
        expressions.append({"key": "kubernetes.io/hostname", "operator": "In", "values": [node]})
    else:
        expressions.append(
            {"key": "kubernetes.io/hostname", "operator": "NotIn", "values": ["master-88"]})
    return {"nodeAffinity": {"requiredDuringSchedulingIgnoredDuringExecution": {
        "nodeSelectorTerms": [{"matchExpressions": expressions}]
    }}}


def env(name, value=None, config=None, secret=None):
    item = {"name": name}
    if value is not None:
        item["value"] = value
    elif config:
        item["valueFrom"] = {"configMapKeyRef": {"name": config[0], "key": config[1]}}
    else:
        item["valueFrom"] = {"secretKeyRef": {"name": secret[0], "key": secret[1]}}
    return item


def party(letter, index):
    namespace = "kuscia-" + letter.lower()
    labels = {"app": "topic4-mpspdz", "topic4.openai.com/party": letter}
    state_name = "topic4-mpspdz-state"
    return [
        {
            "apiVersion": "v1", "kind": "PersistentVolumeClaim",
            "metadata": {"name": state_name, "namespace": namespace},
            "spec": {
                "accessModes": ["ReadWriteOnce"], "storageClassName": "monitor-storage",
                "resources": {"requests": {"storage": "2Gi"}},
            },
        },
        {
            "apiVersion": "v1", "kind": "Service",
            "metadata": {"name": "topic4-mpspdz", "namespace": namespace},
            "spec": {
                "type": "ClusterIP", "selector": labels,
                "ports": [
                    {"name": "runner", "port": 8080, "targetPort": 8080},
                    {"name": "mpc", "port": 5000, "targetPort": 5000},
                ],
            },
        },
        {
            "apiVersion": "apps/v1", "kind": "Deployment",
            "metadata": {"name": "topic4-mpspdz", "namespace": namespace},
            "spec": {
                "replicas": 1, "strategy": {"type": "Recreate"},
                "selector": {"matchLabels": labels},
                "template": {
                    "metadata": {"labels": labels},
                    "spec": {
                        "affinity": affinity("__NODE_%s__" % letter),
                        "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "fsGroup": 10001},
                        "containers": [{
                            "name": "party", "image": "__MPSPDZ_IMAGE__", "imagePullPolicy": "IfNotPresent",
                            "ports": [
                                {"name": "runner", "containerPort": 8080},
                                {"name": "mpc", "containerPort": 5000},
                            ],
                            "env": [
                                env("TOPIC4_PARTY_ID", letter),
                                env("TOPIC4_PARTY_INDEX", str(index)),
                                env("TOPIC4_AGENT_BASE_URL", config=("topic4-privacy-party-config", "agent-base-url")),
                                env("TOPIC4_AGENT_ALLOWED_HOSTS", config=("topic4-privacy-party-config", "agent-allowed-hosts")),
                                env("TOPIC4_AGENT_ALLOWED_PATH_PREFIXES", config=("topic4-privacy-party-config", "agent-path-prefixes")),
                                env("TOPIC4_IMAGE_DIGEST", config=("topic4-privacy-mpspdz-image", "image-digest")),
                                env("TOPIC4_AUTH_TOKEN_FILE", "/run/secrets/topic4-runner/bearer-token"),
                                env("TOPIC4_INPUT_DIR", "/var/run/topic4-inputs"),
                                env("TOPIC4_WORK_DIR", "/var/run/topic4-work"),
                                env("TOPIC4_MPSPDZ_TLS_DIR", "/run/secrets/topic4-mpspdz"),
                            ],
                            "startupProbe": {"httpGet": {"path": "/live", "port": "runner"},
                                             "periodSeconds": 5, "timeoutSeconds": 3,
                                             "failureThreshold": 60},
                            "readinessProbe": {"httpGet": {"path": "/health", "port": "runner"},
                                               "periodSeconds": 15, "timeoutSeconds": 20,
                                               "failureThreshold": 12},
                            "livenessProbe": {"httpGet": {"path": "/live", "port": "runner"},
                                              "periodSeconds": 20, "timeoutSeconds": 3,
                                              "failureThreshold": 3},
                            "resources": {
                                "requests": {"cpu": "1", "memory": "1Gi"},
                                "limits": {"cpu": "4", "memory": "8Gi"},
                            },
                            "volumeMounts": [
                                {"name": "state", "mountPath": "/var/lib/topic4-privacy/jobs"},
                                {"name": "inputs", "mountPath": "/var/run/topic4-inputs"},
                                {"name": "work", "mountPath": "/var/run/topic4-work"},
                                {"name": "auth", "mountPath": "/run/secrets/topic4-runner", "readOnly": True},
                                {"name": "tls", "mountPath": "/run/secrets/topic4-mpspdz", "readOnly": True},
                            ],
                            "securityContext": {
                                "allowPrivilegeEscalation": False, "readOnlyRootFilesystem": False,
                                "capabilities": {"drop": ["ALL"]},
                            },
                        }],
                        "volumes": [
                            {"name": "state", "persistentVolumeClaim": {"claimName": state_name}},
                            {"name": "inputs", "emptyDir": {"sizeLimit": "1Gi"}},
                            {"name": "work", "emptyDir": {"sizeLimit": "1Gi"}},
                            {"name": "auth", "secret": {
                                "secretName": "privacy-mpspdz-runner-auth", "defaultMode": 288}},
                            {"name": "tls", "secret": {"secretName": "topic4-mpspdz-tls", "defaultMode": 288}},
                        ],
                    },
                },
            },
        },
    ]


def gateway():
    labels = {"app": "topic4-privacy-mpspdz-gateway"}
    return [
        {
            "apiVersion": "v1", "kind": "PersistentVolumeClaim",
            "metadata": {"name": "topic4-privacy-mpspdz-gateway-state", "namespace": "kuscia-master"},
            "spec": {
                "accessModes": ["ReadWriteOnce"], "storageClassName": "monitor-storage",
                "resources": {"requests": {"storage": "1Gi"}},
            },
        },
        {
            "apiVersion": "v1", "kind": "Service",
            "metadata": {"name": "topic4-privacy-mpspdz-gateway", "namespace": "kuscia-master"},
            "spec": {
                "type": "ClusterIP", "selector": labels,
                "ports": [{"name": "http", "port": 8080, "targetPort": 8080}],
            },
        },
        {
            "apiVersion": "apps/v1", "kind": "Deployment",
            "metadata": {"name": "topic4-privacy-mpspdz-gateway", "namespace": "kuscia-master"},
            "spec": {
                "replicas": 1, "strategy": {"type": "Recreate"},
                "selector": {"matchLabels": labels},
                "template": {
                    "metadata": {"labels": labels},
                    "spec": {
                        "affinity": affinity(),
                        "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "fsGroup": 10001},
                        "containers": [{
                            "name": "gateway", "image": "__MPSPDZ_IMAGE__", "imagePullPolicy": "IfNotPresent",
                            "command": ["python3", "/opt/topic4/bin/topic4_privacy_gateway.py"],
                            "ports": [{"name": "http", "containerPort": 8080}],
                            "env": [
                                env("TOPIC4_PROVIDER_ID", "MP_SPDZ"),
                                env("TOPIC4_ENGINE", "MP-SPDZ"),
                                env("TOPIC4_ENGINE_VERSION", "0.4.3"),
                                env("TOPIC4_SOURCE_REVISION", "26a605368e40fed3a7e9cee78c9a3f4390b85eb5"),
                                env("TOPIC4_IMAGE_DIGEST", config=("topic4-privacy-mpspdz-image", "image-digest")),
                                env("TOPIC4_AUTH_TOKEN_FILE", "/run/secrets/topic4-gateway/bearer-token"),
                                env("TOPIC4_RUNNER_TOKEN_DIR", "/run/secrets/topic4-party-tokens"),
                                env("TOPIC4_PARTY_ENDPOINTS_FILE", "/etc/topic4-privacy/runner-endpoints.json"),
                                env("TOPIC4_GATEWAY_STATE_DIR", "/var/lib/topic4-privacy/gateway"),
                            ],
                            "startupProbe": {"httpGet": {"path": "/live", "port": "http"},
                                             "periodSeconds": 5, "timeoutSeconds": 3,
                                             "failureThreshold": 60},
                            "readinessProbe": {"httpGet": {"path": "/health", "port": "http"},
                                               "periodSeconds": 15, "timeoutSeconds": 25,
                                               "failureThreshold": 12},
                            "livenessProbe": {"httpGet": {"path": "/live", "port": "http"},
                                              "periodSeconds": 20, "timeoutSeconds": 3,
                                              "failureThreshold": 3},
                            "volumeMounts": [
                                {"name": "state", "mountPath": "/var/lib/topic4-privacy/gateway"},
                                {"name": "gateway-auth", "mountPath": "/run/secrets/topic4-gateway",
                                 "readOnly": True},
                                {"name": "runner-tokens",
                                 "mountPath": "/run/secrets/topic4-party-tokens",
                                 "readOnly": True},
                                {"name": "endpoints", "mountPath": "/etc/topic4-privacy", "readOnly": True},
                            ],
                            "securityContext": {
                                "allowPrivilegeEscalation": False,
                                "capabilities": {"drop": ["ALL"]},
                            },
                        }],
                        "volumes": [
                            {"name": "state", "persistentVolumeClaim": {
                                "claimName": "topic4-privacy-mpspdz-gateway-state"}},
                            {"name": "gateway-auth", "secret": {
                                "secretName": "privacy-mpspdz-gateway-auth", "defaultMode": 288}},
                            {"name": "runner-tokens", "projected": {
                                "defaultMode": 288,
                                "sources": [{
                                    "secret": {
                                        "name": "privacy-mpspdz-runner-%s-auth" % party.lower(),
                                        "items": [{"key": "bearer-token", "path": party}],
                                    },
                                } for party in ("A", "B", "C")],
                            }},
                            {"name": "endpoints", "configMap": {
                                "name": "topic4-privacy-mpspdz-gateway-config"}},
                        ],
                    },
                },
            },
        },
    ]


def main():
    items = []
    for index, letter in enumerate(("A", "B", "C")):
        items.extend(party(letter, index))
    items.extend(gateway())
    target = Path(__file__).with_name("mpspdz.json.tpl")
    target.write_text(json.dumps({"apiVersion": "v1", "kind": "List", "items": items},
                                 indent=2, sort_keys=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
