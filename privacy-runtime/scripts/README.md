# Privacy fixture and acceptance scripts

These scripts prepare deterministic CSV inputs and collect the raw records used
by the Topic4 manual acceptance review. They do not decide whether a protocol
or deployment is acceptable.

## Prerequisites

- `bash`, `curl`, and `jq`
- a reachable Topic4 practice-server API
- storage-capable Topic4 nodes for logical parties A, B, and C
- three enabled `DATA_OWNER` demo users in three different enabled business domains
- every fixture dataset assigned to its corresponding demo owner (`A`, `B`, or `C`)
- the three demo users' usernames and passwords supplied through the environment

Do not put credentials on the command line or in a checked-in file. The
acceptance collector reads credentials only from environment variables, submits
login bodies from a mode `0600` temporary directory, places generated Bearer
headers there, and removes that directory on exit. It does not print passwords,
JWTs, or headers. The acceptance output keeps each `/auth/me` response and a
token-redacted login observation so the human reviewer can identify the users
and domains used.

## Bootstrap fixtures

`bootstrap-fixtures.sh` uploads the 21 normal CSVs and four malformed-key CSVs
under `privacy-runtime/fixtures`, asks
the catalog to verify the registered replica, activates it, and emits a dataset
mapping document. Each upload includes an explicit named-column CSV schema and
the local SHA-256 digest. A repeated run reuses an exact catalog code/version;
it stops if the catalog digest, configured node, full ordered schema,
column type/nullability, or canonical catalog schema digest differs. Changed
fixture bytes or schema require a new fixture version.

The default logical node mapping is A=`3`, B=`1`, C=`6`. Override it when the
deployment uses different storage nodes:

```bash
export TOPIC4_API_BASE_URL=http://practice-server.example
export TOPIC4_NODE_A=3
export TOPIC4_NODE_B=1
export TOPIC4_NODE_C=6
export TOPIC4_FIXTURE_MAPPING_OUT="$PWD/privacy-fixture-datasets.json"
privacy-runtime/scripts/bootstrap-fixtures.sh >privacy-fixture-bootstrap.stdout.json
```

If an API gateway requires Basic authentication for the dataset endpoints, set
both `TOPIC4_API_BASIC_USER` and `TOPIC4_API_BASIC_PASSWORD`. The Topic4 dataset
controller itself does not require the privacy-party credentials.

Other bootstrap variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `TOPIC4_FIXTURE_DIR` | `privacy-runtime/fixtures` | Source fixture directory |
| `TOPIC4_FIXTURE_VERSION` | `v1` | Stable catalog version for this fixture set |
| `TOPIC4_FIXTURE_MAPPING_OUT` | `./privacy-fixture-datasets.json` | Mapping output, or `-` for stdout only |

The mapping contains catalog ids, versions, SHA-256 digests, schemas, schema
digests, node ids, the selected registered replica `sourcePath`, and fixture
provenance. It contains no credential material. The path is needed to mint the
strictly scoped, one-use SFL bootstrap tokens; the party runner still accepts it
only when the Agent independently verifies the signed catalog scope.

## Collect a nine-template acceptance run

`run-privacy-acceptance.sh` logs in the three configured demo users and builds a
fixed dataset-input request for each of the nine initial templates. User A is
the initiator. Each request binds `P0`, `P1`, and, when required, `P2` to an
explicit catalog dataset version. The server resolves the frozen data owner,
business domain, internal A/B/C execution slot, digest, schema, result recipient,
and image digest. The initiator's own input is automatically approved; the
collector submits each remaining approval as the matching frozen data owner.

For every template it records preflight and create responses. When creation
returns a job id, it polls the job and records each data holder's job view,
the three users' pending-approval views, approval responses, events, the
initiator-only result endpoint, evidence, and the frozen image digest.
Provider-unavailable and other API errors remain as their original response
documents.

The collector copies `privacy-runtime/fixtures/baseline.json` into each output
directory. This raw baseline contains the nine templates' fixture byte/schema
digests and deterministic expected values or result shapes. It contains no
runtime conclusion. Regenerate it together with `fixtures/manifest.json` after
editing fixture bytes:

```bash
python3 privacy-runtime/fixtures/generate_manifest.py
```

Supply the three demo-user logins through the environment, along with the
mapping created above. The datasets recorded under each mapping key must already
be assigned to the matching user: all `*/A` datasets to user A, `*/B` to user B,
and `*/C` to user C.

```bash
export TOPIC4_API_BASE_URL=http://practice-server.example
export TOPIC4_FIXTURE_MAPPING_FILE="$PWD/privacy-fixture-datasets.json"
export TOPIC4_USER_A_USERNAME
export TOPIC4_USER_A_PASSWORD
export TOPIC4_USER_B_USERNAME
export TOPIC4_USER_B_PASSWORD
export TOPIC4_USER_C_USERNAME
export TOPIC4_USER_C_PASSWORD
export TOPIC4_ACCEPTANCE_OUTPUT_DIR="$PWD/privacy-acceptance-artifacts"
privacy-runtime/scripts/run-privacy-acceptance.sh
```

Set each password using the deployment's secret manager or a silent shell
prompt before running the command. Empty values are rejected. Each account must
resolve through `/api/v1/auth/me` to a distinct user and distinct enabled domain
with the `DATA_OWNER` role. The output directory is created with mode `0700`
because evidence and result documents can be sensitive.

Fixture ownership follows the logical domains without cross-domain reads.
`pir/a.csv` is party A's client query input and `pir/b.csv` is party B's server
key/value table. `vfl/a.csv` is active party A's feature/label table and
`vfl/b.csv` is passive party B's feature table. The HFL request fixes the SFL
learning rate at `0.05`, matching the registered runtime model.

Useful run controls:

| Variable | Default | Meaning |
| --- | --- | --- |
| `TOPIC4_ACCEPTANCE_RUN_ID` | UTC timestamp plus process id | Idempotency and artifact run id |
| `TOPIC4_ACCEPTANCE_OUTPUT_DIR` | `./privacy-acceptance-<run-id>` | Raw artifact directory |
| `TOPIC4_POLL_SECONDS` | `3600` | Maximum observation time per created job |
| `TOPIC4_POLL_INTERVAL_SECONDS` | `5` | Poll interval |
| `TOPIC4_NEGATIVE_POLL_SECONDS` | `120` | Maximum observation time per dispatched negative job |
| `TOPIC4_RAW_BASELINE_FILE` | `privacy-runtime/fixtures/baseline.json` | Raw baseline copied into the run |

Each endpoint body is stored as JSON and its HTTP status is stored in a sibling
`.http-status` file. `capabilities.json`, `templates.json`, `jobs-index.json`,
`image-digests.json`, and `manifest.json` provide run-level navigation. Per-job
directories contain the request, preflight, creation, approvals, polling
snapshots, data-holder job views, events, result, and evidence.

## Optional negative-case entry points

Set `TOPIC4_NEGATIVE_CASES` to a comma-separated selection. These cases create
additional raw artifacts under `negative/`:

- `digest-mismatch`: emit the exact manual mutation procedure and frozen-digest
  evidence list; public requests cannot supply or override a digest
- `unknown-field`: preflight with a field absent from the frozen schema
- `psi-duplicate`, `psi-empty`: dispatch PSI inputs with duplicate or empty keys
- `pir-duplicate`, `pir-empty`: dispatch PIR client inputs with duplicate or empty keys
- `unauthorized-data`: create with an operator-supplied catalog version whose
  owner is outside the configured acceptance users and preserve the raw response
- `timeout`: dispatch the HFL template with a one-second engine timeout
- `evidence-incomplete`: capture evidence before approval/dispatch, then cancel
- `approval-reject`: let A's own input auto-approve, reject as owner B, then
  capture job evidence
- `cancel`: create and cancel a job while it awaits approval
- `retry`: dispatch a freshly approved second attempt and separately record
  attempts one through three plus the fourth-attempt response
- `non-recipient-result`: request an A-initiated completed result as data owner B
- `cleanup-observation`: optionally collect runtime staged-input path inventory
  and Pod image/restart metadata with `kubectl`

Example:

```bash
export TOPIC4_NEGATIVE_CASES=digest-mismatch,unknown-field,psi-duplicate,psi-empty,pir-duplicate,pir-empty,approval-reject,cancel,retry,non-recipient-result,timeout,evidence-incomplete
privacy-runtime/scripts/run-privacy-acceptance.sh
```

For `unauthorized-data`, supply an existing active dataset version owned by a
user outside the configured A/B/C accounts. Its schema must expose the `value`
field used by the secure-sum request. Dataset SHA-256 and owner identity are
always resolved by the server and are not accepted in the request:

```bash
export TOPIC4_UNAUTHORIZED_DATASET_ID
export TOPIC4_UNAUTHORIZED_DATASET_VERSION
export TOPIC4_NEGATIVE_CASES=unauthorized-data
privacy-runtime/scripts/run-privacy-acceptance.sh
```

The retry artifacts have two independent jobs. `retry-dispatch/attempt-2`
contains new data-owner approvals, polling snapshots, provider evidence, and a
derived `attempt-evidence.json` limited to attempt ids/numbers, digests, retry
events, and the backend-sanitized provider attempt evidence. `retry-limit/`
records three rejected attempts and the raw response to a fourth retry request.

To inspect cleanup after protocol jobs, run the read-only collector from an
operator environment. It lists staged-input file paths but never reads their
contents:

```bash
export TOPIC4_RUNTIME_OBSERVATION_OUT="$PWD/privacy-runtime-observation"
privacy-runtime/scripts/collect-runtime-observations.sh
```

An explicit context can be selected with `TOPIC4_KUBECTL_CONTEXT`. The same
collector is available through `TOPIC4_NEGATIVE_CASES=cleanup-observation` when
`TOPIC4_ENABLE_KUBECTL_OBSERVATION=1`; otherwise that entry writes the exact
manual invocation into its artifact directory.

The scripts preserve observed statuses and error documents without converting
them into an acceptance conclusion. Cases that require an operator action such
as deleting a Pod or tampering with protocol traffic remain manual procedures;
these collectors do not disrupt the cluster.
