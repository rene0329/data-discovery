#!/usr/bin/env bash
set -euo pipefail

# Kuscia keeps a Gateway CR for a stopped Master pod alive for three minutes.
# During that window a new Lite handshake waits for the stopped instance to
# consume the new route token and eventually times out.  Remove only Gateway
# objects whose same-named pod no longer exists in the outer Master namespace.

readonly master_pod_ref="${1:?pass the current outer Master pod name or pod/name}"
readonly master_domain="${2:-topic4-master}"
readonly outer_namespace="${KUSCIA_MASTER_NAMESPACE:-kuscia-master}"

dns_name_re='^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$'

case "$master_pod_ref" in
  pod/*) master_pod_name="${master_pod_ref#pod/}" ;;
  pods/*) master_pod_name="${master_pod_ref#pods/}" ;;
  */*)
    echo "unsupported Master pod reference: $master_pod_ref" >&2
    exit 2
    ;;
  *) master_pod_name="$master_pod_ref" ;;
esac

for value in "$master_pod_name" "$master_domain" "$outer_namespace"; do
  if [[ ${#value} -gt 253 || ! "$value" =~ $dns_name_re ]]; then
    echo "invalid Kubernetes name: $value" >&2
    exit 2
  fi
done

line_in_list() {
  local needle="$1" values="$2" value
  while IFS= read -r value; do
    [[ "$value" == "$needle" ]] && return 0
  done <<< "$values"
  return 1
}

outer_master_pods="$(kubectl -n "$outer_namespace" get pods \
  -l app=kuscia-master \
  --field-selector=status.phase=Running \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')"

# Fail closed if the selected Ready pod disappeared or the outer query returned
# an unexpected result.  This prevents a bad lookup from deleting every Gateway.
if ! line_in_list "$master_pod_name" "$outer_master_pods"; then
  echo "current Master pod is absent from the outer pod list: $master_pod_name" >&2
  exit 1
fi

inner_gateways="$(kubectl -n "$outer_namespace" exec "$master_pod_ref" -- \
  kubectl -n "$master_domain" get gateways.kuscia.secretflow \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')"

while IFS= read -r gateway; do
  [[ -z "$gateway" ]] && continue
  if [[ ${#gateway} -gt 253 || ! "$gateway" =~ $dns_name_re ]]; then
    echo "invalid Gateway name returned by inner Kubernetes: $gateway" >&2
    exit 1
  fi

  # Keep the selected Ready Master explicitly, and also keep any other Gateway
  # that still has a corresponding outer pod (for example during a rollout).
  [[ "$gateway" == "$master_pod_name" ]] && continue
  line_in_list "$gateway" "$outer_master_pods" && continue

  echo "Deleting stale Kuscia Master Gateway: $gateway" >&2
  kubectl -n "$outer_namespace" exec "$master_pod_ref" -- \
    kubectl -n "$master_domain" delete gateways.kuscia.secretflow "$gateway" \
    --ignore-not-found=true --wait=true
done <<< "$inner_gateways"
