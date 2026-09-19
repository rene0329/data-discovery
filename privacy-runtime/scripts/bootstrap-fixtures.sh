#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUNTIME_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
FIXTURE_DIR=${TOPIC4_FIXTURE_DIR:-"$RUNTIME_DIR/fixtures"}
API_BASE_URL=${TOPIC4_API_BASE_URL:-http://127.0.0.1:8080}
API_BASE_URL=${API_BASE_URL%/}
FIXTURE_VERSION=${TOPIC4_FIXTURE_VERSION:-v1}
OUTPUT_FILE=${TOPIC4_FIXTURE_MAPPING_OUT:-"$PWD/privacy-fixture-datasets.json"}

NODE_A=${TOPIC4_NODE_A:-3}
NODE_B=${TOPIC4_NODE_B:-1}
NODE_C=${TOPIC4_NODE_C:-6}

DATASET_API_USER=${TOPIC4_API_BASIC_USER:-}
DATASET_API_PASSWORD=${TOPIC4_API_BASIC_PASSWORD:-}

die() {
  printf 'bootstrap-fixtures: %s\n' "$*" >&2
  exit 1
}

log() {
  printf 'bootstrap-fixtures: %s\n' "$*" >&2
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

case "$FIXTURE_VERSION" in
  ''|*[!A-Za-z0-9._-]*) die "TOPIC4_FIXTURE_VERSION contains unsupported characters" ;;
esac

for node in "$NODE_A" "$NODE_B" "$NODE_C"; do
  case "$node" in
    ''|*[!0-9]*) die "TOPIC4_NODE_A/B/C must be positive numeric node ids" ;;
    0) die "TOPIC4_NODE_A/B/C must be positive numeric node ids" ;;
  esac
done

if { [ -n "$DATASET_API_USER" ] && [ -z "$DATASET_API_PASSWORD" ]; } ||
   { [ -z "$DATASET_API_USER" ] && [ -n "$DATASET_API_PASSWORD" ]; }; then
  die "TOPIC4_API_BASIC_USER and TOPIC4_API_BASIC_PASSWORD must be set together"
fi

case "$DATASET_API_USER$DATASET_API_PASSWORD" in
  *$'\n'*|*$'\r'*) die "Basic credentials must not contain line breaks" ;;
esac

TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/topic4-fixtures.XXXXXX")
cleanup() {
  rm -rf -- "$TEMP_DIR"
}
trap cleanup EXIT HUP INT TERM
chmod 700 "$TEMP_DIR"

AUTH_HEADER=
if [ -n "$DATASET_API_USER" ]; then
  AUTH_HEADER="$TEMP_DIR/dataset-api.header"
  printf 'Authorization: Basic ' >"$AUTH_HEADER"
  printf '%s' "$DATASET_API_USER:$DATASET_API_PASSWORD" | base64 | tr -d '\r\n' >>"$AUTH_HEADER"
  printf '\n' >>"$AUTH_HEADER"
  chmod 600 "$AUTH_HEADER"
fi

file_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "sha256sum or shasum is required"
  fi
}

text_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 | awk '{print $1}'
  else
    die "sha256sum or shasum is required"
  fi
}

file_size() {
  if stat -f '%z' "$1" >/dev/null 2>&1; then
    stat -f '%z' "$1"
  else
    stat -c '%s' "$1"
  fi
}

schema_for() {
  case "$1/$2" in
    secure-sum/*|he/*)
      jq -cn '[{"name":"value","type":"INTEGER","nullable":false}]'
      ;;
    private-stats/*)
      jq -cn '[{"name":"v1","type":"INTEGER","nullable":false},{"name":"v2","type":"INTEGER","nullable":false}]'
      ;;
    psi/*|psi-duplicate/*|psi-empty/*)
      jq -cn '[{"name":"id","type":"STRING","nullable":false},{"name":"value","type":"STRING","nullable":false}]'
      ;;
    pir/A)
      jq -cn '[{"name":"key","type":"STRING","nullable":false}]'
      ;;
    pir/B)
      jq -cn '[{"name":"key","type":"STRING","nullable":false},{"name":"value","type":"STRING","nullable":false}]'
      ;;
    pir/C|pir-duplicate/A|pir-empty/A)
      jq -cn '[{"name":"key","type":"STRING","nullable":false}]'
      ;;
    hfl/*)
      jq -cn '[{"name":"x1","type":"INTEGER","nullable":false},{"name":"x2","type":"INTEGER","nullable":false},{"name":"label","type":"INTEGER","nullable":false}]'
      ;;
    vfl/A)
      jq -cn '[{"name":"id","type":"STRING","nullable":false},{"name":"x2","type":"INTEGER","nullable":false},{"name":"label","type":"INTEGER","nullable":false}]'
      ;;
    vfl/B)
      jq -cn '[{"name":"id","type":"STRING","nullable":false},{"name":"x1","type":"INTEGER","nullable":false}]'
      ;;
    vfl/C)
      jq -cn '[{"name":"id","type":"STRING","nullable":false}]'
      ;;
    *) die "no schema is declared for fixture $1/$2" ;;
  esac
}

node_for_party() {
  case "$1" in
    A) printf '%s\n' "$NODE_A" ;;
    B) printf '%s\n' "$NODE_B" ;;
    C) printf '%s\n' "$NODE_C" ;;
    *) die "unknown fixture party: $1" ;;
  esac
}

api_request() {
  local method=$1
  local path=$2
  local output=$3
  shift 3
  local status
  local -a curl_args
  curl_args=(--silent --show-error --connect-timeout 10 --max-time 300
    --request "$method" --output "$output" --write-out '%{http_code}')
  if [ -n "$AUTH_HEADER" ]; then
    curl_args+=(--header "@$AUTH_HEADER")
  fi
  status=$(curl "${curl_args[@]}" "$@" "$API_BASE_URL$path") ||
    die "request transport error: $method $path"
  printf '%s\n' "$status" >"$output.http-status"
}

require_success_response() {
  local response=$1
  local context=$2
  local status
  status=$(tr -d '\r\n' <"$response.http-status")
  case "$status" in
    2??) ;;
    *) die "$context returned HTTP $status; response retained at $response" ;;
  esac
  jq -e '.code == 0 and .data != null' "$response" >/dev/null ||
    die "$context returned an unexpected API response; response retained at $response"
}

MAPPING_TMP="$TEMP_DIR/dataset-mapping.json"
jq -n \
  --arg generatedAt "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
  --arg fixtureVersion "$FIXTURE_VERSION" \
  --argjson nodeA "$NODE_A" --argjson nodeB "$NODE_B" --argjson nodeC "$NODE_C" \
  '{schemaVersion:1, generatedAt:$generatedAt, fixtureVersion:$fixtureVersion,
    nodeMapping:{A:$nodeA,B:$nodeB,C:$nodeC}, datasets:{}}' >"$MAPPING_TMP"

register_fixture() {
  local family=$1
  local party=$2
  local fixture="$FIXTURE_DIR/$family/$(printf '%s' "$party" | tr '[:upper:]' '[:lower:]').csv"
  [ -f "$fixture" ] || die "fixture file does not exist: $fixture"

  local schema expected_schema expected_catalog_schema expected_schema_digest expected_frozen_schema_digest header expected_header sha size node code name query_response matches dataset_json dataset_id status
  schema=$(schema_for "$family" "$party")
  expected_schema=$(printf '%s' "$schema" | jq -cS \
    'map({name:(.name|tostring),type:(.type|tostring|ascii_upcase),nullable:.nullable})')
  expected_catalog_schema=$(jq -cnS --argjson columns "$expected_schema" \
    '{type:"CSV",columns:$columns}')
  expected_schema_digest=$(printf '%s' "$expected_catalog_schema" | text_sha256)
  expected_frozen_schema_digest=$(printf '%s' "$expected_schema" | text_sha256)
  header=$(sed -n '1p' "$fixture" | tr -d '\r')
  expected_header=$(printf '%s' "$schema" | jq -r '[.[].name] | join(",")')
  [ "$header" = "$expected_header" ] ||
    die "fixture header $family/$party is '$header', expected '$expected_header'"

  sha=$(file_sha256 "$fixture")
  size=$(file_size "$fixture")
  node=$(node_for_party "$party")
  code="topic4-privacy-${family}-$(printf '%s' "$party" | tr '[:upper:]' '[:lower:]')"
  name="Topic4 privacy ${family} fixture ${party}"

  query_response="$TEMP_DIR/query-${family}-${party}.json"
  api_request GET "/api/v1/datasets?page=1&pageSize=100&query=$code" "$query_response"
  require_success_response "$query_response" "lookup for $family/$party"
  matches=$(jq --arg code "$code" --arg version "$FIXTURE_VERSION" \
    '[.data.list[] | select(.datasetCode == $code and .version == $version)] | length' "$query_response")
  [ "$matches" -le 1 ] || die "multiple catalog entries exist for $code/$FIXTURE_VERSION"

  if [ "$matches" -eq 1 ]; then
    dataset_json=$(jq -c --arg code "$code" --arg version "$FIXTURE_VERSION" \
      '.data.list[] | select(.datasetCode == $code and .version == $version)' "$query_response")
    dataset_id=$(printf '%s' "$dataset_json" | jq -r '.datasetId')
    log "reuse dataset $family/$party as catalog id $dataset_id"
  else
    local metadata_inner metadata_request metadata_file upload_response upload_key
    metadata_inner=$(jq -cn \
      --arg code "$code" --arg name "$name" --arg version "$FIXTURE_VERSION" \
      --arg family "$family" --arg party "$party" --arg sha "$sha" \
      --argjson columns "$schema" \
      '{metadataVersion:"1.0",
        dataset:{datasetCode:$code,name:$name,version:$version,
          description:("Deterministic Topic4 privacy-computing fixture " + $family + "/" + $party),
          category:"PRIVACY_COMPUTING",format:"CSV"},
        schema:{type:"CSV",columns:$columns},
        digest:{algorithm:"SHA-256",value:$sha},
        source:{kind:"TOPIC4_ACCEPTANCE_FIXTURE",fixtureFamily:$family,sourceParty:$party},
        labels:{purpose:"privacy-acceptance",fixtureFamily:$family,sourceParty:$party}}')
    metadata_request=$(jq -cn \
      --argjson nodeId "$node" --arg code "$code" --arg name "$name" \
      --arg version "$FIXTURE_VERSION" --arg metadata "$metadata_inner" \
      --arg family "$family" --arg party "$party" \
      '{nodeId:$nodeId,datasetCode:$code,name:$name,version:$version,
        description:("Deterministic Topic4 privacy-computing fixture " + $family + "/" + $party),
        dataType:"CSV",category:"PRIVACY_COMPUTING",format:"CSV",metadataJson:$metadata,
        labels:{purpose:"privacy-acceptance",fixtureFamily:$family,sourceParty:$party}}')
    metadata_file="$TEMP_DIR/upload-${family}-${party}.metadata.json"
    printf '%s\n' "$metadata_request" >"$metadata_file"
    upload_response="$TEMP_DIR/upload-${family}-${party}.json"
    upload_key="privacy-fixture-upload-${family}-${party}-${sha:0:12}"
    api_request POST /api/v1/datasets/upload "$upload_response" \
      --header "Idempotency-Key: $upload_key" \
      --form "metadata=<$metadata_file;type=application/json" \
      --form "file=@$fixture;type=text/csv"
    require_success_response "$upload_response" "upload for $family/$party"
    dataset_json=$(jq -c '.data' "$upload_response")
    dataset_id=$(printf '%s' "$dataset_json" | jq -r '.datasetId')
    log "uploaded dataset $family/$party as catalog id $dataset_id"
  fi

  [ "$dataset_id" != null ] && [ -n "$dataset_id" ] || die "dataset id is missing for $family/$party"
  local detail_response
  detail_response="$TEMP_DIR/detail-${family}-${party}.json"
  api_request GET "/api/v1/datasets/$dataset_id" "$detail_response"
  require_success_response "$detail_response" "dataset detail for $family/$party"
  dataset_json=$(jq -c '.data' "$detail_response")

  local remote_sha
  remote_sha=$(printf '%s' "$dataset_json" | jq -r '.authoritativeSha256 // ""' | tr '[:upper:]' '[:lower:]')
  remote_sha=${remote_sha#sha256:}
  [ "$remote_sha" = "$sha" ] ||
    die "catalog dataset $dataset_id has SHA-256 $remote_sha, but fixture is $sha; use a new TOPIC4_FIXTURE_VERSION"
  local remote_catalog_schema remote_schema_digest
  remote_catalog_schema=$(printf '%s' "$dataset_json" | jq -cSe \
    '.schema
      | select(type == "object" and (.type|type) == "string")
      | (.columns // .fields // []) as $columns
      | if ($columns|type) == "array" and ($columns|length) > 0
          and all($columns[]; has("name") and has("type") and has("nullable"))
        then {type:(.type|ascii_upcase),
          columns:($columns | map({name:(.name|tostring),type:(.type|tostring|ascii_upcase),nullable:.nullable}))}
        else error("incomplete schema") end' 2>/dev/null) ||
    die "catalog dataset $dataset_id schema lacks explicit name/type/nullable metadata"
  [ "$remote_catalog_schema" = "$expected_catalog_schema" ] ||
    die "catalog dataset $dataset_id normalized schema differs from the declared fixture schema"
  remote_schema_digest=$(printf '%s' "$dataset_json" | jq -r '.schemaDigest // ""' | tr '[:upper:]' '[:lower:]')
  remote_schema_digest=${remote_schema_digest#sha256:}
  [ "$remote_schema_digest" = "$expected_schema_digest" ] ||
    die "catalog dataset $dataset_id schemaDigest differs from the canonical fixture schema"
  printf '%s' "$dataset_json" | jq -e --argjson node "$node" \
    '.replicas | any(.nodeId == $node)' >/dev/null ||
    die "catalog dataset $dataset_id has no replica on configured node $node for party $party"

  status=$(printf '%s' "$dataset_json" | jq -r '.status')
  if [ "$status" != ACTIVE ]; then
    local verify_response activate_response
    verify_response="$TEMP_DIR/verify-${family}-${party}.json"
    api_request POST "/api/v1/datasets/$dataset_id/verify" "$verify_response" \
      --header "Idempotency-Key: privacy-fixture-verify-${dataset_id}-${sha:0:12}-$$"
    require_success_response "$verify_response" "verify for $family/$party"
    activate_response="$TEMP_DIR/activate-${family}-${party}.json"
    api_request POST "/api/v1/datasets/$dataset_id/activate" "$activate_response" \
      --header "Idempotency-Key: privacy-fixture-activate-${dataset_id}-${sha:0:12}-$$"
    require_success_response "$activate_response" "activate for $family/$party"
    api_request GET "/api/v1/datasets/$dataset_id" "$detail_response"
    require_success_response "$detail_response" "post-activation dataset detail for $family/$party"
    dataset_json=$(jq -c '.data' "$detail_response")
    status=$(printf '%s' "$dataset_json" | jq -r '.status')
  fi
  [ "$status" = ACTIVE ] || die "dataset $dataset_id finished bootstrap in status $status"

  local entry next_mapping relative_path
  relative_path="${fixture#"$RUNTIME_DIR/"}"
  entry=$(printf '%s' "$dataset_json" | jq -c \
    --arg family "$family" --arg sourceParty "$party" --arg fixturePath "$relative_path" \
    --arg localSha "$sha" --arg frozenSchemaDigest "$expected_frozen_schema_digest" \
    --argjson nodeId "$node" --argjson columns "$schema" \
    '{datasetId:(.datasetId|tostring),datasetCode:.datasetCode,version:.version,
      fixtureFamily:$family,sourceParty:$sourceParty,fixturePath:$fixturePath,nodeId:$nodeId,
      sha256:$localSha,sizeBytes:.authoritativeSizeBytes,schema:$columns,
      schemaDigest:.schemaDigest,catalogSchemaDigest:.schemaDigest,
      frozenSchemaDigest:$frozenSchemaDigest,status:.status}')
  next_mapping="$TEMP_DIR/dataset-mapping.next.json"
  jq --arg family "$family" --arg party "$party" --argjson entry "$entry" \
    '.datasets[$family][$party] = $entry' "$MAPPING_TMP" >"$next_mapping"
  mv -- "$next_mapping" "$MAPPING_TMP"
}

for family in secure-sum private-stats psi pir he hfl vfl; do
  for party in A B C; do
    register_fixture "$family" "$party"
  done
done
for family in psi-duplicate psi-empty pir-duplicate pir-empty; do
  register_fixture "$family" A
done

if [ "$OUTPUT_FILE" != '-' ]; then
  mkdir -p -- "$(dirname -- "$OUTPUT_FILE")"
  output_tmp="$TEMP_DIR/final-mapping.json"
  jq '.' "$MAPPING_TMP" >"$output_tmp"
  cp -- "$output_tmp" "$OUTPUT_FILE"
  chmod 600 "$OUTPUT_FILE"
  log "dataset mapping written to $OUTPUT_FILE"
fi

jq '.' "$MAPPING_TMP"
