#!/usr/bin/env bash
# Frontend API responses for the first five TC-4.2 cases.
set -euo pipefail
export PYTHONIOENCODING=utf-8
exec "${PYTHON:-python3}" "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tc42_api.py" "$@"
