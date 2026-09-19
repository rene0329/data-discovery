#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
API_BASE_URL=${TOPIC4_API_BASE_URL:-http://127.0.0.1:8080}
API_BASE_URL=${API_BASE_URL%/}
MAPPING_FILE=${TOPIC4_FIXTURE_MAPPING_FILE:-"$PWD/privacy-fixture-datasets.json"}
BASELINE_FILE=${TOPIC4_RAW_BASELINE_FILE:-"$SCRIPT_DIR/../fixtures/baseline.json"}
RUN_ID=${TOPIC4_ACCEPTANCE_RUN_ID:-"$(date -u '+%Y%m%dT%H%M%SZ')-$$"}
OUTPUT_DIR=${TOPIC4_ACCEPTANCE_OUTPUT_DIR:-"$PWD/privacy-acceptance-$RUN_ID"}
POLL_SECONDS=${TOPIC4_POLL_SECONDS:-3600}
POLL_INTERVAL=${TOPIC4_POLL_INTERVAL_SECONDS:-5}
NEGATIVE_POLL_SECONDS=${TOPIC4_NEGATIVE_POLL_SECONDS:-120}
NEGATIVE_CASES=${TOPIC4_NEGATIVE_CASES:-}

SECRET_A=${TOPIC4_PARTY_A_SECRET:-}
SECRET_B=${TOPIC4_PARTY_B_SECRET:-}
SECRET_C=${TOPIC4_PARTY_C_SECRET:-}

die() {
  printf 'run-privacy-acceptance: %s\n' "$*" >&2
  exit 1
}

log() {
  printf 'run-privacy-acceptance: %s\n' "$*" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "required command is unavailable: $1"
}

require_command curl
require_command jq

case "$API_BASE_URL" in
  http://*|https://*) ;;
  *) die "TOPIC4_API_BASE_URL must use http:// or https://" ;;
esac
case "$RUN_ID" in
  ''|*[!A-Za-z0-9._-]*) die "TOPIC4_ACCEPTANCE_RUN_ID contains unsupported characters" ;;
esac
for numeric in "$POLL_SECONDS" "$POLL_INTERVAL" "$NEGATIVE_POLL_SECONDS"; do
  case "$numeric" in
    ''|*[!0-9]*) die "poll durations must be non-negative integers" ;;
  esac
done
[ "$POLL_INTERVAL" -gt 0 ] || die "TOPIC4_POLL_INTERVAL_SECONDS must be greater than zero"
[ -f "$MAPPING_FILE" ] || die "fixture mapping does not exist: $MAPPING_FILE"
[ -f "$BASELINE_FILE" ] || die "raw baseline does not exist: $BASELINE_FILE"
jq -e '.schemaVersion == 1 and (.datasets | type == "object")' "$MAPPING_FILE" >/dev/null ||
  die "fixture mapping has an unsupported shape"
jq -e '.contractVersion == "topic4.privacy.raw-baseline/v1"
  and (.templates | length == 9)
  and ([.templates[].templateId] | unique | length == 9)
  and ([.. | objects | keys[] | ascii_downcase] | all(. != "pass" and . != "fail"))' \
  "$BASELINE_FILE" >/dev/null || die "raw baseline has an unsupported shape"

for required in SECRET_A SECRET_B SECRET_C; do
  value=${!required}
  [ -n "$value" ] || die "TOPIC4_PARTY_${required#SECRET_}_SECRET is required"
  case "$value" in
    *$'\n'*|*$'\r'*) die "party credentials must not contain line breaks" ;;
  esac
done

mkdir -p -- "$OUTPUT_DIR"
chmod 700 "$OUTPUT_DIR"
jq '.' "$BASELINE_FILE" >"$OUTPUT_DIR/baseline.json"

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/topic4-privacy-acceptance.XXXXXX")
cleanup() {
  rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT HUP INT TERM
chmod 700 "$TEMP_DIR"

make_auth_header() {
  local party=$1
  local secret=$2
  local path="$TEMP_DIR/auth-$party.header"
  printf 'Authorization: Basic ' >"$path"
  printf '%s' "$party:$secret" | base64 | tr -d '\r\n' >>"$path"
  printf '\n' >>"$path"
  chmod 600 "$path"
}
make_auth_header A "$SECRET_A"
make_auth_header B "$SECRET_B"
make_auth_header C "$SECRET_C"

auth_header_for() {
  case "$1" in
    A|B|C) printf '%s/auth-%s.header\n' "$TEMP_DIR" "$1" ;;
    NONE) printf '\n' ;;
    *) die "unknown API principal: $1" ;;
  esac
}

# Writes the response body verbatim and the observed HTTP status to a sibling
# .http-status file. HTTP error responses are evidence and do not stop the run.
api_request() {
  local principal=$1
  local method=$2
  local path=$3
  local output=$4
  local body_file=${5:-}
  local idempotency_key=${6:-}
  local auth_header status
  local -a curl_args
  auth_header=$(auth_header_for "$principal")
  curl_args=(--silent --show-error --connect-timeout 10 --max-time 300
    --request "$method" --output "$output" --write-out '%{http_code}')
  if [ -n "$auth_header" ]; then
    curl_args+=(--header "@$auth_header")
  fi
  if [ -n "$body_file" ]; then
    curl_args+=(--header 'Content-Type: application/json' --data-binary "@$body_file")
  fi
  if [ -n "$idempotency_key" ]; then
    curl_args+=(--header "Idempotency-Key: $idempotency_key")
  fi
  if ! status=$(curl "${curl_args[@]}" "$API_BASE_URL$path"); then
    status=000
    if [ ! -s "$output" ]; then
      jq -n --arg method "$method" --arg path "$path" \
        '{transportError:true,method:$method,path:$path}' >"$output"
    fi
  fi
  printf '%s\n' "$status" >"$output.http-status"
}

json_body() {
  local target=$1
  shift
  jq -n "$@" >"$target"
}

api_request A GET /api/v1/privacy-computing/capabilities "$OUTPUT_DIR/capabilities.json"
api_request A GET /api/v1/privacy-computing/templates "$OUTPUT_DIR/templates.json"
jq -e '.code == 0 and (.data | type == "array")' "$OUTPUT_DIR/capabilities.json" >/dev/null ||
  die "capabilities endpoint did not return the expected API document"
jq -e '.code == 0 and (.data | type == "array")' "$OUTPUT_DIR/templates.json" >/dev/null ||
  die "templates endpoint did not return the expected API document"

dataset_record() {
  local family=$1
  local source_party=$2
  jq -ce --arg family "$family" --arg party "$source_party" \
    '.datasets[$family][$party]
      | select(.datasetId != null and .version != null and .sha256 != null)' "$MAPPING_FILE" ||
    die "fixture mapping is missing $family/$source_party"
}

participant() {
  local logical_party=$1
  local role=$2
  local family=$3
  local source_party=$4
  local fields=$5
  local dataset
  dataset=$(dataset_record "$family" "$source_party")
  jq -cn --arg party "$logical_party" --arg role "$role" \
    --argjson dataset "$dataset" --argjson fields "$fields" \
    '{partyId:$party,role:$role,datasetId:($dataset.datasetId|tostring),
      datasetVersion:$dataset.version,datasetSha256:$dataset.sha256,fields:$fields}'
}

build_spec() {
  local template_id=$1
  local destination=$2
  local security timeout a b c policy participants
  security=$(jq -er --arg id "$template_id" '.data[] | select(.templateId == $id) | .securityProfile' \
    "$OUTPUT_DIR/templates.json") || die "template is absent from catalog: $template_id"
  timeout=$(jq -er --arg id "$template_id" '.data[] | select(.templateId == $id) | .maxTimeoutSeconds' \
    "$OUTPUT_DIR/templates.json") || die "template timeout is absent: $template_id"

  case "$template_id" in
    secure-sum-3p-v1|private-threshold-3p-v1)
      a=$(participant A PARTY secure-sum A '["value"]')
      b=$(participant B PARTY secure-sum B '["value"]')
      c=$(participant C PARTY secure-sum C '["value"]')
      participants=$(jq -cn --argjson a "$a" --argjson b "$b" --argjson c "$c" '[$a,$b,$c]')
      if [ "$template_id" = private-threshold-3p-v1 ]; then
        policy='{"programId":"topic4_private_threshold_100","threshold":100,"scale":1}'
      else
        policy='{}'
      fi
      ;;
    private-stats-3p-v1)
      a=$(participant A PARTY private-stats A '["v1","v2"]')
      b=$(participant B PARTY private-stats B '["v1","v2"]')
      c=$(participant C PARTY private-stats C '["v1","v2"]')
      participants=$(jq -cn --argjson a "$a" --argjson b "$b" --argjson c "$c" '[$a,$b,$c]')
      policy='{"scale":1}'
      ;;
    psi-2p-v1)
      a=$(participant A RECEIVER psi A '["id"]')
      b=$(participant B PROVIDER psi B '["id"]')
      participants=$(jq -cn --argjson a "$a" --argjson b "$b" '[$a,$b]')
      policy='{"keyColumns":["id"],"outputMode":"RECEIVER_ONLY"}'
      ;;
    psi-3p-v1)
      a=$(participant A PARTY psi A '["id"]')
      b=$(participant B PARTY psi B '["id"]')
      c=$(participant C PARTY psi C '["id"]')
      participants=$(jq -cn --argjson a "$a" --argjson b "$b" --argjson c "$c" '[$a,$b,$c]')
      policy='{"keyColumns":["id"],"outputMode":"RECEIVER_ONLY"}'
      ;;
    pir-keyword-2p-v1)
      a=$(participant A CLIENT pir A '["key"]')
      b=$(participant B SERVER pir B '["key","value"]')
      participants=$(jq -cn --argjson a "$a" --argjson b "$b" '[$a,$b]')
      policy='{"queryColumn":"key","valueColumns":["value"]}'
      ;;
    he-paillier-2p-v1)
      a=$(participant A KEY_HOLDER he A '["value"]')
      b=$(participant B DATA_HOLDER he B '["value"]')
      participants=$(jq -cn --argjson a "$a" --argjson b "$b" '[$a,$b]')
      policy='{"operation":"ADD","scale":1}'
      ;;
    hfl-fedavg-logreg-3p-v1)
      a=$(participant A TRAINER hfl A '["x1","x2","label"]')
      b=$(participant B TRAINER hfl B '["x1","x2","label"]')
      c=$(participant C TRAINER hfl C '["x1","x2","label"]')
      participants=$(jq -cn --argjson a "$a" --argjson b "$b" --argjson c "$c" '[$a,$b,$c]')
      policy='{"labelColumn":"label","featureColumns":["x1","x2"],"epochs":1,"learningRate":0.05,"seed":20260919}'
      ;;
    vfl-secureboost-2p-v1)
      a=$(participant A ACTIVE vfl A '["id","x2","label"]')
      b=$(participant B PASSIVE vfl B '["id","x1"]')
      participants=$(jq -cn --argjson a "$a" --argjson b "$b" '[$a,$b]')
      policy='{"labelColumn":"label","featureColumns":["x1","x2"],"epochs":1,"learningRate":0.1,"seed":20260919}'
      ;;
    *) die "no acceptance request is declared for template $template_id" ;;
  esac

  jq -n --arg templateId "$template_id" --arg securityProfile "$security" \
    --argjson participants "$participants" --argjson timeout "$timeout" \
    --argjson policy "$policy" \
    '{templateId:$templateId,securityProfile:$securityProfile,participants:$participants,
      resultRecipients:["A"],timeoutSeconds:$timeout,enginePolicy:$policy}' >"$destination"
}

INDEX_NDJSON="$TEMP_DIR/index.ndjson"
: >"$INDEX_NDJSON"

append_index() {
  local template_id=$1
  local directory=$2
  local job_id=${3:-}
  local observed_status=${4:-}
  local image_digest=${5:-}
  local create_http
  create_http=$(tr -d '\r\n' <"$directory/create.json.http-status")
  jq -cn --arg templateId "$template_id" --arg directory "${directory#"$OUTPUT_DIR/"}" \
    --arg jobId "$job_id" --arg status "$observed_status" --arg imageDigest "$image_digest" \
    --arg createHttp "$create_http" \
    '{templateId:$templateId,artifactDirectory:$directory,
      jobId:(if ($jobId|length)>0 then $jobId else null end),
      observedStatus:(if ($status|length)>0 then $status else null end),
      imageDigest:(if ($imageDigest|length)>0 then $imageDigest else null end),
      createHttpStatus:$createHttp}' >>"$INDEX_NDJSON"
}

terminal_status() {
  case "$1" in
    SUCCEEDED|FAILED|ABORTED|CANCELLED) return 0 ;;
    *) return 1 ;;
  esac
}

run_template() {
  local template_id=$1
  local directory="$OUTPUT_DIR/jobs/$template_id"
  mkdir -p -- "$directory/poll" "$directory/participant-jobs"
  build_spec "$template_id" "$directory/spec.json"
  log "record template $template_id"

  api_request A POST /api/v1/privacy-computing/jobs/preflight \
    "$directory/preflight.json" "$directory/spec.json"
  api_request A POST /api/v1/privacy-computing/jobs \
    "$directory/create.json" "$directory/spec.json" "privacy-acceptance-$RUN_ID-$template_id"

  local job_id
  job_id=$(jq -r '.data.jobId // empty' "$directory/create.json" 2>/dev/null || true)
  if [ -z "$job_id" ]; then
    append_index "$template_id" "$directory"
    return
  fi
  printf '%s\n' "$job_id" >"$directory/job-id.txt"

  local participant_id decision_file
  jq -r '.data.participants[]?.partyId' "$directory/create.json" | while IFS= read -r participant_id; do
    case "$participant_id" in A|B|C) ;; *) continue ;; esac
    decision_file="$TEMP_DIR/approve-$template_id-$participant_id.json"
    json_body "$decision_file" --arg party "$participant_id" \
      '{participantId:$party,reason:"raw acceptance execution"}'
    api_request "$participant_id" POST "/api/v1/privacy-computing/jobs/$job_id/approve" \
      "$directory/approve-$participant_id.json" "$decision_file"
  done

  local started elapsed poll_no observed_status latest_job image_digest
  started=$(date +%s)
  poll_no=0
  observed_status=
  latest_job="$directory/job.json"
  while :; do
    poll_no=$((poll_no + 1))
    api_request A GET "/api/v1/privacy-computing/jobs/$job_id" \
      "$directory/poll/$(printf '%04d' "$poll_no").json"
    cp -- "$directory/poll/$(printf '%04d' "$poll_no").json" "$latest_job"
    observed_status=$(jq -r '.data.status // empty' "$latest_job" 2>/dev/null || true)
    if terminal_status "$observed_status"; then
      break
    fi
    elapsed=$(( $(date +%s) - started ))
    if [ "$elapsed" -ge "$POLL_SECONDS" ]; then
      break
    fi
    sleep "$POLL_INTERVAL"
  done
  jq -n --arg observedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    --arg status "$observed_status" --argjson polls "$poll_no" \
    --argjson elapsedSeconds "$(( $(date +%s) - started ))" \
    --argjson configuredLimitSeconds "$POLL_SECONDS" \
    '{observedAt:$observedAt,status:$status,polls:$polls,elapsedSeconds:$elapsedSeconds,
      configuredLimitSeconds:$configuredLimitSeconds}' >"$directory/poll-observation.json"

  jq '{jobId:.data.jobId,attemptId:.data.attemptId,participants:(.data.participants // [])}' \
    "$latest_job" >"$directory/participants.json" 2>/dev/null ||
    jq -n '{jobId:null,attemptId:null,participants:[]}' >"$directory/participants.json"

  jq -r '.data.participants[]?.partyId' "$latest_job" 2>/dev/null | while IFS= read -r participant_id; do
    case "$participant_id" in A|B|C) ;; *) continue ;; esac
    api_request "$participant_id" GET "/api/v1/privacy-computing/jobs/$job_id" \
      "$directory/participant-jobs/$participant_id.json"
  done
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id/events" "$directory/events.json"
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id/result" "$directory/result.json"
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id/evidence" "$directory/evidence.json"

  image_digest=$(jq -r '.data.imageDigest // empty' "$latest_job" 2>/dev/null || true)
  append_index "$template_id" "$directory" "$job_id" "$observed_status" "$image_digest"
}

TEMPLATES='secure-sum-3p-v1
private-stats-3p-v1
private-threshold-3p-v1
psi-2p-v1
psi-3p-v1
pir-keyword-2p-v1
he-paillier-2p-v1
hfl-fedavg-logreg-3p-v1
vfl-secureboost-2p-v1'

while IFS= read -r template_id; do
  [ -n "$template_id" ] && run_template "$template_id"
done <<EOF
$TEMPLATES
EOF

jq -s '.' "$INDEX_NDJSON" >"$OUTPUT_DIR/jobs-index.json"

negative_preflight() {
  local case_name=$1
  local filter=$2
  local directory="$OUTPUT_DIR/negative/$case_name"
  mkdir -p -- "$directory"
  build_spec secure-sum-3p-v1 "$directory/base-spec.json"
  jq "$filter" "$directory/base-spec.json" >"$directory/spec.json"
  api_request A POST /api/v1/privacy-computing/jobs/preflight \
    "$directory/preflight.json" "$directory/spec.json"
}

create_control_job() {
  local case_name=$1
  local directory="$OUTPUT_DIR/negative/$case_name"
  mkdir -p -- "$directory"
  build_spec secure-sum-3p-v1 "$directory/spec.json"
  api_request A POST /api/v1/privacy-computing/jobs/preflight \
    "$directory/preflight.json" "$directory/spec.json"
  api_request A POST /api/v1/privacy-computing/jobs "$directory/create.json" \
    "$directory/spec.json" "privacy-negative-$RUN_ID-$case_name"
  jq -r '.data.jobId // empty' "$directory/create.json" 2>/dev/null || true
}

record_control_job() {
  local directory=$1
  local job_id=$2
  mkdir -p -- "$directory"
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id" "$directory/job.json"
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id/events" "$directory/events.json"
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id/evidence" "$directory/evidence.json"
}

approve_all_from_view() {
  local job_id=$1
  local view_file=$2
  local directory=$3
  local reason=$4
  local participant_id body
  mkdir -p -- "$directory"
  jq -r '.data.participants[]?.partyId' "$view_file" | while IFS= read -r participant_id; do
    case "$participant_id" in A|B|C) ;; *) continue ;; esac
    body="$TEMP_DIR/negative-approve-$participant_id-$$.json"
    json_body "$body" --arg party "$participant_id" --arg reason "$reason" \
      '{participantId:$party,reason:$reason}'
    api_request "$participant_id" POST "/api/v1/privacy-computing/jobs/$job_id/approve" \
      "$directory/approve-$participant_id.json" "$body"
  done
}

poll_control_job() {
  local directory=$1
  local job_id=$2
  local limit=${3:-$NEGATIVE_POLL_SECONDS}
  local started poll_no observed_status elapsed snapshot
  mkdir -p -- "$directory/poll"
  started=$(date +%s)
  poll_no=0
  observed_status=
  while :; do
    poll_no=$((poll_no + 1))
    snapshot="$directory/poll/$(printf '%04d' "$poll_no").json"
    api_request A GET "/api/v1/privacy-computing/jobs/$job_id" "$snapshot"
    cp -- "$snapshot" "$directory/job.json"
    observed_status=$(jq -r '.data.status // empty' "$snapshot" 2>/dev/null || true)
    terminal_status "$observed_status" && break
    elapsed=$(( $(date +%s) - started ))
    [ "$elapsed" -ge "$limit" ] && break
    sleep "$POLL_INTERVAL"
  done
  jq -n --arg observedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
    --arg status "$observed_status" --argjson polls "$poll_no" \
    --argjson elapsedSeconds "$(( $(date +%s) - started ))" --argjson configuredLimitSeconds "$limit" \
    '{observedAt:$observedAt,status:$status,polls:$polls,elapsedSeconds:$elapsedSeconds,
      configuredLimitSeconds:$configuredLimitSeconds}' >"$directory/poll-observation.json"
}

capture_attempt_evidence() {
  local directory=$1
  local job_id=$2
  record_control_job "$directory" "$job_id"
  jq '{job:{jobId:.data.job.jobId,attemptId:.data.job.attemptId,
        attemptNo:.data.job.attemptNo,specDigest:.data.job.specDigest,
        protocolVersion:.data.job.protocolVersion,imageDigest:.data.job.imageDigest},
      retryEvents:[.data.events[]?
        | select(.messageCode == "FRESH_ATTEMPT_CREATED")
        | {attemptId,phase,status,messageCode,messageDigest,createdAt}],
      providerAttemptEvidence:(.data.providerEvidence // null),
      providerEvidenceDigest:(.data.providerEvidenceDigest // null)}' \
    "$directory/evidence.json" >"$directory/attempt-evidence.json" 2>/dev/null ||
    jq -n --arg jobId "$job_id" '{job:{jobId:$jobId},retryEvents:[],providerAttemptEvidence:null}' \
      >"$directory/attempt-evidence.json"
}

run_negative_spec_job() {
  local case_name=$1
  local spec_file=$2
  local directory="$OUTPUT_DIR/negative/$case_name"
  local job_id
  mkdir -p -- "$directory"
  cp -- "$spec_file" "$directory/spec.json"
  api_request A POST /api/v1/privacy-computing/jobs/preflight \
    "$directory/preflight.json" "$directory/spec.json"
  api_request A POST /api/v1/privacy-computing/jobs "$directory/create.json" \
    "$directory/spec.json" "privacy-negative-$RUN_ID-$case_name"
  job_id=$(jq -r '.data.jobId // empty' "$directory/create.json" 2>/dev/null || true)
  [ -n "$job_id" ] || return
  approve_all_from_view "$job_id" "$directory/create.json" "$directory/approvals" \
    "negative input execution"
  poll_control_job "$directory" "$job_id"
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id/events" "$directory/events.json"
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id/evidence" "$directory/evidence.json"
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id/result" "$directory/result.json"
}

negative_malformed_input() {
  local case_name=$1
  local template_id=$2
  local fixture_family=$3
  local base="$TEMP_DIR/$case_name-base.json"
  local spec="$TEMP_DIR/$case_name-spec.json"
  local malformed
  build_spec "$template_id" "$base"
  malformed=$(dataset_record "$fixture_family" A)
  jq --argjson dataset "$malformed" \
    '(.participants[] | select(.partyId == "A")) |=
      (.datasetId=($dataset.datasetId|tostring)
       | .datasetVersion=$dataset.version | .datasetSha256=$dataset.sha256)' \
    "$base" >"$spec"
  run_negative_spec_job "$case_name" "$spec"
}

negative_approval_reject() {
  local directory="$OUTPUT_DIR/negative/approval-reject" job_id body
  job_id=$(create_control_job approval-reject)
  [ -n "$job_id" ] || return
  body="$TEMP_DIR/negative-approve-a.json"
  json_body "$body" --arg party A '{participantId:$party,reason:"negative case approval before rejection"}'
  api_request A POST "/api/v1/privacy-computing/jobs/$job_id/approve" "$directory/approve-A.json" "$body"
  body="$TEMP_DIR/negative-reject-b.json"
  json_body "$body" --arg party B '{participantId:$party,reason:"negative case participant rejection"}'
  api_request B POST "/api/v1/privacy-computing/jobs/$job_id/reject" "$directory/reject-B.json" "$body"
  record_control_job "$directory" "$job_id"
}

negative_cancel() {
  local directory="$OUTPUT_DIR/negative/cancel" job_id body
  job_id=$(create_control_job cancel)
  [ -n "$job_id" ] || return
  body="$TEMP_DIR/negative-cancel.json"
  json_body "$body" '{reason:"negative case cancellation"}'
  api_request A POST "/api/v1/privacy-computing/jobs/$job_id/cancel" "$directory/cancel.json" "$body"
  record_control_job "$directory" "$job_id"
}

negative_retry() {
  local directory="$OUTPUT_DIR/negative/retry-dispatch" job_id body observed
  job_id=$(create_control_job retry-dispatch)
  [ -n "$job_id" ] || return
  body="$TEMP_DIR/retry-dispatch-reject.json"
  json_body "$body" --arg party B '{participantId:$party,reason:"prepare a fresh dispatched attempt"}'
  api_request B POST "/api/v1/privacy-computing/jobs/$job_id/reject" "$directory/reject-attempt-1.json" "$body"
  capture_attempt_evidence "$directory/attempt-1" "$job_id"

  api_request A POST "/api/v1/privacy-computing/jobs/$job_id/retry" "$directory/retry-to-attempt-2.json" '' \
    "privacy-negative-retry-dispatch-$RUN_ID"
  approve_all_from_view "$job_id" "$directory/retry-to-attempt-2.json" "$directory/attempt-2/approvals" \
    "fresh retry approval and dispatch"
  poll_control_job "$directory/attempt-2" "$job_id"
  capture_attempt_evidence "$directory/attempt-2" "$job_id"
  observed=$(jq -r '.data.status // empty' "$directory/attempt-2/job.json" 2>/dev/null || true)
  if ! terminal_status "$observed"; then
    body="$TEMP_DIR/retry-dispatch-cleanup.json"
    json_body "$body" '{reason:"cleanup after retry dispatch observation"}'
    api_request A POST "/api/v1/privacy-computing/jobs/$job_id/cancel" \
      "$directory/attempt-2/cancel.json" "$body"
    capture_attempt_evidence "$directory/attempt-2-after-cancel" "$job_id"
  fi

  negative_retry_limit
}

negative_retry_limit() {
  local directory="$OUTPUT_DIR/negative/retry-limit" job_id body attempt next
  job_id=$(create_control_job retry-limit)
  [ -n "$job_id" ] || return
  attempt=1
  while [ "$attempt" -le 3 ]; do
    mkdir -p -- "$directory/attempt-$attempt"
    body="$TEMP_DIR/retry-limit-reject-$attempt.json"
    json_body "$body" --arg party B --argjson attempt "$attempt" \
      '{participantId:$party,reason:("retry limit observation attempt " + ($attempt|tostring))}'
    api_request B POST "/api/v1/privacy-computing/jobs/$job_id/reject" \
      "$directory/attempt-$attempt/reject-B.json" "$body"
    capture_attempt_evidence "$directory/attempt-$attempt" "$job_id"
    if [ "$attempt" -lt 3 ]; then
      next=$((attempt + 1))
      api_request A POST "/api/v1/privacy-computing/jobs/$job_id/retry" \
        "$directory/retry-to-attempt-$next.json" '' \
        "privacy-negative-retry-limit-$RUN_ID-$next"
      cp -- "$directory/retry-to-attempt-$next.json" "$directory/attempt-$next-created.json"
    fi
    attempt=$((attempt + 1))
  done
  api_request A POST "/api/v1/privacy-computing/jobs/$job_id/retry" \
    "$directory/retry-over-limit.json" '' "privacy-negative-retry-limit-$RUN_ID-4"
  record_control_job "$directory/final" "$job_id"
}

negative_timeout() {
  local spec="$TEMP_DIR/negative-timeout.json"
  build_spec hfl-fedavg-logreg-3p-v1 "$spec"
  jq '.timeoutSeconds = 1' "$spec" >"$TEMP_DIR/negative-timeout-final.json"
  run_negative_spec_job timeout "$TEMP_DIR/negative-timeout-final.json"
}

negative_evidence_incomplete() {
  local directory="$OUTPUT_DIR/negative/evidence-incomplete" job_id body
  job_id=$(create_control_job evidence-incomplete)
  [ -n "$job_id" ] || return
  api_request A GET "/api/v1/privacy-computing/jobs/$job_id/evidence" \
    "$directory/evidence-before-approval.json"
  body="$TEMP_DIR/evidence-incomplete-cancel.json"
  json_body "$body" '{reason:"end evidence-before-dispatch observation"}'
  api_request A POST "/api/v1/privacy-computing/jobs/$job_id/cancel" "$directory/cancel.json" "$body"
  record_control_job "$directory/after-cancel" "$job_id"
}

negative_unauthorized_data() {
  local directory="$OUTPUT_DIR/negative/unauthorized-data"
  local dataset_id=${TOPIC4_UNAUTHORIZED_DATASET_ID:-}
  local dataset_version=${TOPIC4_UNAUTHORIZED_DATASET_VERSION:-}
  local dataset_sha=${TOPIC4_UNAUTHORIZED_DATASET_SHA256:-}
  local spec="$TEMP_DIR/unauthorized-data-base.json"
  mkdir -p -- "$directory"
  if [ -z "$dataset_id" ] || [ -z "$dataset_version" ] || [ -z "$dataset_sha" ]; then
    jq -n '{requiredEnvironment:["TOPIC4_UNAUTHORIZED_DATASET_ID",
      "TOPIC4_UNAUTHORIZED_DATASET_VERSION","TOPIC4_UNAUTHORIZED_DATASET_SHA256"],
      operation:"rerun with an ACTIVE catalog version that party A is not authorized to read"}' \
      >"$directory/manual-input.json"
    return
  fi
  build_spec secure-sum-3p-v1 "$spec"
  jq --arg id "$dataset_id" --arg version "$dataset_version" --arg sha "$dataset_sha" \
    '(.participants[] | select(.partyId == "A")) |=
      (.datasetId=$id | .datasetVersion=$version | .datasetSha256=$sha)' \
    "$spec" >"$directory/spec.json"
  api_request A POST /api/v1/privacy-computing/jobs/preflight \
    "$directory/preflight.json" "$directory/spec.json"
  api_request A POST /api/v1/privacy-computing/jobs "$directory/create.json" \
    "$directory/spec.json" "privacy-negative-$RUN_ID-unauthorized-data"
}

negative_non_recipient_result() {
  local directory="$OUTPUT_DIR/negative/non-recipient-result" source_job job_id
  mkdir -p -- "$directory"
  source_job=
  for candidate in "$OUTPUT_DIR"/jobs/*/job.json; do
    [ -f "$candidate" ] || continue
    if jq -e '.data.status == "SUCCEEDED"
      and ((.data.resultRecipients // []) | index("B") | not)
      and ((.data.participants // []) | map(.partyId) | index("B") != null)' "$candidate" >/dev/null; then
      source_job=$candidate
      break
    fi
  done
  if [ -z "$source_job" ]; then
    jq -n '{observation:"no completed job with a non-recipient party B was available"}' \
      >"$directory/observation.json"
    return
  fi
  job_id=$(jq -r '.data.jobId' "$source_job")
  printf '%s\n' "$job_id" >"$directory/job-id.txt"
  api_request B GET "/api/v1/privacy-computing/jobs/$job_id/result" "$directory/result-as-B.json"
}

negative_cleanup_observation() {
  local directory="$OUTPUT_DIR/negative/cleanup-observation"
  mkdir -p -- "$directory"
  if [ "${TOPIC4_ENABLE_KUBECTL_OBSERVATION:-0}" = 1 ]; then
    TOPIC4_RUNTIME_OBSERVATION_OUT="$directory" \
      "$SCRIPT_DIR/collect-runtime-observations.sh" >"$directory/collector.stdout"
  else
    jq -n '{operation:"set TOPIC4_ENABLE_KUBECTL_OBSERVATION=1 and rerun cleanup-observation",
      collector:"privacy-runtime/scripts/collect-runtime-observations.sh",
      reads:["Pod imageID and restart metadata","staged-input file path inventory"],
      contentRead:false}' >"$directory/manual-input.json"
  fi
}

if [ -n "$NEGATIVE_CASES" ]; then
  old_ifs=$IFS
  IFS=,
  set -- $NEGATIVE_CASES
  IFS=$old_ifs
  for negative_case in "$@"; do
    case "$negative_case" in
      digest-mismatch)
        negative_preflight digest-mismatch \
          '(.participants[0].datasetSha256) = "0000000000000000000000000000000000000000000000000000000000000000"'
        ;;
      unknown-field)
        negative_preflight unknown-field '(.participants[0].fields) = ["__missing__"]'
        ;;
      psi-duplicate) negative_malformed_input psi-duplicate psi-2p-v1 psi-duplicate ;;
      psi-empty) negative_malformed_input psi-empty psi-2p-v1 psi-empty ;;
      pir-duplicate) negative_malformed_input pir-duplicate pir-keyword-2p-v1 pir-duplicate ;;
      pir-empty) negative_malformed_input pir-empty pir-keyword-2p-v1 pir-empty ;;
      unauthorized-data) negative_unauthorized_data ;;
      timeout) negative_timeout ;;
      evidence-incomplete) negative_evidence_incomplete ;;
      approval-reject) negative_approval_reject ;;
      cancel) negative_cancel ;;
      retry) negative_retry ;;
      non-recipient-result) negative_non_recipient_result ;;
      cleanup-observation) negative_cleanup_observation ;;
      '') ;;
      *) die "unknown TOPIC4_NEGATIVE_CASES entry: $negative_case" ;;
    esac
  done
fi

jq -n \
  --slurpfile capabilities "$OUTPUT_DIR/capabilities.json" \
  --slurpfile templates "$OUTPUT_DIR/templates.json" \
  --slurpfile jobs "$OUTPUT_DIR/jobs-index.json" \
  '{capabilities:[$capabilities[0].data[] | {provider,status,version,imageDigest}],
    templates:[$templates[0].data[] | {templateId,provider,protocolVersion,imageDigest,available}],
    jobs:$jobs[0]}' >"$OUTPUT_DIR/image-digests.json"

jq -n --arg runId "$RUN_ID" --arg generatedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  --arg apiBaseUrl "$API_BASE_URL" --arg fixtureMapping "$MAPPING_FILE" \
  --arg outputDirectory "$OUTPUT_DIR" --arg negativeCases "$NEGATIVE_CASES" \
  --slurpfile jobs "$OUTPUT_DIR/jobs-index.json" \
  '{schemaVersion:1,runId:$runId,generatedAt:$generatedAt,apiBaseUrl:$apiBaseUrl,
    fixtureMapping:$fixtureMapping,outputDirectory:$outputDirectory,
    rawBaseline:"baseline.json",
    requestedNegativeCases:($negativeCases | split(",") | map(select(length>0))),jobs:$jobs[0],
    judgment:"reserved for manual review"}' >"$OUTPUT_DIR/manifest.json"

log "raw artifacts written to $OUTPUT_DIR"
printf '%s\n' "$OUTPUT_DIR"
