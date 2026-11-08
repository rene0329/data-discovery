#!/usr/bin/env bash
set -euo pipefail
repo="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
if [[ -n "$(git -C "$repo" status --porcelain)" ]]; then
  echo 'Refusing to build a dirty checkout' >&2
  exit 1
fi
git -C "$repo" pull --ff-only origin main
sha="$(git -C "$repo" rev-parse HEAD)"
if [[ -n "${EXPECTED_COMMIT:-}" && "$sha" != "$EXPECTED_COMMIT" ]]; then
  echo 'Checkout differs from the tested commit' >&2
  exit 1
fi
image="${TOPIC4_REGISTRY:?source /data/topic4-build/bin/env.sh first}/trainer-tabular-mlp:v-$sha"
docker build --label "org.opencontainers.image.revision=$sha" --tag "$image" "$repo/training-images/tabular-mlp"
if [[ "${PUSH:-0}" == 1 ]]; then docker push "$image"; fi
printf 'IMAGE=%s\n' "$image"
