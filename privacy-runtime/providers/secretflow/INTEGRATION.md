# Topic4 unified SecretFlow image

`privacy-psi` builds this directory's Dockerfile with `privacy-runtime` as the
context. The resulting image serves both providers; the Kuscia AppImage selects
one catalog with `TOPIC4_PROVIDER_CONFIG`:

- `/opt/topic4/provider-secretflow.json` registers `psi-2p-v1`, `psi-3p-v1`,
  `he-paillier-2p-v1`, and `vfl-secureboost-2p-v1` for
  `KUSCIA_SECRETFLOW`.
- `/opt/topic4/provider-apsi.json` registers `pir-keyword-2p-v1` for
  `KUSCIA_APSI`.

The PSI/APSI launcher and APSI parameter set are built from SecretFlow PSI
revision `72f3312fd9142ea567f3a170d0808a2118b75af8`. HEU and SecureBoost run on
SecretFlow `1.11.0b1` revision
`b9d6fd5ca9dbdfc95cfda5c282e93022e1caebc4`. Both build images are pulled
from the project GHCR mirror by immutable upstream digest.

```text
docker build -f providers/secretflow/Dockerfile .
```

Kuscia starts `/opt/topic4/bin/kuscia-runtime-entry.py`. The entrypoint
validates `CLUSTER_DEFINE`, `ALLOCATED_PORTS`, `INPUT_CONFIG`, domain identity,
and serving identity, then writes the normalized private context to
`/var/run/topic4-kuscia/context.json`. HE/VFL require the context's
`secretflowEndpoints.fed` and `secretflowEndpoints.spu` maps, its valid
`contextDigest`, `TOPIC4_KUSCIA_DEPLOYMENT_ID`, and
`TOPIC4_KUSCIA_TRANSPORT=KUSCIA_CLUSTER_MTLS_ENVOY`.

The local fed/SPU listeners bind only to their Kuscia allocated ports. Remote
addresses are Kuscia Cluster routes. Kuscia Envoy provides cross-domain mTLS,
so the application has no direct cross-domain endpoint and no separate
SecretFlow TLS key Secret. Both parties execute the same production
SecretFlow graph with `ray_mode=False` and `brpc_link`; missing or invalid
Kuscia context exits with code 69 and never selects a plaintext implementation.

`secretflow-engine-check.py` derives all addresses from the current validated
Kuscia context. It checks the exact SecretFlow version and APIs, the embedded
RR22 enum, a real Paillier-2048 known-answer operation, the pinned PSI launcher
and revision, and DNS for the current remote Kuscia routes. `/health` is based
only on those checks against the running workload.

`run-production-smoke.sh <image>` is a reproducible engine test. It starts two
production-mode containers and requires real HEU Paillier ciphertext addition,
plaintext multiplication and dot product, RR22 FAST PSI, SecureBoost
training/prediction, and a non-empty model shard at each owner. It never uses
SecretFlow simulation mode. On `master-89` on 2026-09-19, the pinned
`sha256:f0033a79e71f1f76656230d0f3cd31cbddc8d3bdcdb39fe2fd2afc8b000ec178`
image produced addition `[13,24]`, multiplication `[30,80]`, dot product `110`,
intersection count `12`, twelve predictions, and model shards at A and B.
