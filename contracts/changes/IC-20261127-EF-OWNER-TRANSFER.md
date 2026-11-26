---
id: IC-20261127-EF-OWNER-TRANSFER
type: NORMAL
status: IMPLEMENTING
allowedOperations: [createHousehold, getHousehold, renameHousehold, removeMember, listOwnershipTransferRequests, requestOwnershipTransfer, acceptOwnershipTransfer, rejectOwnershipTransfer, cancelOwnershipTransfer, dissolveHousehold]
allowedSchemas: [Household, TransferStatus, OwnershipTransferRequest, CreateOwnershipTransferRequest, OwnershipTransferRequestPage]
allowedSymbols: []
contractDigest: null
baseSha: 53c2dda
headSha: null
---
# Household owner identity and two-phase ownership transfer

User explicitly authorized Batch 3A on 2026-11-27 (Asia/Shanghai) after confirming PD-1…PD-7 in
`docs/v2-analysis/BATCH3_PLAN.md`. This record covers the contract additions for owner identity and
the two-phase transfer; it does not authorize Batch 3B (plan permissions) or Batch 3C (Android UI).

## Old contract

- `Household` exposes `id, createdAt, updatedAt, name, zoneId, version` only; there is no owner.
- Household creation writes the creator as a plain `MEMBER`; nobody can be identified as owner.
- No transfer/dissolve operations; leaving is self-only and the last member closes the household.

## New contract

- `Household` gains nullable `ownerUserId` (uuid) and `ownerSource`
  (`CREATED | MIGRATION_SIGNATURE | MIGRATION_SOLE | PENDING_CLAIM | TRANSFER | CLAIM | CLOSED`).
  Both are optional response fields, so older clients remain compatible.
- New `OwnershipTransferRequest` value object + `TransferStatus` enum
  (`PENDING | ACCEPTED | REJECTED | CANCELLED`) + page wrapper.
- New operations (tag `Household`, `x-idempotent`):
  - `listOwnershipTransferRequests` — GET, visible only to the current owner and the target;
    other members receive an empty page.
  - `requestOwnershipTransfer` — POST, owner-only, creates a PENDING request; does not change
    `ownerUserId`.
  - `acceptOwnershipTransfer` — POST, target-only, atomically sets the new owner and resolves the
    request.
  - `rejectOwnershipTransfer` — POST, target-only.
  - `cancelOwnershipTransfer` — POST, owner-only, only while PENDING.
  - `dissolveHousehold` — POST, owner-only and only when the owner is the sole active member;
    closes the household, clears the owner, keeps all historical rows.
- `removeMember` now documents `OWNER_MUST_TRANSFER` and `OWNER_MUST_DISSOLVE` on 409: an owner
  cannot leave a multi-member household and a sole owner must dissolve instead.

## Error codes

| Code | HTTP | Meaning |
| --- | --- | --- |
| `NOT_HOUSEHOLD_OWNER` | 403 | Caller is not the current owner |
| `NOT_TRANSFER_TARGET` | 403 | Caller is not the target member |
| `INVALID_TRANSFER_TARGET` | 400 | Target is not an active member or is the owner |
| `TRANSFER_ALREADY_PENDING` | 409 | A PENDING request already exists for the household |
| `TRANSFER_NOT_PENDING` | 409 | Request was already resolved or the household owner changed |
| `TRANSFER_REQUEST_NOT_FOUND` | 404 | Request does not exist in this household |
| `OWNER_MUST_TRANSFER` | 409 | Owner attempted to leave a multi-member household |
| `OWNER_MUST_DISSOLVE` | 409 | Sole owner attempted to leave without dissolving |
| `HOUSEHOLD_HAS_MEMBERS` | 409 | Dissolve attempted with more than one active member |

## Data

- Additive Flyway migration `V20261127120000__household_owner_and_transfers.sql` adds
  `households.owner_user_id / owner_source / owner_assigned_at / owner_confirmed_at`, backfills
  historical households deterministically, and adds `ownership_transfer_requests` with a partial
  unique index enforcing at most one PENDING request per household.
- Historical backfill: exactly one active member whose `created_at` equals the household's creation
  time → `MIGRATION_SIGNATURE`; otherwise a sole active member → `MIGRATION_SOLE`; otherwise
  `PENDING_CLAIM` with a NULL owner (no automatic grant). Closed households stay ownerless.
- A validated CHECK keeps open households owned unless explicitly `PENDING_CLAIM`; PostgreSQL
  experiments showed that a plain `NOT VALID` owner CHECK rejects every update of a pending row,
  so the OR-form constraint is used instead.

## Compatibility

- Response fields are nullable additions; no existing field changes type or meaning.
- New endpoints are additive; old clients keep working and simply see 409 on an owner leave that
  now requires a transfer or dissolve. Batch 3C will consume the new fields/endpoints.

## Verification

- Server integration tests: creation owner, backfill decisions, transfer state machine, concurrency,
  visibility, leave/dissolve rules, idempotent replay, cross-household access.
- `validateContract` / `checkContracts`; server test suite.
