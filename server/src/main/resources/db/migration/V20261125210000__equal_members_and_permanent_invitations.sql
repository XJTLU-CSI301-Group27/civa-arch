-- User-authorized policy change. Preserve household data/history; close identity after last leave.
ALTER TABLE households ADD COLUMN closed_at TIMESTAMPTZ;
DROP INDEX memberships_one_active_owner;
UPDATE memberships SET role='MEMBER',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE role='OWNER' AND active;
ALTER TABLE invitations ADD COLUMN permanent BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE invitations ADD COLUMN sealed_code TEXT;
ALTER TABLE invitations ALTER COLUMN expires_at DROP NOT NULL;
ALTER TABLE invitations ADD CONSTRAINT invitations_lifetime CHECK (
 (permanent AND expires_at IS NULL AND used_at IS NULL AND sealed_code IS NOT NULL)
 OR (NOT permanent AND expires_at IS NOT NULL));
CREATE UNIQUE INDEX invitations_one_permanent_per_household ON invitations(household_id) WHERE permanent;
UPDATE households h SET closed_at=CURRENT_TIMESTAMP,version=version+1,updated_at=CURRENT_TIMESTAMP
 WHERE NOT EXISTS(SELECT 1 FROM memberships m WHERE m.household_id=h.id AND m.active);
