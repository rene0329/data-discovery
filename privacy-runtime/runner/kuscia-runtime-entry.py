#!/usr/bin/env python3
"""Validate Kuscia-rendered deployment context, then start the fixed HTTP runner.

This entrypoint is used only by an AppImage/KusciaDeployment workload.  It
derives all cross-domain engine endpoints and the local dynamic ports from the
read-only config rendered by Kuscia.  A job request cannot override them.
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlparse

ID_RE = re.compile(r"^[a-z0-9](?:[-a-z0-9]{0,61}[a-z0-9])?$")
BACKEND_PARTY_RE = re.compile(r"^[A-Z][A-Z0-9_-]{0,31}$")
CLUSTER_ENDPOINT_RE = re.compile(
    r"^[a-z0-9](?:[-a-z0-9.]{0,252}[a-z0-9])?(?::[1-9][0-9]{0,4})?$"
)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def inner_json(value, name):
    if isinstance(value, dict):
        return value
    if not isinstance(value, str) or not value:
        raise ValueError("%s is absent from the Kuscia-rendered context" % name)
    parsed = json.loads(value)
    if not isinstance(parsed, dict):
        raise ValueError("%s must render as a JSON object" % name)
    return parsed


def service_endpoint(party, port_name):
    services = party.get("services")
    if not isinstance(services, list):
        raise ValueError("Kuscia cluster party services are missing")
    for service in services:
        if service.get("portName") == port_name:
            endpoints = service.get("endpoints")
            if (not isinstance(endpoints, list) or len(endpoints) != 1
                    or not isinstance(endpoints[0], str) or not endpoints[0]):
                raise ValueError("Kuscia service %s requires one endpoint" % port_name)
            return endpoints[0]
    raise ValueError("Kuscia service %s is absent" % port_name)


def remote_endpoint(party, port_name, *, http=False):
    endpoint = service_endpoint(party, port_name)
    if not CLUSTER_ENDPOINT_RE.fullmatch(endpoint):
        raise ValueError("invalid Kuscia Cluster endpoint for %s" % port_name)
    if ":" not in endpoint:
        endpoint += ":80"
    return ("http://" if http else "") + endpoint


def validate_context(path):
    outer = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(outer, dict):
        raise ValueError("Kuscia context must be an object")
    domain_id = outer.get("domainId")
    serving_id = outer.get("servingId")
    if not isinstance(domain_id, str) or not ID_RE.fullmatch(domain_id):
        raise ValueError("invalid Kuscia domainId")
    if not isinstance(serving_id, str) or not ID_RE.fullmatch(serving_id):
        raise ValueError("invalid Kuscia servingId")
    cluster = inner_json(outer.get("clusterDefine"), "CLUSTER_DEFINE")
    ports = inner_json(outer.get("allocatedPorts"), "ALLOCATED_PORTS")
    deployment_input = inner_json(outer.get("inputConfig"), "INPUT_CONFIG")
    parties = cluster.get("parties")
    self_index = cluster.get("selfPartyIdx")
    if (not isinstance(parties, list) or len(parties) not in (2, 3)
            or not isinstance(self_index, int) or isinstance(self_index, bool)
            or not 0 <= self_index < len(parties)):
        raise ValueError("invalid Kuscia CLUSTER_DEFINE party topology")
    names = [item.get("name") for item in parties if isinstance(item, dict)]
    if (len(names) != len(parties) or len(set(names)) != len(names)
            or any(not isinstance(name, str) or not ID_RE.fullmatch(name) for name in names)):
        raise ValueError("invalid or duplicate Kuscia domain names")
    if names[self_index] != domain_id:
        raise ValueError("KUSCIA_DOMAIN_ID differs from CLUSTER_DEFINE self party")

    # The controller injects the same values as environment variables.  Requiring
    # equality prevents a normal Deployment with a hand-written marker from being
    # reported as Kuscia-managed.
    injected = {
        "KUSCIA_DOMAIN_ID": domain_id,
        "CLUSTER_DEFINE": cluster,
        "ALLOCATED_PORTS": ports,
    }
    if os.getenv("KUSCIA_DOMAIN_ID") != domain_id:
        raise ValueError("KUSCIA_DOMAIN_ID was not injected by KusciaDeployment")
    if os.getenv("SERVING_ID") != serving_id:
        raise ValueError("SERVING_ID was not injected by KusciaDeployment")
    for env_name, expected in (("CLUSTER_DEFINE", cluster), ("ALLOCATED_PORTS", ports)):
        actual = inner_json(os.getenv(env_name), env_name)
        if canonical(actual) != canonical(expected):
            raise ValueError("%s differs from the read-only rendered context" % env_name)
    if canonical(inner_json(os.getenv("INPUT_CONFIG"), "INPUT_CONFIG")) != canonical(deployment_input):
        raise ValueError("INPUT_CONFIG differs from the read-only rendered context")

    allocated = {}
    for item in ports.get("ports", []):
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            raise ValueError("invalid Kuscia allocated port")
        number = item.get("port")
        if not isinstance(number, int) or isinstance(number, bool) or not 1 <= number <= 65535:
            raise ValueError("invalid Kuscia allocated port number")
        allocated[item["name"]] = {
            "port": number, "scope": item.get("scope"), "protocol": item.get("protocol"),
        }
    if "runner" not in allocated:
        raise ValueError("AppImage must allocate a runner port")
    if (allocated["runner"]["protocol"] != "HTTP"
            or allocated["runner"]["scope"] != "Domain"):
        raise ValueError("Kuscia runner port protocol/scope differs from the registered AppImage")
    has_psi = "psi" in allocated
    has_secretflow = "fed" in allocated or "spu" in allocated
    if has_psi and (allocated["psi"]["protocol"] != "GRPC"
                    or allocated["psi"]["scope"] != "Cluster"):
        raise ValueError("Kuscia PSI port protocol/scope differs from the registered AppImage")
    if has_secretflow:
        if not {"fed", "spu"}.issubset(allocated):
            raise ValueError("SecretFlow AppImage must allocate both fed and spu ports")
        for name in ("fed", "spu"):
            if allocated[name]["protocol"] != "GRPC" or allocated[name]["scope"] != "Cluster":
                raise ValueError("Kuscia %s port protocol/scope differs from the registered AppImage" % name)

    party_map = deployment_input.get("partyMap")
    agent_map = deployment_input.get("agentMap")
    if not isinstance(party_map, dict) or set(party_map) != set(names):
        raise ValueError("INPUT_CONFIG partyMap differs from CLUSTER_DEFINE")
    if (not isinstance(agent_map, dict) or set(agent_map) != set(names)
            or len(set(party_map.values())) != len(names)):
        raise ValueError("INPUT_CONFIG agentMap differs from CLUSTER_DEFINE")
    for domain, party_id in party_map.items():
        if not isinstance(party_id, str) or not BACKEND_PARTY_RE.fullmatch(party_id):
            raise ValueError("invalid backend party mapping")
        item = agent_map[domain]
        if not isinstance(item, dict):
            raise ValueError("invalid Agent mapping")
        parsed = urlparse(str(item.get("baseUrl", "")))
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.path not in ("", "/"):
            raise ValueError("invalid fixed Agent base URL")
        if not isinstance(item.get("nodeName"), str) or not item["nodeName"]:
            raise ValueError("fixed Agent nodeName is required")

    engine_endpoints = {}
    fed_endpoints = {}
    spu_endpoints = {}
    runner_endpoints = {}
    for index, party in enumerate(parties):
        party_id = party_map[party["name"]]
        if has_psi:
            if index == self_index:
                engine_endpoints[party_id] = "0.0.0.0:%d" % allocated["psi"]["port"]
            else:
                engine_endpoints[party_id] = remote_endpoint(party, "psi", http=True)
        if has_secretflow:
            if index == self_index:
                fed_endpoints[party_id] = "0.0.0.0:%d" % allocated["fed"]["port"]
                spu_endpoints[party_id] = "0.0.0.0:%d" % allocated["spu"]["port"]
            else:
                fed_endpoints[party_id] = remote_endpoint(party, "fed")
                spu_endpoints[party_id] = remote_endpoint(party, "spu", http=True)
        if index == self_index:
            runner_endpoints[party_id] = service_endpoint(party, "runner")

    normalized = {
        "contractVersion": "topic4.kuscia.runtime-context/v1",
        "servingId": serving_id,
        "domainId": domain_id,
        "partyId": party_map[domain_id],
        "clusterDefine": cluster,
        "allocatedPorts": ports,
        "engineEndpoints": engine_endpoints,
        "secretflowEndpoints": {"fed": fed_endpoints, "spu": spu_endpoints},
        "runnerEndpoints": runner_endpoints,
        "agent": agent_map[domain_id],
        "transport": "KUSCIA_CLUSTER_MTLS_ENVOY",
    }
    if has_secretflow:
        normalized["sflClusterConfig"] = {
            "parties": {
                party_id: {
                    # Kuscia's Cluster-scope Envoy route selects the remote
                    # domain from the HTTP Host header.  Supplying an explicit
                    # scheme makes brpc preserve the service hostname instead
                    # of replacing it with the resolved Lite Envoy address.
                    "address": "http://" + fed_endpoints[party_id],
                    **({"listen_addr": fed_endpoints[party_id]}
                       if party_id == normalized["partyId"] else {}),
                }
                for party_id in party_map.values()
            },
            "self_party": normalized["partyId"],
        }
        normalized["spuClusterDef"] = {
            "nodes": [
                {
                    "party": party_id,
                    "address": spu_endpoints[party_id],
                    **({"listen_address": spu_endpoints[party_id]}
                       if party_id == normalized["partyId"] else {}),
                }
                for party_id in party_map.values()
            ],
            "runtime_config": {"protocol": "SEMI2K", "field": "FM64"},
        }
    normalized["contextDigest"] = "sha256:" + hashlib.sha256(canonical(normalized)).hexdigest()
    return normalized


def write_private(path, value):
    target = Path(path)
    target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = target.with_suffix(".tmp")
    descriptor = os.open(str(temporary), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(value, stream, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(str(temporary), str(target))


def serve(source):
    context = validate_context(source)
    runtime_dir = Path(os.getenv("TOPIC4_KUSCIA_RUNTIME_DIR", "/var/run/topic4-kuscia"))
    context_path = runtime_dir / "context.json"
    endpoints_path = runtime_dir / "endpoints.json"
    write_private(context_path, context)
    write_private(endpoints_path, context["engineEndpoints"])
    agent = context["agent"]
    parsed = urlparse(agent["baseUrl"])
    environment = dict(os.environ)
    environment.update({
        "TOPIC4_KUSCIA_CONTEXT_FILE": str(context_path),
        "TOPIC4_KUSCIA_DEPLOYMENT_ID": context["servingId"],
        "TOPIC4_KUSCIA_TRANSPORT": context["transport"],
        "TOPIC4_ENDPOINT_MAP": str(endpoints_path),
        "TOPIC4_PARTY_ID": context["partyId"],
        "TOPIC4_BIND": "0.0.0.0",
        "TOPIC4_PORT": str(next(
            item["port"] for item in context["allocatedPorts"]["ports"]
            if item["name"] == "runner")),
        "TOPIC4_AGENT_BASE_URL": agent["baseUrl"].rstrip("/"),
        "TOPIC4_AGENT_ALLOWED_HOSTS": parsed.hostname,
        "TOPIC4_AGENT_ALLOWED_PATH_PREFIXES": ",".join(
            agent.get("allowedPathPrefixes", ["/dataset/", "/data/"])),
    })
    runner = os.getenv("TOPIC4_RUNNER_EXECUTABLE", "/opt/topic4/bin/topic4_privacy_runner.py")
    os.execve(sys.executable, [sys.executable, runner], environment)


def check(source):
    context = json.loads(Path(source).read_text(encoding="utf-8"))
    supplied = context.pop("contextDigest", None)
    actual = "sha256:" + hashlib.sha256(canonical(context)).hexdigest()
    if supplied != actual:
        raise ValueError("validated Kuscia context digest mismatch")
    if context.get("transport") != "KUSCIA_CLUSTER_MTLS_ENVOY":
        raise ValueError("runtime is not using Kuscia Cluster endpoints")
    if os.getenv("TOPIC4_KUSCIA_DEPLOYMENT_ID") != context.get("servingId"):
        raise ValueError("Kuscia deployment identity is absent")
    launcher = Path("/opt/psi/main")
    if launcher.is_file():
        completed = subprocess.run(
            [str(launcher), "--version"], stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15, check=False)
        if completed.returncode != 0:
            raise ValueError("pinned PSI launcher version check failed")
    print(json.dumps({
        "status": "ok", "servingId": context["servingId"],
        "domainId": context["domainId"], "contextDigest": supplied,
    }, sort_keys=True))


def main():
    if len(sys.argv) != 3 or sys.argv[1] not in ("serve", "check"):
        print("usage: kuscia-runtime-entry.py serve|check CONTEXT", file=sys.stderr)
        return 64
    try:
        if sys.argv[1] == "serve":
            serve(sys.argv[2])
        else:
            check(sys.argv[2])
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print("Kuscia runtime context rejected: %s" % exc, file=sys.stderr)
        return 69
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
