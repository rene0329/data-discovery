#!/usr/bin/env bash
set -euo pipefail

# Execute the one-time real A/B/C SFL smoke while normal provider health is
# intentionally DOWN, then pin each party's independently written record. This
# script requires cluster-operator access because approval is an explicit
# deployment action, not a runtime API operation.

readonly namespace="${KUSCIA_MASTER_NAMESPACE:-kuscia-master}"
readonly gateway="${SFL_GATEWAY_SERVICE:-topic4-privacy-sfl-gateway}"
readonly gateway_secret="${SFL_GATEWAY_SECRET:-privacy-sfl-gateway-auth}"
readonly source_namespace="${SOURCE_SECRET_NAMESPACE:-topic4-1}"
readonly source_secret="${SOURCE_SECRET_NAME:-topic4-acceptance-secrets}"
readonly mapping_file="${TOPIC4_FIXTURE_MAPPING_FILE:-privacy-fixture-datasets.json}"
readonly local_port="${TOPIC4_SFL_SMOKE_LOCAL_PORT:-18083}"
readonly poll_seconds="${TOPIC4_SFL_SMOKE_POLL_SECONDS:-3600}"
readonly poll_interval="${TOPIC4_SFL_SMOKE_POLL_INTERVAL_SECONDS:-5}"

die() { printf 'sfl-smoke: %s\n' "$*" >&2; exit 1; }
command -v kubectl >/dev/null || die "kubectl is required"
command -v curl >/dev/null || die "curl is required"
command -v jq >/dev/null || die "jq is required"
[[ "$local_port" =~ ^[0-9]+$ ]] || die "TOPIC4_SFL_SMOKE_LOCAL_PORT must be numeric"
[[ "$poll_seconds" =~ ^[0-9]+$ ]] || die "TOPIC4_SFL_SMOKE_POLL_SECONDS must be numeric"
[[ "$poll_interval" =~ ^[0-9]+$ ]] || die "TOPIC4_SFL_SMOKE_POLL_INTERVAL_SECONDS must be numeric"

work="$(mktemp -d "${TMPDIR:-/tmp}/topic4-sfl-smoke.XXXXXX")"
port_forward_pid=""
cleanup() {
  if [[ -n "$port_forward_pid" ]]; then kill "$port_forward_pid" 2>/dev/null || true; fi
  find "$work" -type f -exec sh -c 'for f do : > "$f"; done' sh {} + 2>/dev/null || true
  rm -rf -- "$work"
}
trap cleanup EXIT INT TERM
umask 077

kubectl -n "$namespace" get secret "$gateway_secret" \
  -o 'go-template={{index .data "bearer-token"}}' | base64 --decode >"$work/gateway-token"
test -s "$work/gateway-token" || die "SFL gateway bearer token is unavailable"
{ printf 'Authorization: Bearer '; cat "$work/gateway-token"; printf '\n'; } >"$work/auth-header"

kubectl -n "$namespace" get configmap "${gateway}-config" \
  -o 'jsonpath={.data.runner-endpoints\.json}' >"$work/endpoints.json"
jq -e 'keys == ["A","B","C"]' "$work/endpoints.json" >/dev/null ||
  die "SFL endpoint map is incomplete"

image_digest="$(kubectl -n "$namespace" get deployment "$gateway" \
  -o 'jsonpath={.spec.template.spec.containers[?(@.name=="gateway")].env[?(@.name=="TOPIC4_IMAGE_DIGEST")].value}')"
[[ "$image_digest" =~ ^sha256:[0-9a-fA-F]{64}$ ]] || die "SFL image digest is unavailable"

if [[ -n "${TOPIC4_SFL_SMOKE_REQUEST:-}" ]]; then
  test -f "$TOPIC4_SFL_SMOKE_REQUEST" || die "TOPIC4_SFL_SMOKE_REQUEST is not a file"
  cp "$TOPIC4_SFL_SMOKE_REQUEST" "$work/request.json"
  chmod 0600 "$work/request.json"
  request_file="$work/request.json"
else
  test -f "$mapping_file" || die "fixture mapping is missing: $mapping_file"
  kubectl -n "$source_namespace" get secret "$source_secret" \
    -o 'go-template={{index .data "dataset-access-hmac"}}' | \
    base64 --decode >"$work/access-hmac"
  test -s "$work/access-hmac" || die "dataset access HMAC is unavailable"
  IMAGE_DIGEST="$image_digest" MAPPING_FILE="$mapping_file" \
    ENDPOINTS_FILE="$work/endpoints.json" HMAC_FILE="$work/access-hmac" \
    OUTPUT_FILE="$work/request.json" python3 - <<'PY'
import base64, hashlib, hmac, json, os, time, uuid

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")

def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")

mapping = json.load(open(os.environ["MAPPING_FILE"], encoding="utf-8"))
endpoints = json.load(open(os.environ["ENDPOINTS_FILE"], encoding="utf-8"))
secret = open(os.environ["HMAC_FILE"], "rb").read().strip()
if not secret:
    raise SystemExit("empty dataset access HMAC")
entries = mapping.get("datasets", {}).get("hfl", {})
if list(sorted(entries)) != ["A", "B", "C"]:
    raise SystemExit("fixture mapping does not contain hfl A/B/C")
now = int(time.time())
staging = []
datasets = []
for party in ("A", "B", "C"):
    item = entries[party]
    endpoint = endpoints[party]
    path = item.get("sourcePath")
    if not isinstance(path, str) or not path.startswith(("/dataset/", "/data/")):
        raise SystemExit("hfl/%s sourcePath is missing or outside configured data roots" % party)
    claims = {
        "subject": "SYSTEM", "datasetId": str(item["datasetId"]),
        "datasetVersion": str(item["version"]), "path": path, "action": "READ",
        "targetNode": endpoint["nodeName"], "issuedAtEpochSeconds": now,
        "expiresAtEpochSeconds": now + 3600, "jti": str(uuid.uuid4()), "singleUse": True,
    }
    payload = b64(canonical(claims))
    signed = "t4v1." + payload
    signature = b64(hmac.new(secret, signed.encode("ascii"), hashlib.sha256).digest())
    sha = str(item["sha256"]).lower()
    sha = sha if sha.startswith("sha256:") else "sha256:" + sha
    schema_digest = str(item.get("frozenSchemaDigest") or item["schemaDigest"]).lower()
    schema_digest = (schema_digest if schema_digest.startswith("sha256:")
                     else "sha256:" + schema_digest)
    staging.append({
        "partyId": party, "nodeName": endpoint["nodeName"],
        "agentBaseUrl": endpoint["agentBaseUrl"], "tokenType": "Topic4Scope",
        "token": signed + "." + signature, "datasetId": str(item["datasetId"]),
        "datasetVersion": str(item["version"]), "sourcePath": path,
        "expectedSize": int(item["sizeBytes"]), "expectedSha256": sha,
        "expectedSchema": item["schema"], "expectedSchemaDigest": schema_digest,
    })
    datasets.append({
        "partyId": party, "datasetId": str(item["datasetId"]),
        "datasetVersion": str(item["version"]), "sha256": sha,
        "schemaDigest": schema_digest,
    })
image_digest = os.environ["IMAGE_DIGEST"].lower()
attempt = "image-%s-%s" % (
    image_digest.split(":", 1)[1][:12], uuid.uuid4().hex[:12])
spec_material = {
    "templateId": "hfl-fedavg-logreg-3p-v1", "datasets": datasets,
    "participants": ["A", "B", "C"], "resultRecipients": ["A"],
    "enginePolicy": {"labelColumn": "label", "featureColumns": ["x1", "x2"],
                     "epochs": 1, "learningRate": 0.05, "seed": 20260919},
}
request = {
    "jobId": "sfl-distributed-smoke", "attemptId": attempt,
    "templateId": "hfl-fedavg-logreg-3p-v1",
    "protocolVersion": "sfl-c383e40f/fedavg-logreg",
    "imageDigest": image_digest,
    "specDigest": "sha256:" + hashlib.sha256(canonical(spec_material)).hexdigest(),
    "securityProfile": "SEMI_HONEST_FL",
    "participants": [
        {"partyId": party, "role": "TRAINER", "fields": ["x1", "x2", "label"]}
        for party in ("A", "B", "C")
    ],
    "resultRecipients": ["A"], "timeoutSeconds": 3600,
    "enginePolicy": spec_material["enginePolicy"], "staging": staging,
}
with open(os.environ["OUTPUT_FILE"], "w", encoding="utf-8") as stream:
    json.dump(request, stream, sort_keys=True, separators=(",", ":"))
    stream.write("\n")
PY
  request_file="$work/request.json"
fi

jq -e '
  .templateId == "hfl-fedavg-logreg-3p-v1"
  and .protocolVersion == "sfl-c383e40f/fedavg-logreg"
  and .securityProfile == "SEMI_HONEST_FL"
  and ([.participants[].partyId] == ["A","B","C"])
  and ([.staging[].partyId] == ["A","B","C"])
  and (all(.staging[]; (.token|type) == "string" and (.token|length) > 20))
  and ([keys[] | select(startswith("_topic4"))] | length == 0)
' "$request_file" >/dev/null || die "SFL smoke request violates the fixed bootstrap contract"
image_digest_lower="$(printf '%s' "$image_digest" | tr '[:upper:]' '[:lower:]')"
[[ "$(jq -r '.imageDigest|ascii_downcase' "$request_file")" == "$image_digest_lower" ]] ||
  die "SFL smoke request imageDigest differs from the deployed gateway"

kubectl -n "$namespace" port-forward "service/$gateway" "$local_port:8080" \
  >"$work/port-forward.log" 2>&1 &
port_forward_pid=$!
base="http://127.0.0.1:${local_port}"
for _ in $(seq 1 50); do
  curl --silent --show-error --fail --max-time 2 "$base/live" >/dev/null 2>&1 && break
  kill -0 "$port_forward_pid" 2>/dev/null || die "SFL gateway port-forward stopped"
  sleep 0.2
done
curl --silent --show-error --fail --max-time 2 "$base/live" >/dev/null ||
  die "SFL gateway port-forward did not become ready"

job_id="$(jq -r '.jobId' "$request_file")"
attempt_id="$(jq -r '.attemptId' "$request_file")"
external_id="$(JOB_ID="$job_id" ATTEMPT_ID="$attempt_id" python3 - <<'PY'
import hashlib, os
value = "%s\0%s\0KUSCIA_SFL" % (os.environ["JOB_ID"], os.environ["ATTEMPT_ID"])
print(hashlib.sha256(value.encode()).hexdigest()[:32])
PY
)"

http_code="$(curl --silent --show-error --output "$work/existing.json" --write-out '%{http_code}' \
  --header @"$work/auth-header" "$base/jobs/$external_id")"
if [[ "$http_code" == 404 ]]; then
  http_code="$(curl --silent --show-error --output "$work/submit.json" --write-out '%{http_code}' \
    --request POST --header 'Content-Type: application/json' \
    --header @"$work/auth-header" --data-binary "@$request_file" \
    "$base/bootstrap/jobs")"
  [[ "$http_code" == 202 ]] || {
    jq -c '{status,failureCode,failureMessage}' "$work/submit.json" >&2 || true
    die "SFL bootstrap dispatch returned HTTP $http_code"
  }
elif [[ "$http_code" != 200 ]]; then
  die "existing SFL smoke lookup returned HTTP $http_code"
fi

deadline=$((SECONDS + poll_seconds))
while :; do
  curl --silent --show-error --fail --header @"$work/auth-header" \
    "$base/jobs/$external_id" >"$work/status.json"
  status="$(jq -r '.status' "$work/status.json")"
  case "$status" in
    SUCCEEDED) break ;;
    FAILED|ABORTED|CANCELLED)
      jq -c '{externalJobId,status,failureCode,failureMessage,partyJobs}' "$work/status.json" >&2
      die "SFL distributed smoke ended in $status"
      ;;
  esac
  (( SECONDS < deadline )) || die "SFL distributed smoke observation timed out"
  sleep "$poll_interval"
done
jq -e '.status == "SUCCEEDED" and
  ([.partyJobs | to_entries[] | .key] | sort == ["A","B","C"]) and
  (all(.partyJobs[]; .status == "SUCCEEDED"))' "$work/status.json" >/dev/null ||
  die "gateway did not record three successful party jobs"

party_pod() {
  local ns="$1" pod count
  pod="$(kubectl -n "$ns" get pod \
    -l 'kuscia.secretflow/kd-name=topic4-privacy-sfl-parties' \
    -o 'jsonpath={range .items[*]}{.metadata.name}{"\n"}{end}')"
  count="$(printf '%s\n' "$pod" | sed '/^$/d' | wc -l | tr -d ' ')"
  [[ "$count" == 1 ]] || die "expected one SFL party pod in $ns, found $count"
  printf '%s\n' "$pod" | sed -n '1p'
}

for ns in kuscia-a kuscia-b kuscia-c; do
  pod="$(party_pod "$ns")"
  kubectl -n "$ns" exec "$pod" -c runner -- \
    python3 /opt/topic4/bin/sfl-engine-check.py --verify-smoke >/dev/null
done
for ns in kuscia-a kuscia-b kuscia-c; do
  pod="$(party_pod "$ns")"
  kubectl -n "$ns" exec "$pod" -c runner -- \
    python3 /opt/topic4/bin/sfl-engine-check.py --approve-smoke >"$work/approve-${ns}.json"
done

deadline=$((SECONDS + 120))
while :; do
  http_code="$(curl --silent --show-error --output "$work/health.json" --write-out '%{http_code}' \
    "$base/health")"
  [[ "$http_code" == 200 ]] && [[ "$(jq -r '.status' "$work/health.json")" == UP ]] && break
  (( SECONDS < deadline )) || die "SFL gateway did not become healthy after approval"
  sleep 2
done

# Emit only non-secret deployment evidence. The request and staging tokens stay
# in the mode-0700 temporary directory and are wiped by the EXIT trap.
jq '{externalJobId,status,providerId,templateId,partyJobs,resultDigest}' "$work/status.json"
jq '{status,providerId,version,imageDigest,partyStatus,plaintextFallback}' "$work/health.json"
