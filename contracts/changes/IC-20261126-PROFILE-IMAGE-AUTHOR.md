---
id: IC-20261126-PROFILE-IMAGE-AUTHOR
type: NORMAL
status: IMPLEMENTING
allowedOperations: [updateProfile, joinHousehold, listMembers, listPlanSlots, replacePlanSlot, setPlanSlotLock, commitPlan, listPersonalRecipes, getPersonalRecipe, createPersonalRecipe, updatePersonalRecipe, setPersonalRecipeArchived]
allowedSchemas: [UpdateProfileRequest, Membership, MembershipPage, PlanSlot, PlanSlotPage, PersonalRecipe, PersonalRecipePage, CreatePersonalRecipeRequest, UpdatePersonalRecipeRequest]
allowedSymbols: []
contractDigest: null
baseSha: 88f4b2d
headSha: null
---
# Member display names, plan authors and personal recipe images

User explicitly authorized contract changes on 2026-11-26 Asia/Shanghai ("允许修改契约，完成第5，8项，并补全英文资源").

- `PUT /api/v1/me/profile` updates the signed-in user's `displayName` (1–120 characters) and returns the updated `User`. Needed because display names were previously local-only, so other household members could never see them.
- `Membership` gains optional `displayName` / `username` so the member list can show who is in the household without extra N+1 lookups. The server fills them from `users`.
- `PlanSlot` gains nullable `lastEditorId` / `lastEditorName`; the planning service records the member who last replaced or committed the slot and returns their display name. Additive Flyway migration adds `plan_slots.author_id`.
- `PersonalRecipe`, `CreatePersonalRecipeRequest` and `UpdatePersonalRecipeRequest` gain nullable `imageId`, reusing the existing `/media` upload API. Additive Flyway migration adds `personal_recipes.image_id` with a media foreign key.
- All response additions are nullable/optional; no existing field changes shape. Server validates that the referenced media is accessible to the caller before persisting `imageId`.
- Affected: generated client/server from OpenAPI, auth/profile household services, planning service, personal recipes service, Flyway migrations, Android recipe editors/details, plan screen, profile member list and English resources; tests updated.
