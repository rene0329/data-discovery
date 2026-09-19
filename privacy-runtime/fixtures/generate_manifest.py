#!/usr/bin/env python3
import csv
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent


def canonical(value):
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def digest(value):
    return "sha256:" + hashlib.sha256(value).hexdigest()


def column_type(relative, name):
    family = relative.parts[0]
    if name in {"id", "key"}:
        return "STRING"
    if family in {"psi", "psi-duplicate", "psi-empty", "pir"} and name == "value":
        return "STRING"
    return "INTEGER"


entries = []
for path in sorted(root.glob("*/*.csv")):
    relative_path = path.relative_to(root)
    relative = relative_path.as_posix()
    data = path.read_bytes()
    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.reader(stream)
        header = next(reader)
        row_count = sum(1 for _ in reader)
    schema = [
        {"name": name, "type": column_type(relative_path, name), "nullable": False}
        for name in header
    ]
    entries.append({
        "datasetId": "fixture:" + relative.removesuffix(".csv").replace("/", ":"),
        "datasetVersion": "fixture-v1",
        "partyId": path.stem.upper(),
        "path": relative,
        "bytes": len(data),
        "rows": row_count,
        "sha256": digest(data),
        "schema": schema,
        "schemaDigest": digest(canonical(schema)),
    })

entries_by_path = {item["path"]: item for item in entries}


def input_summary(party, path):
    item = entries_by_path[path]
    return {
        "partyId": party,
        "path": path,
        "bytes": item["bytes"],
        "rows": item["rows"],
        "sha256": item["sha256"],
        "schemaDigest": item["schemaDigest"],
    }


baseline_templates = [
    {
        "templateId": "secure-sum-3p-v1",
        "inputs": [input_summary(party, f"secure-sum/{party.lower()}.csv") for party in "ABC"],
        "expectedRaw": {"sum": [145]},
    },
    {
        "templateId": "private-stats-3p-v1",
        "inputs": [input_summary(party, f"private-stats/{party.lower()}.csv") for party in "ABC"],
        "expectedRaw": {
            "lanes": [
                {"field": "v1", "sum": 60, "count": 3, "mean": 20,
                 "min": 10, "max": 30, "variance": {"numerator": 200, "denominator": 3}},
                {"field": "v2", "sum": 12, "count": 3, "mean": 4,
                 "min": 2, "max": 6, "variance": {"numerator": 8, "denominator": 3}},
            ]
        },
    },
    {
        "templateId": "private-threshold-3p-v1",
        "inputs": [input_summary(party, f"secure-sum/{party.lower()}.csv") for party in "ABC"],
        "expectedRaw": {"inputSum": 145, "threshold": 100, "reached": True},
    },
    {
        "templateId": "psi-2p-v1",
        "inputs": [input_summary(party, f"psi/{party.lower()}.csv") for party in "AB"],
        "expectedRaw": {"keyColumn": "id", "receiver": "A", "intersection": ["u2", "u3"]},
    },
    {
        "templateId": "psi-3p-v1",
        "inputs": [input_summary(party, f"psi/{party.lower()}.csv") for party in "ABC"],
        "expectedRaw": {"keyColumn": "id", "receiver": "A", "intersection": ["u3"]},
    },
    {
        "templateId": "pir-keyword-2p-v1",
        "inputs": [input_summary("A", "pir/a.csv"), input_summary("B", "pir/b.csv")],
        "expectedRaw": {
            "queryColumn": "key", "valueColumn": "value",
            "queries": [{"key": "k2", "hit": True, "value": "beta"},
                        {"key": "k9", "hit": False, "value": None}],
        },
    },
    {
        "templateId": "he-paillier-2p-v1",
        "inputs": [input_summary(party, f"he/{party.lower()}.csv") for party in "AB"],
        "expectedRaw": {"operation": "ADD", "scale": 1, "value": [90]},
    },
    {
        "templateId": "hfl-fedavg-logreg-3p-v1",
        "inputs": [input_summary(party, f"hfl/{party.lower()}.csv") for party in "ABC"],
        "expectedRaw": {
            "algorithm": "FedAvg binary logistic regression", "epochs": 1,
            "learningRate": 0.05, "seed": 20260919,
            "featureColumns": ["x1", "x2"], "labelColumn": "label",
            "resultFields": ["model_reference", "metrics"],
        },
    },
    {
        "templateId": "vfl-secureboost-2p-v1",
        "inputs": [input_summary("A", "vfl/a.csv"), input_summary("B", "vfl/b.csv")],
        "expectedRaw": {
            "algorithm": "SecureBoost with HEU", "alignmentKey": "id",
            "alignedIds": ["u1", "u2", "u3"], "labelOwner": "A",
            "featureOwners": {"x1": "B", "x2": "A"}, "labelColumn": "label",
            "epochs": 1, "learningRate": 0.1, "seed": 20260919,
            "resultFields": ["model_reference", "metrics", "prediction_reference"],
        },
    },
]

manifest = {
    "contractVersion": "topic4.privacy.fixtures/v1",
    "registrationMode": "deployment-time-authorized-catalog-versions",
    "entries": entries,
    "knownResults": {
        "secure-sum-3p-v1": 145,
        "psi-2p-v1": ["u2", "u3"],
        "psi-3p-v1": ["u3"],
        "pir-keyword-2p-v1": {"k2": "beta", "k9": None},
        "he-paillier-2p-v1": [90],
    },
}
baseline = {
    "contractVersion": "topic4.privacy.raw-baseline/v1",
    "sourceManifest": "manifest.json",
    "templates": baseline_templates,
}

(root / "manifest.json").write_text(
    json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
(root / "baseline.json").write_text(
    json.dumps(baseline, indent=2, sort_keys=True) + "\n", encoding="utf-8")
