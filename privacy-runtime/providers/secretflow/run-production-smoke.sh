#!/usr/bin/env bash
set -euo pipefail

image="${1:-ghcr.io/rene0329/data-discovery-practice-server:privacy-secretflow-lite-1.11.0b1-amd64@sha256:f0033a79e71f1f76656230d0f3cd31cbddc8d3bdcdb39fe2fd2afc8b000ec178}"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
work_root="$(mktemp -d "${TMPDIR:-/tmp}/topic4-secretflow-smoke.XXXXXX")"
network="topic4-secretflow-smoke-$$"
container_a="topic4-secretflow-smoke-a-$$"
container_b="topic4-secretflow-smoke-b-$$"

cleanup() {
  docker rm -f "$container_a" "$container_b" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$work_root"
}
trap cleanup EXIT INT TERM

mkdir -p "$work_root/a" "$work_root/b"

printf '%s\n' \
  'value' \
  '10' \
  '20' >"$work_root/a/he.csv"
printf '%s\n' \
  'value' \
  '3' \
  '4' >"$work_root/b/he.csv"

printf '%s\n' \
  'id,x1,label' \
  'u1,1,1' \
  'u2,2,1' \
  'u3,3,0' \
  'u4,0,1' \
  'u5,1,1' \
  'u6,2,0' \
  'u7,3,1' \
  'u8,0,1' \
  'u9,1,0' \
  'u10,2,1' \
  'u11,3,1' \
  'u12,0,0' >"$work_root/a/vfl.csv"
printf '%s\n' \
  'id,x2' \
  'u12,1' \
  'u11,3' \
  'u10,0' \
  'u9,2' \
  'u8,4' \
  'u7,1' \
  'u6,3' \
  'u5,0' \
  'u4,2' \
  'u3,4' \
  'u2,1' \
  'u1,3' >"$work_root/b/vfl.csv"

docker network create "$network" >/dev/null
docker run -d --name "$container_a" --hostname smoke-a --network "$network" \
  --shm-size 1g \
  -e TOPIC4_SMOKE_PARTY=A \
  -v "$work_root/a:/work" \
  -v "$script_dir/smoke-secretflow-1.11.py:/opt/topic4/smoke.py:ro" \
  --entrypoint python3 "$image" /opt/topic4/smoke.py >/dev/null
docker run -d --name "$container_b" --hostname smoke-b --network "$network" \
  --shm-size 1g \
  -e TOPIC4_SMOKE_PARTY=B \
  -v "$work_root/b:/work" \
  -v "$script_dir/smoke-secretflow-1.11.py:/opt/topic4/smoke.py:ro" \
  --entrypoint python3 "$image" /opt/topic4/smoke.py >/dev/null

deadline=$((SECONDS + 900))
while :; do
  running_a="$(docker inspect --format '{{.State.Running}}' "$container_a")"
  running_b="$(docker inspect --format '{{.State.Running}}' "$container_b")"
  if [[ "$running_a" == false && "$running_b" == false ]]; then
    break
  fi
  if [[ "$running_a" == false ]]; then
    status_a="$(docker inspect --format '{{.State.ExitCode}}' "$container_a")"
    if [[ "$status_a" != 0 ]]; then
      docker kill "$container_b" >/dev/null 2>&1 || true
      break
    fi
  fi
  if [[ "$running_b" == false ]]; then
    status_b="$(docker inspect --format '{{.State.ExitCode}}' "$container_b")"
    if [[ "$status_b" != 0 ]]; then
      docker kill "$container_a" >/dev/null 2>&1 || true
      break
    fi
  fi
  if ((SECONDS >= deadline)); then
    docker kill "$container_a" "$container_b" >/dev/null 2>&1 || true
    break
  fi
  sleep 2
done

status_a="$(docker inspect --format '{{.State.ExitCode}}' "$container_a")"
status_b="$(docker inspect --format '{{.State.ExitCode}}' "$container_b")"

docker logs "$container_a" >&2 || true
docker logs "$container_b" >&2 || true

if [[ "$status_a" != 0 || "$status_b" != 0 ]]; then
  printf 'SecretFlow production smoke failed: A=%s B=%s\n' "$status_a" "$status_b" >&2
  exit 1
fi
test -s "$work_root/a/smoke-ok.json"
test -s "$work_root/b/smoke-ok.json"
python3 - "$work_root/a/smoke-ok.json" "$work_root/b/smoke-ok.json" <<'PY'
import json
import sys

for path in sys.argv[1:]:
    with open(path, encoding="utf-8") as stream:
        value = json.load(stream)
    assert value["secretflowVersion"] == "1.11.0b1"
    assert value["heuScheme"] == "Paillier-2048"
    assert value["psiProtocol"] == "RR22_FAST_PSI_2PC"
    assert value["intersectionCount"] == 12
    assert value["modelShardSha256"].startswith("sha256:")
print("secretflow-1.11 production HEU/RR22/SecureBoost smoke: ok")
PY
