# Controlled claim procedure for a `PENDING_CLAIM` household

Batch 3A deliberately does **not** expose a self-service owner claim endpoint. Historical households
whose creator could not be identified safely remain `PENDING_CLAIM` with `owner_user_id = NULL`:
members can keep using every non-owner feature (rename, invitations, recipes, inventory, plan
additions, cooking, meal diary), but owner-only actions are rejected with `NOT_HOUSEHOLD_OWNER`.

## Preconditions

- Support/team has verified the claimant's identity out of band (e.g. account recovery, project
  supervisor confirmation for the university deployment).
- The household is still open (`closed_at IS NULL`) and has no owner.
- The claimant is an **active** member of that household.

## Procedure

1. Record the request in the team issue tracker (who, which household, evidence, approver).
2. Check the current state:

```sql
SELECT h.id, h.name, h.owner_source, m.user_id, u.username
FROM households h
JOIN memberships m ON m.household_id = h.id AND m.active
JOIN users u ON u.id = m.user_id
WHERE h.id = '<household-id>' AND h.closed_at IS NULL;
```

3. Claim with the approved user id. The `WHERE` guard makes the update idempotent and prevents
   overwriting a household that was claimed or transferred meanwhile:

```sql
UPDATE households
SET owner_user_id = '<user-id>',
    owner_source = 'CLAIM',
    owner_assigned_at = CURRENT_TIMESTAMP,
    owner_confirmed_at = CURRENT_TIMESTAMP,
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE id = '<household-id>'
  AND closed_at IS NULL
  AND owner_user_id IS NULL
  AND EXISTS (
      SELECT 1 FROM memberships m
      WHERE m.household_id = households.id AND m.user_id = '<user-id>' AND m.active
  );
```

4. Verify (must return one row with the expected user and `CLAIM`):

```sql
SELECT h.id, h.owner_user_id, h.owner_source, h.owner_confirmed_at
FROM households h WHERE h.id = '<household-id>';
```

5. Append the executed SQL, timestamp and operator to the issue record.

## Notes

- Never assign an owner who is not an active member: the ownership CHECK would accept the row but
  the service would treat the household as ownerless for that user.
- Never change `owner_source` to `CLAIM` without setting `owner_user_id`.
- A claimed household behaves exactly like a created one; the new owner can rename, invite,
  transfer ownership, dissolve when sole, and (after Batch 3B) manage plan servings.
