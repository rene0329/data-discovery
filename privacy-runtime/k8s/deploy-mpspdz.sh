#!/usr/bin/env bash
set -euo pipefail

readonly here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${MPSPDZ_IMAGE:?set the immutable v-full-git-sha image reference}"
: "${MPSPDZ_IMAGE_DIGEST:?set the pulled image content digest (sha256:...)}"
: "${NODE_A:?set NODE_A to party A data node}"
: "${NODE_B:?set NODE_B to party B data node}"
: "${NODE_C:?set NODE_C to party C data node}"
: "${AGENT_URL_A:?set fixed party A Agent URL}"
: "${AGENT_URL_B:?set fixed party B Agent URL}"
: "${AGENT_URL_C:?set fixed party C Agent URL}"
: "${AGENT_NODE_A:?set fixed party A Agent nodeName}"
: "${AGENT_NODE_B:?set fixed party B Agent nodeName}"
: "${AGENT_NODE_C:?set fixed party C Agent nodeName}"

[[ "$MPSPDZ_IMAGE" =~ :v-[0-9a-f]{40}$ ]] || {
  echo "MPSPDZ_IMAGE must use the immutable full Git SHA tag" >&2; exit 2;
}
[[ "$MPSPDZ_IMAGE_DIGEST" =~ ^sha256:[0-9a-fA-F]{64}$ ]] || {
  echo "MPSPDZ_IMAGE_DIGEST must be sha256:<64 hex>" >&2; exit 2;
}
export AGENT_URL_A AGENT_URL_B AGENT_URL_C AGENT_NODE_A AGENT_NODE_B AGENT_NODE_C

source_namespace="${SOURCE_SECRET_NAMESPACE:-topic4-1}"
source_secret="${SOURCE_SECRET_NAME:-topic4-acceptance-secrets}"
work="$(mktemp -d "${TMPDIR:-/tmp}/topic4-mpspdz-deploy.XXXXXX")"
trap 'find "$work" -type f -exec sh -c '"'"'for f do : > "$f"; done'"'"' sh {} +; rmdir "$work" 2>/dev/null || true' EXIT
umask 077

apply_file_secret() {
  local namespace="$1" name="$2" token_file="$3"
  kubectl -n "$namespace" create secret generic "$name" \
    --from-file=bearer-token="$token_file" --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$namespace" annotate secret "$name" \
    kubectl.kubernetes.io/last-applied-configuration- >/dev/null
}

provision_gateway_auth() {
  kubectl -n "$source_namespace" get secret "$source_secret" \
    -o go-template='{{index .data "privacy-mpspdz-token"}}' | \
    base64 --decode > "$work/gateway-token"
  test -s "$work/gateway-token"
  apply_file_secret kuscia-master privacy-mpspdz-gateway-auth "$work/gateway-token"
}

provision_runner_auth() {
  local letter="$1" namespace="$2" lower runner_file
  lower="$(printf '%s' "$letter" | tr '[:upper:]' '[:lower:]')"
  runner_file="$work/runner-${lower}-token"
  openssl rand -hex 32 > "$runner_file"
  test -s "$runner_file"
  if cmp -s "$work/gateway-token" "$runner_file"; then
    echo "generated MP-SPDZ ${letter} runner credential equals gateway credential" >&2
    exit 1
  fi
  apply_file_secret "$namespace" privacy-mpspdz-runner-auth "$runner_file"
  apply_file_secret kuscia-master "privacy-mpspdz-runner-${lower}-auth" "$runner_file"
}

provision_gateway_auth

for index in 0 1 2; do
  openssl req -newkey rsa:3072 -nodes -x509 -sha256 -days 365 \
    -out "$work/P${index}.pem" -keyout "$work/P${index}.key" \
    -subj "/CN=P${index}" 2>/dev/null
done

for letter in A B C; do
  lower="$(printf '%s' "$letter" | tr '[:upper:]' '[:lower:]')"
  namespace="kuscia-$lower"
  index="$(( $(printf '%d' "'$letter") - 65 ))"
  provision_runner_auth "$letter" "$namespace"
  kubectl -n "$namespace" create secret generic topic4-mpspdz-tls \
    --from-file=P0.pem="$work/P0.pem" \
    --from-file=P1.pem="$work/P1.pem" \
    --from-file=P2.pem="$work/P2.pem" \
    --from-file="P${index}.key=$work/P${index}.key" \
    --dry-run=client -o yaml | kubectl apply -f -
  # Do not retain the generated private key a second time in apply metadata.
  kubectl -n "$namespace" annotate secret topic4-mpspdz-tls \
    kubectl.kubernetes.io/last-applied-configuration- >/dev/null
  case "$letter" in
    A)
      agent_url="$AGENT_URL_A"
      agent_node="$AGENT_NODE_A"
      ;;
    B)
      agent_url="$AGENT_URL_B"
      agent_node="$AGENT_NODE_B"
      ;;
    C)
      agent_url="$AGENT_URL_C"
      agent_node="$AGENT_NODE_C"
      ;;
  esac
  agent_host="$(python3 -c 'import sys,urllib.parse; print(urllib.parse.urlparse(sys.argv[1]).hostname or "")' "$agent_url")"
  test -n "$agent_host"
  kubectl -n "$namespace" create configmap topic4-privacy-party-config \
    --from-literal=agent-base-url="${agent_url%/}" \
    --from-literal=agent-allowed-hosts="$agent_host" \
    --from-literal=agent-path-prefixes="${AGENT_PATH_PREFIXES:-/dataset/,/data/}" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n "$namespace" create configmap topic4-privacy-mpspdz-image \
    --from-literal=image-digest="$MPSPDZ_IMAGE_DIGEST" \
    --dry-run=client -o yaml | kubectl apply -f -
done

if cmp -s "$work/runner-a-token" "$work/runner-b-token" \
  || cmp -s "$work/runner-a-token" "$work/runner-c-token" \
  || cmp -s "$work/runner-b-token" "$work/runner-c-token"; then
  echo "generated duplicate MP-SPDZ runner credentials" >&2
  exit 1
fi

kubectl -n kuscia-master create configmap topic4-privacy-mpspdz-image \
  --from-literal=image-digest="$MPSPDZ_IMAGE_DIGEST" \
  --dry-run=client -o yaml | kubectl apply -f -

python3 - "$work/runner-endpoints.json" <<'PY'
import json, os, sys
parties = {}
for party, namespace in (("A", "kuscia-a"), ("B", "kuscia-b"), ("C", "kuscia-c")):
    parties[party] = {
        "runnerUrl": "http://topic4-mpspdz.%s.svc.cluster.local:8080" % namespace,
        "runnerTokenFile": "/run/secrets/topic4-party-tokens/%s" % party,
        "agentBaseUrl": os.environ["AGENT_URL_" + party].rstrip("/"),
        "nodeName": os.environ["AGENT_NODE_" + party],
    }
with open(sys.argv[1], "w", encoding="utf-8") as stream:
    json.dump(parties, stream, sort_keys=True)
PY
kubectl -n kuscia-master create configmap topic4-privacy-mpspdz-gateway-config \
  --from-file=runner-endpoints.json="$work/runner-endpoints.json" \
  --dry-run=client -o yaml | kubectl apply -f -

export MPSPDZ_IMAGE MPSPDZ_IMAGE_DIGEST NODE_A NODE_B NODE_C
python3 - "$here/mpspdz.json.tpl" "$work/mpspdz.json" <<'PY'
import os, sys
value = open(sys.argv[1], encoding="utf-8").read()
for name in ("MPSPDZ_IMAGE", "MPSPDZ_IMAGE_DIGEST", "NODE_A", "NODE_B", "NODE_C"):
    value = value.replace("__%s__" % name, os.environ[name])
if "__" in value:
    raise SystemExit("unresolved manifest placeholder")
open(sys.argv[2], "w", encoding="utf-8").write(value)
PY
kubectl apply -f "$work/mpspdz.json"

for namespace in kuscia-a kuscia-b kuscia-c; do
  kubectl -n "$namespace" rollout restart deploy/topic4-mpspdz
  kubectl -n "$namespace" rollout status deploy/topic4-mpspdz --timeout=15m
done
kubectl -n kuscia-master rollout restart deploy/topic4-privacy-mpspdz-gateway
kubectl -n kuscia-master rollout status deploy/topic4-privacy-mpspdz-gateway --timeout=15m

# Delete the shared legacy credential only after every workload is running
# with the isolated gateway and party Secrets.
for namespace in kuscia-master kuscia-a kuscia-b kuscia-c; do
  kubectl -n "$namespace" delete secret privacy-mpspdz-auth --ignore-not-found
done

for namespace in kuscia-a kuscia-b kuscia-c; do
  image_id="$(kubectl -n "$namespace" get pod -l app=topic4-mpspdz \
    -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')"
  [[ "$image_id" == *"@$MPSPDZ_IMAGE_DIGEST" ]] || {
    echo "$namespace imageID does not match MPSPDZ_IMAGE_DIGEST" >&2; exit 1;
  }
done

kubectl -n kuscia-master get svc topic4-privacy-mpspdz-gateway
kubectl get endpoints -n kuscia-a topic4-mpspdz
kubectl get endpoints -n kuscia-b topic4-mpspdz
kubectl get endpoints -n kuscia-c topic4-mpspdz
