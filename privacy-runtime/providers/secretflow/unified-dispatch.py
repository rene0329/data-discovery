#!/usr/bin/env python3
"""Dispatch only registered templates to image-owned SecretFlow adapters."""
import json
import os
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 3:
        return 64
    try:
        request = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, ValueError):
        return 64
    template = request.get("templateId")
    if template in {"psi-2p-v1", "psi-3p-v1", "pir-keyword-2p-v1"}:
        executable = "/opt/topic4/bin/psi-run-engine.py"
    elif template in {"he-paillier-2p-v1", "vfl-secureboost-2p-v1"}:
        executable = "/opt/topic4/bin/secretflow-run-engine.py"
    else:
        return 64
    if not Path(executable).is_file() or not os.access(executable, os.X_OK):
        return 69
    os.execv(executable, [executable, *sys.argv[1:]])
    return 70


if __name__ == "__main__":
    raise SystemExit(main())
