---
id: IC-20261125-EF-PERMANENT-FAMILY
type: NORMAL
status: IMPLEMENTING
allowedOperations: [getHouseholdInvitation, createInvitation, createHousehold, joinHousehold, removeMember]
allowedSchemas: [Invitation, Role]
allowedSymbols: [edu.xjtlu.civa.server.household.HouseholdWriteService]
contractDigest: null
baseSha: null
headSha: null
---
# Equal family members and permanent household invitation

User explicitly authorized this policy change on 2026-11-25 Asia/Shanghai, superseding their prior owner-leave restriction. This is a local downloaded copy without Git history or Issue; not represented as EF/team approval or latest-main integration.

- All active members, including creator, have equal MEMBER privileges. Each may leave only themselves; nobody can remove another member. OWNER remains a historical compatibility enum, not a privilege.
- Creation atomically provisions one persistent random 128-bit Base32 code; any active member reads it via new GET household invitations. Existing POST is deprecated, idempotent and returns the same permanent code, never rotates it.
- Invitation.expiresAt is required nullable: null means no time expiry. Multiple joins do not consume it. Old temporary invitations cease granting entry. Existing populated homes get one fixed code on guarded startup backfill.
- The last self-leave closes the household under the same row lock as joining; closed ID/code cannot be revived. Soft closure preserves historical foreign keys/personal records.
- Additive Flyway migration adds closed_at, permanent/sealed_code and fixed-code uniqueness; converts active OWNER rows to MEMBER. No old migration edits, no wiping data. Hash and separately keyed AES-GCM code remain protected.
- Affected: app EF settings/profile; generated client/server from OpenAPI; core-data raw response validation; household services; DB migration; tests and shared docs. BiConsumer provisioning dependency is supplied by server configuration; direct executor test supplies its explicit fixture provisioner.
- Recovery errors that definitively failed clear their durable pending record. Unknown transport results retain same-key retry behavior. Joined settings never exposes setup/recovery controls.

Validation and migration backup results will be recorded in docs/EF_SHARED_FAMILY_HANDOFF.md. Team review is still required before merging; no approval metadata or contract guard waiver is fabricated.
