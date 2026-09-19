# Topic4 privacy execution plane

This directory contains thin, authenticated wrappers around pinned third-party
engines. The wrappers validate a closed template contract, stage one authorized
dataset snapshot per party, invoke the real engine, and return result/evidence
envelopes. They contain no plaintext implementation of cryptographic protocols.

## Runtime topology

The control plane sends one `POST /jobs` request with `staging[]` to a
provider-specific gateway. The gateway uses a read-only deployment map for all
party runner and Agent identities and sends one local request to each isolated
party runner. A staging token exists only in the call stack. The local runner
downloads from its fixed Agent, verifies byte count, SHA-256 and schema, writes
the input to an emptyDir-backed 0700 directory, and deletes it in terminal paths.

Both gateway and party APIs require bearer authentication. Each provider has
one gateway ingress secret plus three independently generated A/B/C runner
secrets; the gateway mounts only the corresponding downstream token for each
fixed party endpoint. No party pod receives the gateway secret or another
domain's runner secret. `/live` and `/health` remain usable by Kubernetes probes. A healthy runtime returns
`status=UP`, its exact version and `TOPIC4_IMAGE_DIGEST`. Missing engines,
credentials, immutable digest, validated Kuscia context, or distributed config
return HTTP 503 and `status=DOWN`.

| Provider | ClusterIP DNS |
| --- | --- |
| MP-SPDZ | `topic4-privacy-mpspdz-gateway.kuscia-master.svc.cluster.local:8080` |
| SecretFlow | `topic4-privacy-secretflow-gateway.kuscia-master.svc.cluster.local:8080` |
| APSI | `topic4-privacy-apsi-gateway.kuscia-master.svc.cluster.local:8080` |
| SFL | `topic4-privacy-sfl-gateway.kuscia-master.svc.cluster.local:8080` |

Each provider gateway has a separate target Secret. Deployment scripts copy
only its matching ingress key from `topic4-1/topic4-acceptance-secrets`, then
generate distinct random runner credentials for A/B/C. No credential value is
stored in a ConfigMap, command line, log, or the repository.

## Pinned engines and commands

MP-SPDZ uses v0.4.3 asset SHA-256
`4b5f4f7a79f31365288fe4498ba2b70afc314c028696929c8d259ef50ab2e759`.
Every job runs one process in each A/B/C pod:

```
malicious-rep-ring-party.x <0|1|2> <fixed-program> \
  --ip-file-name /etc/topic4-mpspdz/hosts.txt --portnumbase 5000 -OF .
```

The three services use MP-SPDZ certificate-authenticated TLS. Each pod receives
all public P0/P1/P2 certificates and only its own private key. Sum, statistics,
and threshold each have seven precompiled recipient-mask variants.
`print_ln_to` plus `reveal_to` keeps non-recipients from reconstructing the
result. MP-SPDZ 0.4.3 binary-circuit shares reject per-player reveal, so the
threshold template uses a malicious replicated-ring comparison. The binary
executable remains installed only for an explicit all-recipient engine smoke
and is not advertised as the threshold implementation.

`privacy-psi` builds one image containing SecretFlow 1.11.0b1 and the real
PSI/APSI v0.6.0.dev260105 launcher at commit
`72f3312fd9142ea567f3a170d0808a2118b75af8` and runs:

```
/opt/psi/main --config <image-generated-fixed-launch-config>
```

RR22, ECDH 3PC and APSI parameters are selected by template code. APSI and the
combined PSI/HEU/SecureBoost providers select different fixed catalogs from the
same image. Party endpoints come only from a context rendered by a real
AppImage/KusciaDeployment. Cross-domain connections use Kuscia Cluster
endpoints through Envoy mTLS; a normal Deployment with a marker cannot satisfy
the context digest, serving ID and controller-injected environment checks.
APSI supports one value column, emits the required `key,value` header, rejects
empty/duplicate keys, and returns hit/value rows to the client runner.

SFL is pinned to `c383e40f665063d7f7d87e437a73e015f87c435c`. That
unreleased source declares `1.0.0.dev$$DATE$$` and requires
`secretflow-lite==1.13.0b0`, SPU `>=0.9.4.dev20250618`, and Ray 2.52.0.
It has an independent image. The build check verifies the pinned libraries and
model constructor only. A real SFL AppImage/KusciaDeployment exposes `fed` and
`spu` Cluster ports, and the adapter runs SecretFlow production SPMD in A/B/C,
saves one model shard per owner, and releases metrics only to configured
recipients. Runtime health remains DOWN until a digest-pinned three-domain
smoke record for that exact serving ID and context exists. A local simulation,
import, or marker is not distributed availability evidence.

The initial smoke uses a separate authenticated `POST /bootstrap/jobs` path.
It exists only when both the gateway and all three party runners are
`KUSCIA_SFL` deployments with `TOPIC4_SFL_BOOTSTRAP_MODE=1`. It replaces the
smoke-dependent `healthCommand` with `bootstrapHealthCommand`; exact package
versions and the signed Kuscia topology are still checked, while only the
record-approval gate is omitted. Image, source, imports, Kuscia context,
template, protocol/security profile, A/B/C roles, policy and scoped staging are
still validated. Caller-supplied `_topic4*` fields are rejected. Each successful
party freezes its own `distributed.json` under persistent party state. Health
accepts that record only after an operator writes the matching canonical digest
to the separate `distributed.sha256` file. Once any party has an approval file,
the bootstrap endpoint fails closed for that deployment.

`dependencies.lock.json` is the machine-readable dependency lock and
`THIRD_PARTY_NOTICES.md` records licenses. Generate an SPDX image SBOM with:

```
./scripts/generate-sbom.sh IMAGE OUTPUT.spdx.json
```

The script requires Syft and refuses to emit an incomplete substitute.

## Kubernetes

`k8s/kuscia/bootstrap.sh` deploys one real Kuscia Master and three Lite RunK
domains from the pinned amd64 image. It requires a dedicated MySQL schema and
user for the Master datastore,
generates PKCS#1 domain keys and one-use Lite tokens, configures each Lite DNS
server to its Service ClusterIP, keeps `protocol: MTLS`, creates every A/B/C
route, and waits for every ClusterDomainRoute Ready condition. Kuscia 1.2 Lite
does not expose a datastore endpoint; each Lite therefore keeps its own state
on its domain PVC instead of pretending to consume a separate MySQL account.
Deployments run `kuscia start`. RunK does not mount catalog data directly;
inputs enter only via the scoped Agent staging flow.

`k8s/deploy-kuscia-providers.sh` registers APSI, SecretFlow and SFL AppImages
and KusciaDeployments, waits for all three KDs to become Available, adds
party-local persistent state plus input/work scratch volumes to the generated
RunK Deployments, creates explicit outer ClusterIP runner Services, and deploys
three isolated gateways. It needs
the one `PRIVACY_PSI_IMAGE`, its registry manifest `PRIVACY_PSI_IMAGE_DIGEST`,
its OCI config `PRIVACY_PSI_IMAGE_ID`, the equivalent SFL values, A/B/C node
names, and fixed Agent URLs/node identities. Kuscia `spec.image.id` uses the
OCI config digest; evidence and pod verification use the registry digest.

After `bootstrap-fixtures.sh` creates a mapping that includes the concrete
registered replica `sourcePath`, run the real SFL smoke and approve the three
party-local records with:

```
TOPIC4_FIXTURE_MAPPING_FILE=./privacy-fixture-datasets.json \
  k8s/run-and-approve-sfl-smoke.sh
```

The script reads the provider and dataset-access secrets through `kubectl`,
mints one-hour single-use tokens in a mode-0700 temporary directory, dispatches
the fixed HFL request, requires all three child jobs to report `SUCCEEDED`,
validates every party record, and only then performs the explicit approvals.
It supports a prebuilt request through `TOPIC4_SFL_SMOKE_REQUEST`. Neither
bearer secret nor staging token is printed or passed as a command-line
argument.

`k8s/deploy-mpspdz.sh` creates three separate party Deployments/Services in
`kuscia-a`, `kuscia-b` and `kuscia-c`, plus one gateway in `kuscia-master`.
It requires an immutable image tag/digest, fixed Agent identities, and A/B/C
data-node names. It generates separate MP TLS keys, waits for rollouts, checks
pod `imageID`, and prints Service endpoints. Stopping any party aborts the
attempt without a partial aggregate result.

## Tests and fixture registration

```
./tests/run-contract-tests.sh
```

Tests cover frozen protocol/security/image values, fixed concurrent fan-out,
gateway/party credential isolation, asynchronous one-use staging, token
redaction, snapshot/schema verification, cleanup, finalization retry, public
policy names, catalogs, and the generated 21-program MP registry. All 21
programs were also compiled with the pinned v0.4.3 compiler.

`fixtures/manifest.json` holds deployment-time catalog IDs, byte counts,
SHA-256 values, and schema digests for deterministic A/B/C CSVs. Fixtures must
be registered through the authorized dataset catalog; they are never mounted
into runtime pods as a substitute for staging.

Cluster acceptance exports raw evidence for the human judge:

1. Build/push with `PUSH=1 SBOM=1 build-topic4 privacy-mpspdz` and record the
   image digest.
2. Deploy and submit the sum fixture. Verify the authorized recipient sees 145
   and non-recipients have `released=false`.
3. Stop one party and retry with a new attempt/token set. Verify the whole job
   aborts without a result. Repeat with a modified protocol message.
4. For PSI/PIR, inspect the sender log during the isolated acceptance run and
   search for every query key. Export that raw search result along with
   hit/miss, duplicate, empty-key, permission, and cleanup observations.
5. Preserve capabilities, party status, events, result/evidence digests, pod
   image IDs, and cleanup observations.

No component claims resistance to the Kubernetes cluster administrator.
