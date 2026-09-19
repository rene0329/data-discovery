#!/usr/bin/env bash
set -euo pipefail

# Register real AppImages/KusciaDeployments for APSI, the combined SecretFlow
# provider and SFL, then deploy isolated control-plane gateways. APSI and
# SecretFlow intentionally use the same privacy-psi image with different fixed
# provider catalogs.

readonly here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly source_namespace="${SOURCE_SECRET_NAMESPACE:-topic4-1}"
readonly source_secret="${SOURCE_SECRET_NAME:-topic4-acceptance-secrets}"
readonly timeout="${KUSCIA_PROVIDER_TIMEOUT:-20m}"

PSI_IMAGE="${PRIVACY_PSI_IMAGE:-${SECRETFLOW_IMAGE:-${APSI_IMAGE:-}}}"
PSI_IMAGE_DIGEST="${PRIVACY_PSI_IMAGE_DIGEST:-${SECRETFLOW_IMAGE_DIGEST:-${APSI_IMAGE_DIGEST:-}}}"
PSI_IMAGE_ID="${PRIVACY_PSI_IMAGE_ID:-${SECRETFLOW_IMAGE_ID:-${APSI_IMAGE_ID:-}}}"
export PSI_IMAGE PSI_IMAGE_DIGEST PSI_IMAGE_ID

for name in PSI_IMAGE PSI_IMAGE_DIGEST PSI_IMAGE_ID SFL_IMAGE SFL_IMAGE_DIGEST SFL_IMAGE_ID NODE_A NODE_B NODE_C AGENT_URL_A AGENT_URL_B \
  AGENT_URL_C AGENT_NODE_A AGENT_NODE_B AGENT_NODE_C; do
  [[ -n "${!name:-}" ]] || { echo "set $name" >&2; exit 2; }
done
for name in PSI_IMAGE SFL_IMAGE; do
  [[ "${!name}" =~ :v-[0-9a-f]{40}$ ]] || {
    echo "$name must use the immutable v-<full-git-sha> tag" >&2; exit 2;
  }
done
for name in PSI_IMAGE_DIGEST PSI_IMAGE_ID SFL_IMAGE_DIGEST SFL_IMAGE_ID; do
  [[ "${!name}" =~ ^sha256:[0-9a-fA-F]{64}$ ]] || {
    echo "$name must be sha256:<64 hex>" >&2; exit 2;
  }
done
if [[ -n "${APSI_IMAGE:-}" && "$APSI_IMAGE" != "$PSI_IMAGE" ]] \
  || [[ -n "${SECRETFLOW_IMAGE:-}" && "$SECRETFLOW_IMAGE" != "$PSI_IMAGE" ]] \
  || [[ -n "${APSI_IMAGE_DIGEST:-}" && "$APSI_IMAGE_DIGEST" != "$PSI_IMAGE_DIGEST" ]] \
  || [[ -n "${SECRETFLOW_IMAGE_DIGEST:-}" && "$SECRETFLOW_IMAGE_DIGEST" != "$PSI_IMAGE_DIGEST" ]] \
  || [[ -n "${APSI_IMAGE_ID:-}" && "$APSI_IMAGE_ID" != "$PSI_IMAGE_ID" ]] \
  || [[ -n "${SECRETFLOW_IMAGE_ID:-}" && "$SECRETFLOW_IMAGE_ID" != "$PSI_IMAGE_ID" ]]; then
  echo "APSI and SecretFlow must use the one immutable privacy-psi image" >&2
  exit 2
fi
export AGENT_URL_A AGENT_URL_B AGENT_URL_C AGENT_NODE_A AGENT_NODE_B AGENT_NODE_C

work="$(mktemp -d "${TMPDIR:-/tmp}/topic4-kuscia-providers.XXXXXX")"
trap 'find "$work" -type f -exec sh -c '\''for f do : > "$f"; done'\'' sh {} +; rmdir "$work" 2>/dev/null || true' EXIT
umask 077

apply_file_secret() {
  local namespace="$1" name="$2" token_file="$3"
  kubectl -n "$namespace" create secret generic "$name" \
    --from-file=bearer-token="$token_file" --dry-run=client -o yaml | kubectl apply -f -
}

provision_provider_auth() {
  local provider="$1" source_key="privacy-${provider}-token"
  local gateway_file="$work/${provider}-gateway-token"
  local party lower namespace runner_file
  kubectl -n "$source_namespace" get secret "$source_secret" \
    -o "go-template={{index .data \"${source_key}\"}}" | base64 --decode > "$gateway_file"
  test -s "$gateway_file"
  apply_file_secret kuscia-master "privacy-${provider}-gateway-auth" "$gateway_file"

  for party in A B C; do
    lower="$(printf '%s' "$party" | tr '[:upper:]' '[:lower:]')"
    namespace="kuscia-${lower}"
    runner_file="$work/${provider}-runner-${lower}-token"
    openssl rand -hex 32 > "$runner_file"
    test -s "$runner_file"
    if cmp -s "$gateway_file" "$runner_file"; then
      echo "generated ${provider}/${party} runner credential equals gateway credential" >&2
      exit 1
    fi
    # The AppImage uses the same Secret object name in every namespace, while
    # each Secret value is independently generated.  The master copy is only
    # projected into the gateway's fixed downstream-token directory.
    apply_file_secret "$namespace" "privacy-${provider}-runner-auth" "$runner_file"
    apply_file_secret kuscia-master "privacy-${provider}-runner-${lower}-auth" "$runner_file"
  done
  if cmp -s "$work/${provider}-runner-a-token" "$work/${provider}-runner-b-token" \
    || cmp -s "$work/${provider}-runner-a-token" "$work/${provider}-runner-c-token" \
    || cmp -s "$work/${provider}-runner-b-token" "$work/${provider}-runner-c-token"; then
    echo "generated duplicate ${provider} runner credentials" >&2
    exit 1
  fi
}

for provider in apsi secretflow sfl; do
  provision_provider_auth "$provider"
done

generate_kd() {
  local provider="$1" image="$2" digest="$3" image_id="$4" output="$5"
  python3 "$here/generate_kuscia_provider.py" \
    --provider "$provider" --image "$image" --image-digest "$digest" --image-id "$image_id" \
    --node-a "$NODE_A" --node-b "$NODE_B" --node-c "$NODE_C" \
    --agent-url-a "$AGENT_URL_A" --agent-url-b "$AGENT_URL_B" --agent-url-c "$AGENT_URL_C" \
    --agent-node-a "$AGENT_NODE_A" --agent-node-b "$AGENT_NODE_B" --agent-node-c "$AGENT_NODE_C" \
    --output "$output"
}

generate_kd apsi "$PSI_IMAGE" "$PSI_IMAGE_DIGEST" "$PSI_IMAGE_ID" "$work/apsi-kd.json"
generate_kd secretflow "$PSI_IMAGE" "$PSI_IMAGE_DIGEST" "$PSI_IMAGE_ID" "$work/secretflow-kd.json"
generate_kd sfl "$SFL_IMAGE" "$SFL_IMAGE_DIGEST" "$SFL_IMAGE_ID" "$work/sfl-kd.json"

for provider in apsi secretflow sfl; do
  kubectl -n kuscia-master exec -i deploy/kuscia-master -- kubectl apply -f - \
    < "$work/${provider}-kd.json"
done

# AppImage v1.2 exposes config-template mounts but no arbitrary Pod volumes.
# Patch the concrete Deployment in each Lite domain after Kuscia materializes
# it. The v1.2 deployment reconciler preserves operator-added pod fields while
# continuing to own image, resources, affinity and input configuration. RunK
# passes the hostPath through to the outer pod on the fixed data node, so job
# metadata, result references, model shards and approved SFL smoke evidence
# survive Pod recreation inside their owning domain. Raw staged inputs use an
# emptyDirs and are deleted by the runner on every terminal/recovery path.
patch_party_storage() {
  local provider="$1" domain="$2"
  local deployment count image state_path patch
  # Lite mode uses the outer cluster ServiceAccount for its RunK runtime, so a
  # kubectl invocation inside the Lite Pod cannot read the logical domain
  # namespace.  The master embedded cluster owns these Deployments and is the
  # only supported administrative path for patching them.
  timeout "$timeout" bash -c '
    set -euo pipefail
    while true; do
      deployments="$(kubectl -n kuscia-master exec deploy/kuscia-master -- \
        kubectl -n "$1" get deployment \
        -l "kuscia.secretflow/kd-name=topic4-privacy-$2-parties" \
        -o name \
        2>/dev/null || true)"
      count="$(printf "%s\n" "$deployments" | sed "/^$/d" | wc -l | tr -d " ")"
      [[ "$count" == 1 ]] && exit 0
      sleep 2
    done
  ' _ "$domain" "$provider" || {
    echo "timed out waiting for ${provider} deployment in ${domain}" >&2
    exit 1
  }
  deployment="$(kubectl -n kuscia-master exec deploy/kuscia-master -- \
    kubectl -n "$domain" get deployment \
    -l "kuscia.secretflow/kd-name=topic4-privacy-${provider}-parties" \
    -o 'jsonpath={range .items[*]}{.metadata.name}{"\n"}{end}')"
  count="$(printf '%s\n' "$deployment" | sed '/^$/d' | wc -l | tr -d ' ')"
  [[ "$count" == 1 ]] || {
    echo "expected exactly one ${provider} deployment in ${domain}, found ${count}" >&2
    exit 1
  }
  deployment="$(printf '%s\n' "$deployment" | sed -n '1p')"
  image="$(kubectl -n kuscia-master exec deploy/kuscia-master -- \
    kubectl -n "$domain" get deployment "$deployment" \
    -o 'jsonpath={.spec.template.spec.containers[?(@.name=="runner")].image}')"
  [[ "$image" =~ :v-[0-9a-f]{40}$ ]] || {
    echo "${provider}/${domain} runner image is not an immutable Topic4 tag" >&2
    exit 1
  }
  state_path="/data/topic4-privacy/party-state/${domain}/${provider}"
  # RunK projects the hostPath through a bind-mounted volume whose mount root
  # cannot be chmod'ed from the nested Pod. Keep the mount root owned by the
  # runner and make every directory that can contain protocol state private.
  patch="$(cat <<EOF
{"spec":{"strategy":{"type":"Recreate"},"template":{"metadata":{"annotations":{"topic4.openai.com/party-state":"${state_path}"}},"spec":{"initContainers":[{"name":"prepare-party-state","image":"${image}","imagePullPolicy":"IfNotPresent","command":["sh","-c","mkdir -p /state/jobs /state/models /state/smoke && chmod 0700 /state/jobs /state/models /state/smoke && chown -R 10001:10001 /state/jobs /state/models /state/smoke && chown 10001:10001 /state"],"securityContext":{"runAsUser":0,"runAsGroup":0,"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"],"add":["CHOWN","DAC_OVERRIDE","FOWNER"]}},"volumeMounts":[{"name":"party-state","mountPath":"/state"}]}],"containers":[{"name":"runner","volumeMounts":[{"name":"party-state","mountPath":"/var/lib/topic4-privacy"},{"name":"staged-inputs","mountPath":"/var/run/topic4-inputs"},{"name":"private-work","mountPath":"/var/run/topic4-work"}]}],"volumes":[{"name":"party-state","hostPath":{"path":"${state_path}","type":"DirectoryOrCreate"}},{"name":"staged-inputs","emptyDir":{}},{"name":"private-work","emptyDir":{}}]}}}}
EOF
)"
  kubectl -n kuscia-master exec deploy/kuscia-master -- \
    kubectl -n "$domain" patch deployment "$deployment" --type=strategic -p "$patch"
  # Secret-backed environment credentials are captured at process start.  Restart on
  # every deployment so a newly rotated party token is active before the
  # control-plane gateway resumes dispatching requests.
  kubectl -n kuscia-master exec deploy/kuscia-master -- \
    kubectl -n "$domain" rollout restart "deployment/${deployment}"
  kubectl -n kuscia-master exec deploy/kuscia-master -- \
    kubectl -n "$domain" rollout status "deployment/${deployment}" --timeout="$timeout"
}

for provider in apsi secretflow sfl; do
  patch_party_storage "$provider" domain-a
  patch_party_storage "$provider" domain-b
  patch_party_storage "$provider" domain-c
done

for provider in apsi secretflow sfl; do
  kubectl -n kuscia-master exec deploy/kuscia-master -- \
    kubectl -n cross-domain wait \
    --for='jsonpath={.status.phase}=Available' \
    "kusciadeployment/topic4-privacy-${provider}-parties" --timeout="$timeout"
done

# RunK projects Pods, ConfigMaps and Secrets into the outer Kubernetes
# namespace, but it does not project the embedded-cluster Service created by
# KusciaDeployment. Publish a fixed ClusterIP Service for gateway-to-runner
# traffic. The selector is a controller-owned label copied onto the RunK Pod;
# targetPort resolves the dynamically allocated named AppImage port.
ensure_outer_runner_service() {
  local provider="$1" namespace="$2"
  cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: Service
metadata:
  name: topic4-${provider}-runner
  namespace: ${namespace}
  labels:
    topic4.openai.com/privacy-provider: ${provider}
spec:
  type: ClusterIP
  selector:
    kuscia.secretflow/kd-name: topic4-privacy-${provider}-parties
  ports:
    - name: runner
      port: 8080
      targetPort: runner
      protocol: TCP
EOF
  kubectl -n "$namespace" get endpoints "topic4-${provider}-runner" \
    -o jsonpath='{.subsets[0].addresses[0].ip}' | grep -Eq '^[0-9a-fA-F:.]+$'
}

for provider in apsi secretflow sfl; do
  for namespace in kuscia-a kuscia-b kuscia-c; do
    ensure_outer_runner_service "$provider" "$namespace"
  done
done

service_port() {
  local provider="$1" namespace="$2"
  kubectl -n "$namespace" get service "topic4-${provider}-runner" \
    -o 'jsonpath={.spec.ports[?(@.name=="runner")].port}'
}

write_endpoint_map() {
  local provider="$1" output="$2"
  PROVIDER="$provider" OUTPUT="$output" python3 - <<'PY'
import json, os, subprocess
provider = os.environ["PROVIDER"]
items = {}
for party, namespace in (("A", "kuscia-a"), ("B", "kuscia-b"), ("C", "kuscia-c")):
    port = subprocess.check_output([
        "kubectl", "-n", namespace, "get", "service", "topic4-%s-runner" % provider,
        "-o", 'jsonpath={.spec.ports[?(@.name=="runner")].port}',
    ], universal_newlines=True).strip()
    if not port.isdigit():
        raise SystemExit("runner Service port is unavailable for %s/%s" % (provider, party))
    items[party] = {
        "runnerUrl": "http://topic4-%s-runner.%s.svc.cluster.local:%s" % (
            provider, namespace, port),
        "runnerTokenFile": "/run/secrets/topic4-party-tokens/%s" % party,
        "agentBaseUrl": os.environ["AGENT_URL_" + party].rstrip("/"),
        "nodeName": os.environ["AGENT_NODE_" + party],
    }
with open(os.environ["OUTPUT"], "w", encoding="utf-8") as stream:
    json.dump(items, stream, sort_keys=True, separators=(",", ":"))
PY
  kubectl -n kuscia-master create configmap "topic4-privacy-${provider}-gateway-config" \
    --from-file=runner-endpoints.json="$output" --dry-run=client -o yaml | kubectl apply -f -
}

for provider in apsi secretflow sfl; do
  write_endpoint_map "$provider" "$work/${provider}-endpoints.json"
done

python3 "$here/generate_provider_gateways.py" \
  --apsi-image "$PSI_IMAGE" --apsi-digest "$PSI_IMAGE_DIGEST" \
  --secretflow-image "$PSI_IMAGE" --secretflow-digest "$PSI_IMAGE_DIGEST" \
  --sfl-image "$SFL_IMAGE" --sfl-digest "$SFL_IMAGE_DIGEST" \
  --output "$work/gateways.json"
kubectl apply -f "$work/gateways.json"

for provider in apsi secretflow sfl; do
  kubectl -n kuscia-master rollout restart \
    "deployment/topic4-privacy-${provider}-gateway"
  kubectl -n kuscia-master rollout status \
    "deployment/topic4-privacy-${provider}-gateway" --timeout="$timeout"
done

# These legacy Secrets coupled gateway ingress and all party runners.  Remove
# them only after every replacement workload has completed its rollout.
for provider in apsi secretflow sfl; do
  for namespace in kuscia-master kuscia-a kuscia-b kuscia-c; do
    kubectl -n "$namespace" delete secret "privacy-${provider}-auth" --ignore-not-found
  done
done

# SFL party pods are live under a real KusciaDeployment. Its provider health
# intentionally remains DOWN until a real three-domain smoke record is pinned.
kubectl -n kuscia-master get services \
  topic4-privacy-apsi-gateway topic4-privacy-secretflow-gateway topic4-privacy-sfl-gateway

for provider in apsi secretflow sfl; do
  if [[ "$provider" == "sfl" ]]; then expected="$SFL_IMAGE_DIGEST"; else expected="$PSI_IMAGE_DIGEST"; fi
  for namespace in kuscia-a kuscia-b kuscia-c; do
    image_id="$(kubectl -n "$namespace" get pod \
      -l "kuscia.secretflow/kd-name=topic4-privacy-${provider}-parties" \
      -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')"
    [[ "$image_id" == *"@${expected}" ]] || {
      echo "$provider/$namespace imageID does not match $expected" >&2; exit 1;
    }
  done
done

kubectl -n kuscia-master exec deploy/kuscia-master -- \
  kubectl -n cross-domain get kusciadeployments \
  topic4-privacy-apsi-parties topic4-privacy-secretflow-parties topic4-privacy-sfl-parties \
  -o custom-columns=NAME:.metadata.name,PHASE:.status.phase,AVAILABLE:.status.availableParties
