---
id: IC-20261127-EF-PLAN-PERMISSIONS
type: NORMAL
status: IMPLEMENTING
allowedOperations: [replacePlanSlot, setPlanSlotLock, commitPlan]
allowedSchemas: []
allowedSymbols: []
contractDigest: null
baseSha: 3386f38
headSha: null
---
# Household plan write permissions (Batch 3B)

User explicitly authorized Batch 3B on 2026-11-27 (Asia/Shanghai) after accepting Batch 3A.
Only the 403 error set of the existing plan write operations changes; no request/response schema changes.

## Old contract

- `replacePlanSlot`, `setPlanSlotLock` and `commitPlan` documented 403 only as
  `HOUSEHOLD_ACCESS_REVOKED, FORBIDDEN`; any active member could delete, replace, decrease, clear,
  move or lock plan content.

## New contract

- The three write operations additionally document `PLAN_PERMISSION_DENIED` (HTTP 403).
- The server computes the difference between the persisted slot and the requested slot and permits a
  member only additions and serving increases. Deletions, decreases, replacements, moves between
  date/meal, clearing and lock/unlock require the current household owner.
- `commitPlan` is evaluated as one batch: any owner-only change in any slot rejects the whole batch
  with 403 and no partial write.
- Unknown/non-member callers keep 404; stale versions keep 409 `VERSION_CONFLICT`; replacing a
  locked slot keeps 409.

## Semantics

- Item identity is `(slot, recipeId)`; `plan_items` has a primary key on `(slot_id, recipe_id)` and
  the request validation rejects duplicate recipe ids, so the diff is exact and cannot be bypassed by
  splitting or renaming.
- A recipe present before with a smaller or absent total servings after is a decrease/removal and
  requires the owner; a new recipe id or a larger total is an addition/increase and is member-safe.
- Permission is read from `households.owner_user_id` while holding a `FOR SHARE` lock on the
  household row, so an ownership transfer (`FOR UPDATE`) and a plan write cannot interleave into a
  write authorised by a stale owner.
- Idempotent replay of `commitPlan` returns the stored result after re-checking current membership;
  it never re-executes the write and never applies the owner-only rule to a historical result.

## Compatibility

- Additive error code only; Android clients that do not handle 403 still show a failure, Batch 3C will
  map `PLAN_PERMISSION_DENIED` to a "仅房主可操作" message.
