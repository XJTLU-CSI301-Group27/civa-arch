---
id: IC-20261125-EF-FAMILY-NAME
type: NORMAL
status: IMPLEMENTING
allowedOperations: [renameHousehold]
allowedSchemas: []
allowedSymbols: []
contractDigest: null
baseSha: null
headSha: null
---
# Family name editing in joined settings

2026-11-25 Asia/Shanghai: user explicitly requests family name input, save, and upward grouped controls. Existing API only reads/creates households, so add PUT /api/v1/households/{householdId}, operation renameHousehold, expectedVersion query and reuse CreateHouseholdRequest name body. Return existing Household; any active member may rename. Lock household before membership/version checks to serialize rename with leave/join; no schema migration/new DTO/dependency. Blank/overlong rejected; stale version409; no-op same-name retains version. Fixed ID/invitation remain unchanged. EF uses ApiCalls with version checking and GET reconciliation for conflicts/unknown transport, preserving unsaved input and not silently overwriting another member's edit.

Joined-only layout uses top gravity/30dp padding. Setup and Login layouts unchanged. Header says 修改家庭名, loaded current name is editable; fixed invitation/copy remain, then Save and Leave. Saving stays in settings and confirms success; closing returns Profile which reads the shared name.

Local user-authorized implementation, not a fabricated EF/team approval, GitHub Issue or latest-main integration. Team review required before merge. Verification and file audit recorded in docs/EF_FAMILY_NAME_HANDOFF.md.
