# UI / backend alignment release (2026-09-02)

## Contract

- `/common` page must be >= 1 and pageSize 1–100. Invalid values return HTTP 400 with a nonzero `code`.
- `/common/networkTopology` keeps string `id` for edges and adds stable numeric `nodeId` for dataset replicas. `cpu` and `memory` are nullable percentages. `disk` is a deprecated memory alias, not disk utilization.
- `/common/nodeSettings` also exposes derived `effectiveStatus`, `schedulable`, `statusReason`. Management UI uses `/api/v1/nodes` for status and versioned editing.
- `/common/updateDataItem` accepts only `{dataId, dataHeat}` (physical ID, heat 0–100); unsupported fields return 400 and missing records 404.
- `/common/updateNodeSettings` returns 410. Use `PATCH /api/v1/nodes/{nodeId}` with `version` for supported metadata fields.
- Data selection uses logical `/api/v1/datasets` IDs and `replicas[].nodeId`. These IDs are not interchangeable with `/common` physical `dataId`.
- Task flow: POST `/api/v1/tasks/preflight`, then POST `/api/v1/tasks` with a stable `Idempotency-Key`; returns the persisted taskId. Existing `/common/taskList` remains the query endpoint.
- Health endpoint: `/actuator/health`.
- Node/task/current-page polling remains approximately 1 second. Full dataset catalogs used in topology/settings refresh separately every 10 seconds, paginated at 100. Failed regions retain last-known data with an error message.
- Template login is intentionally retained by user request; it is not backend authentication. No business mock handler or XMLHttpRequest interception is included in the production build. Do not expose unauthenticated backend mutation APIs to untrusted networks.

## Schema and deployment order

1. Run Maven tests and frontend unit tests/build.
2. Back up `registered_dataset` and `dataset_discovery_candidate`; rehearse the additive migration on an isolated database.
3. Apply `practice-server/src/main/resources/db/migration/V20260902__external_scheduling_integration.sql` once, after verifying fields/tables are absent. It adds category/format, companion metadata, dataset_metadata, scheduling_plan and scheduling_assignment. It is not auto-run by the application and is not idempotent.
4. Push to GitHub, wait for Actions to publish immutable `v-<commit>` images for practice-server and the discovery Agent. Frontend workflow publishes `v-<short-sha>`.
5. Record currently deployed images. Update only the named workloads in `topic4-1`; retain existing service/env/volumes/roles.
6. Verify rollout, health, invalid pagination, node IDs/status, dataset metadata, and read-only external scheduling datasets. Test mutation logic in automated tests; do not launch real training/migrations as a smoke test.

Rollback: restore previous workload images. Additive schema can remain for the old version; do not drop tables or overwrite user records as part of application rollback. Backups are a disaster-recovery fallback, not an automatic downgrade script.
