-- Household owner identity, deterministic backfill and the two-phase ownership transfer.
-- Additive only: no household, membership or history row is deleted.

ALTER TABLE households ADD COLUMN owner_user_id UUID REFERENCES users(id);
ALTER TABLE households ADD COLUMN owner_source VARCHAR(24);
ALTER TABLE households ADD COLUMN owner_assigned_at TIMESTAMPTZ;
ALTER TABLE households ADD COLUMN owner_confirmed_at TIMESTAMPTZ;

-- Backfill historical households deterministically.
-- 1. exactly one active member joined in the household's creating transaction -> creator candidate;
-- 2. otherwise a sole active member;
-- 3. otherwise leave the household unowned as PENDING_CLAIM (never grant ownership by guessing).
WITH candidates AS (
    SELECT h.id AS household_id,
           (SELECT m.user_id FROM memberships m
             WHERE m.household_id = h.id AND m.active AND m.created_at = h.created_at
             ORDER BY m.id LIMIT 1) AS signature_user,
           (SELECT count(*) FROM memberships m
             WHERE m.household_id = h.id AND m.active AND m.created_at = h.created_at) AS signature_count,
           (SELECT count(*) FROM memberships m
             WHERE m.household_id = h.id AND m.active) AS active_count,
           (SELECT m.user_id FROM memberships m
             WHERE m.household_id = h.id AND m.active
             ORDER BY m.created_at, m.id LIMIT 1) AS earliest_user
    FROM households h
    WHERE h.closed_at IS NULL
)
UPDATE households h SET
    owner_user_id = CASE WHEN c.signature_count = 1 THEN c.signature_user
                         WHEN c.active_count = 1 THEN c.earliest_user END,
    owner_source = CASE WHEN c.signature_count = 1 THEN 'MIGRATION_SIGNATURE'
                        WHEN c.active_count = 1 THEN 'MIGRATION_SOLE'
                        ELSE 'PENDING_CLAIM' END,
    owner_assigned_at = CURRENT_TIMESTAMP,
    owner_confirmed_at = NULL
FROM candidates c
WHERE c.household_id = h.id;

UPDATE households
SET owner_source = 'CLOSED', owner_assigned_at = NULL, owner_confirmed_at = NULL
WHERE closed_at IS NOT NULL;

-- Open households must have an owner unless explicitly waiting for a controlled claim.
-- The PENDING_CLAIM branch is required: a plain owner check also rejects updates of pending rows,
-- which would make renaming such a household impossible.
ALTER TABLE households ADD CONSTRAINT households_owner_when_open CHECK (
    closed_at IS NOT NULL OR owner_user_id IS NOT NULL OR owner_source = 'PENDING_CLAIM');
ALTER TABLE households ADD CONSTRAINT households_owner_source CHECK (
    owner_source IS NULL OR owner_source IN
    ('CREATED', 'MIGRATION_SIGNATURE', 'MIGRATION_SOLE', 'PENDING_CLAIM', 'TRANSFER', 'CLAIM', 'CLOSED'));
CREATE INDEX households_owner_idx ON households(owner_user_id);

CREATE TABLE ownership_transfer_requests (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL REFERENCES households(id),
    requested_by UUID NOT NULL REFERENCES users(id),
    target_user_id UUID NOT NULL REFERENCES users(id),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES users(id),
    CHECK (requested_by <> target_user_id),
    CHECK ((status = 'PENDING' AND resolved_at IS NULL)
        OR (status <> 'PENDING' AND resolved_at IS NOT NULL))
);

CREATE UNIQUE INDEX ownership_transfer_one_pending
    ON ownership_transfer_requests(household_id) WHERE status = 'PENDING';
CREATE INDEX ownership_transfer_household_idx
    ON ownership_transfer_requests(household_id, created_at DESC);
