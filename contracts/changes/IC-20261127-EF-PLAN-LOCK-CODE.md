---
id: IC-20261127-EF-PLAN-LOCK-CODE
type: NORMAL
status: IMPLEMENTING
allowedOperations: [replacePlanSlot, commitPlan]
allowedSchemas: []
allowedSymbols: []
contractDigest: null
baseSha: a5fa28e
headSha: null
---
# Align the locked-slot error code with the runtime

The OpenAPI contract documented 409 `PLAN_LOCKED` for `replacePlanSlot` and `commitPlan`, while the
server (and its tests, and the Android client) use `PLAN_SLOT_LOCKED`. This is a documentation-only
alignment: the runtime, HTTP status (409) and Android handling are unchanged, and Android already
handled both spellings. Batch 3C-3 requested a minimal, safe correction rather than a runtime change.

- `replacePlanSlot` 409 enum: `VERSION_CONFLICT, PLAN_SLOT_LOCKED`
- `commitPlan` 409 enum: `IDEMPOTENCY_KEY_REUSED, VERSION_CONFLICT, PLAN_SLOT_LOCKED`
- No schema, request/response body or status-code change.
- Android `PlanFragment` maps both `PLAN_SLOT_LOCKED` and `PLAN_LOCKED`, so older cached clients remain
  compatible.
