#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo "usage: generate-sbom.sh IMAGE OUTPUT.spdx.json" >&2
  exit 2
fi
if ! command -v syft >/dev/null 2>&1; then
  echo "syft is required; refusing to emit an incomplete SBOM" >&2
  exit 69
fi
image="$1"
output="$2"
mkdir -p "$(dirname "$output")"
syft "$image" -o "spdx-json=$output"
test -s "$output"
