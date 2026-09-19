#!/usr/bin/env bash

set -euo pipefail

OUTPUT_DIR=${TOPIC4_RUNTIME_OBSERVATION_OUT:-"$PWD/privacy-runtime-observation-$(date -u '+%Y%m%dT%H%M%SZ')"}
KUBECTL_CONTEXT=${TOPIC4_KUBECTL_CONTEXT:-}

die() {
  printf 'collect-runtime-observations: %s\n' "$*" >&2
  exit 1
}

command -v kubectl >/dev/null 2>&1 || die "kubectl is required"
command -v jq >/dev/null 2>&1 || die "jq is required"

mkdir -p -- "$OUTPUT_DIR/staged-input-inventory"
chmod 700 "$OUTPUT_DIR"

KUBECTL=(kubectl)
if [ -n "$KUBECTL_CONTEXT" ]; then
  KUBECTL+=(--context "$KUBECTL_CONTEXT")
fi

"${KUBECTL[@]}" get pods -A -o json | jq '{apiVersion:.apiVersion,kind:.kind,
  items:[.items[]
    | select((.metadata.namespace | startswith("kuscia-"))
      and (((.metadata.labels.app // "") | startswith("topic4-"))
        or ((.metadata.labels["kuscia.secretflow/kd-name"] // "")
          | startswith("topic4-privacy-"))
        or ((.metadata.name // "") | startswith("topic4-"))))]}' \
  >"$OUTPUT_DIR/privacy-pods.json"

jq '[.items[] | {
    namespace:.metadata.namespace,pod:.metadata.name,nodeName:.spec.nodeName,
    phase:.status.phase,restartCounts:[.status.containerStatuses[]? | {name,restartCount}],
    containers:[.status.containerStatuses[]? | {
      name,image,imageID,ready,restartCount,state,lastState}]
  }]' "$OUTPUT_DIR/privacy-pods.json" >"$OUTPUT_DIR/image-ids-and-restarts.json"

INVENTORY_NDJSON="$OUTPUT_DIR/staged-input-inventory/index.ndjson"
: >"$INVENTORY_NDJSON"
jq -r '.items[]
  | .metadata.namespace as $namespace | .metadata.name as $pod
  | .spec.containers[]
  | select(any(.env[]?; .name == "TOPIC4_INPUT_DIR"))
  | [$namespace,$pod,.name] | @tsv' "$OUTPUT_DIR/privacy-pods.json" |
while IFS=$'\t' read -r namespace pod container; do
  [ -n "$namespace" ] && [ -n "$pod" ] && [ -n "$container" ] || continue
  artifact="$OUTPUT_DIR/staged-input-inventory/$namespace--$pod--$container.txt"
  exit_code=0
  "${KUBECTL[@]}" exec -n "$namespace" "$pod" -c "$container" -- sh -c \
    'root=${TOPIC4_INPUT_DIR:-/var/run/topic4-inputs}; if [ -d "$root" ]; then find "$root" -mindepth 1 -maxdepth 3 -type f -print; fi' \
    >"$artifact" 2>&1 || exit_code=$?
  jq -cn --arg namespace "$namespace" --arg pod "$pod" --arg container "$container" \
    --arg artifact "staged-input-inventory/$(basename -- "$artifact")" --argjson exitCode "$exit_code" \
    '{namespace:$namespace,pod:$pod,container:$container,artifact:$artifact,exitCode:$exitCode}' \
    >>"$INVENTORY_NDJSON"
done

jq -s '.' "$INVENTORY_NDJSON" >"$OUTPUT_DIR/staged-input-inventory/index.json"
jq -n --arg observedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  --arg context "$KUBECTL_CONTEXT" \
  --slurpfile pods "$OUTPUT_DIR/image-ids-and-restarts.json" \
  --slurpfile inventory "$OUTPUT_DIR/staged-input-inventory/index.json" \
  '{contractVersion:"topic4.privacy.runtime-observation/v1",observedAt:$observedAt,
    kubectlContext:(if ($context|length)>0 then $context else null end),
    podMetadata:$pods[0],stagedInputInventories:$inventory[0],
    fileContentsRead:false,judgment:"reserved for manual review"}' \
  >"$OUTPUT_DIR/observation.json"

printf '%s\n' "$OUTPUT_DIR"
