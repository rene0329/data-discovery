#!/usr/bin/env python3
import os
from pathlib import Path

party = os.getenv("TOPIC4_PARTY_INDEX", "")
if party not in {"0", "1", "2"}:
    raise SystemExit("TOPIC4_PARTY_INDEX is missing")
root = Path("/opt/mp-spdz")
for executable in ("malicious-rep-ring-party.x", "malicious-rep-bin-party.x"):
    if not (root / executable).is_file():
        raise SystemExit("missing protocol executable")
for prefix in ("topic4_secure_sum", "topic4_private_stats", "topic4_private_threshold_100"):
    for mask in range(1, 8):
        if not (root / "Programs" / "Schedules" / ("%s_r%d.sch" % (prefix, mask))).is_file():
            raise SystemExit("missing fixed recipient-mask schedule")
tls = Path(os.getenv("TOPIC4_MPSPDZ_TLS_DIR", "/tls"))
for certificate in ("P0.pem", "P1.pem", "P2.pem"):
    if not (tls / certificate).is_file():
        raise SystemExit("missing party certificate")
if not (tls / ("P%s.key" % party)).is_file():
    raise SystemExit("missing local party private key")
