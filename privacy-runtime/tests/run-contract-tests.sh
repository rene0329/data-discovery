#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
python3 -c 'import ast,pathlib; [ast.parse(p.read_text(encoding="utf-8"), filename=str(p)) for root in (pathlib.Path("runner"), pathlib.Path("providers"), pathlib.Path("tests")) for p in root.rglob("*.py")]'
python3 -m unittest discover -s tests -p 'test_*.py' -v
python3 -m json.tool contract/job-request.schema.json >/dev/null
python3 -m json.tool dependencies.lock.json >/dev/null
python3 -m json.tool k8s/mpspdz.json.tpl >/dev/null
while IFS= read -r -d '' script; do
  bash -n "$script"
done < <(find k8s scripts -type f -name '*.sh' -print0)
if command -v kubectl >/dev/null 2>&1; then
  kubectl apply --dry-run=client --validate=false \
    -f k8s/kuscia/namespaces-services-rbac.yaml \
    -f k8s/kuscia/deployments.yaml >/dev/null
fi
