# Data Contract

Status: Draft

This document is the source of truth for cross-group data. The team must agree on
the final storage representation before implementation. Public contracts must
not be changed silently.

Every contract must define:

- business meaning;
- field names, types, and nullability;
- identifier and relationships;
- ownership: personal or household;
- quantity, unit, date, and time rules;
- empty and error semantics;
- provider and allowed callers;
- related tests.

## Contract status

| Status | Meaning |
| --- | --- |
| Draft | Under discussion; callers must not depend on it |
| Agreed | Approved for implementation |
| Implemented | Provider implementation exists |
| Tested | Contract and integration tests pass |

## Required concepts

The field lists below are provisional and must be finalized by the team.

### User and Preference

Ownership: personal

Suggested fields:

- `userId`
- `displayName`
- `tastePreferences`
- `dietaryRestrictions`

### Household and Membership

Ownership: household

Suggested fields:

- `householdId`
- `name`
- `memberUserId`
- `role`

### Ingredient

Ownership: shared canonical data

Suggested fields:

- `ingredientId`
- `canonicalName`
- `aliases`
- `measurementDimension`
- `defaultUnit`

Rules:

- Integrations use `ingredientId`, not display names.
- Unit conversion is allowed only within one measurement dimension.

### Recipe and RecipeIngredient

Ownership: household-shared recipe with personal wishlist state

Suggested fields:

- `recipeId`
- `name`
- `imageReference`
- `tasteTags`
- `estimatedMinutes`
- `servings`
- `caloriesPerServing`
- `status`: active or archived
- `ingredients`: list of ingredient ID and quantity
- `steps`

Archiving a recipe must not remove historical references.

### Wishlist

Ownership: personal

Suggested fields:

- `userId`
- `recipeId`
- `createdAt`

### InventoryBatch

Ownership: household

Suggested fields:

- `batchId`
- `ingredientId`
- `quantity`
- `unit`
- `storageLocation`
- `expiresAt`
- `receivedAt`

### InventoryMovement

Ownership: household

Suggested fields:

- `movementId`
- `batchId`
- `ingredientId`
- `quantityDelta`
- `reason`
- `actorUserId`
- `requestId`
- `createdAt`

`requestId` supports idempotency.

### MealPlan and PlanItem

Ownership: household

Suggested fields:

- `planId`
- `date`
- `mealSlot`
- `recipeId`
- `servings`
- `locked`
- `createdBy`
- `updatedAt`

### CookingEvent

Ownership: household

Suggested fields:

- `cookingEventId`
- `recipeId`
- `recipeSnapshot`
- `cookedAt`
- `servingsProduced`
- `cookedBy`
- `requestId`

### MealEntry

Ownership: personal

Suggested fields:

- `mealEntryId`
- `userId`
- `mealSlot`
- `actualServings`
- `calories`
- `calorieStatus`: known or unknown
- `recipeSnapshot`
- `photoReference`
- `notes`
- `consumedAt`

## Contract change process

1. Describe the old and new contract.
2. Identify every caller.
3. Update this document.
4. Update provider and callers in a coordinated change.
5. Add or update tests.
6. Run integration checks.
7. Record remaining incompatibilities before requesting review.

## Local BD planning drafts (2026-11-21)

The home screen keeps **personal, on-device** planning drafts, independent of household membership (2026-11-26 user decision). They do not read or write shared household `plan_slots`. Any future personal sync requires an explicit documented contract; implementing shared server plans must not silently change Today ownership.

- Storage: `filesDir/home-plan-drafts/<scope>.json`, written through
  `AtomicFile` and read back byte-for-byte before success is reported.
- Scope (2026-11-26 user decision): `SHA-256("account:<userId>")`, independent of household; `signed-out` for the
  logged-out app and `local-preview` for the debug preview entry. Drafts never
  cross scopes, and a decoded file whose `scope` does not match its filename is
  rejected.
- File shape (version 1):

  ```json
  {
    "version": 1,
    "scope": "<sha-256 hex>",
    "meals": {
      "2026-11-21/BREAKFAST": [
        {
          "id": "<add-operation UUID>",
          "recipeId": "asset recipe id or null",
          "name": "shown to the user",
          "image": "asset:recipes/images/<id>.jpg | local:home-dish-photos/<scope>/<file> | null",
          "sourceUrl": "upstream recipe link or null",
          "ingredients": ["..."],
          "steps": ["..."],
          "nutrition": { "schemaVersion": 1, "inputHash": "...", "basis": "per_100g" }
        }
      ]
    }
  }
  ```

- Add-operation id: reused while a retry is pending, so a repeated confirmation
  cannot append the same dish twice. Dish identity within a slot is that id.
- Image references are validated on decode: only the bundled asset prefix or the
  owning scope's private directory is accepted.
- Nutrition estimates come from the server endpoint described below; the client keeps a
  local copy keyed by a SHA-256 fingerprint of name + ingredients + steps so the dish
  still shows the last estimate offline. Editing a recipe invalidates it.
- No cooking event, inventory movement, meal entry or shared plan write is
  produced from these drafts. A future sync must go through the existing
  plan/records contract change process (currently 501 placeholders).
- Version policy: unknown version / mismatched file scope remains rejected. Version1 optionally records `migratedScopes`; old version1 without it loads with an empty set. Explicit same-account old-scope migration is additive and retains sources/photos. See [BD_PERSONAL_PLAN_HANDOFF.md](BD_PERSONAL_PLAN_HANDOFF.md); current-family and old-null sources are known, other historical scopes require verified ownership.

## Server nutrition estimates (2026-11-21)

The only nutrition path is the contract operation `estimateNutrition`
(`POST /api/v1/nutrition/estimates`); the client no longer talks to a provider directly and
no provider key ships with the app.

- Request: `name` (1-60), `ingredients` (1-30 items, each 1-120), optional `steps` (≤60 items,
  each ≤200). No user id, household id or photo is sent.
- Response: `basis` = `PER_100G`, nullable metrics (`energyKcal`, `carbsG`, `proteinG`, `fatG`,
  `fiberG`, `sugarsG`, `saltMg`), `tips` (1-600), `inputHash`, `providerVersion`, `cached`.
  Unknown stays `null`, never `0`.
- Cache: canonicalized dish text → SHA-256 → one `nutrition_estimates` row (metrics only, no
  dish text), 30-day expiry, shared by all accounts. A cache hit does not consume quota.
- Limits: per-user rolling window (default 60/hour, 20/24h) before a provider call; over the
  limit returns `429 RATE_LIMITED` with `Retry-After`.
- Provider: `CIVA_NUTRITION_PROVIDER` = `auto` (default; DeepSeek when a key is configured,
  deterministic stub otherwise), `deepseek` or `stub`. `CIVA_NUTRITION_API_KEY` never leaves
  the server. Provider failures return `503 NUTRITION_UNAVAILABLE` with `Retry-After`.
- Estimate is approximate: the UI labels it as an AI estimate, and the data is not an
  authoritative calorie record (see product invariants).

## Server inventory (2026-11-23)

C 组的库存功能已并入主体：`server/.../inventory/` 实现 7 个既有 `InventoryApi` 操作 + 新增
`createIngredient`（IC-20261123-INVENTORY-01）。没有新数据库迁移，复用 V1 的
`ingredients`、`ingredient_aliases`、`inventory_batches`、`inventory_movements`。

- 范围：所有库存操作按当前家庭隔离；`Idempotency-Key` 重放返回原结果；`expectedVersion`
  与负库存保护在服务端强制。
- 批次：同食材每次入库是独立批次；`listInventoryBatches` 支持食材与到期状态过滤
  （`EXPIRED`/`EXPIRING`/`FRESH`），排序为到期日升序、未知日期最后。
- 变动：消耗/丢弃/修正均写 `inventory_movements`，数量不得使库存为负；单位换算在客户端
  （kg→G），服务端只接受标准单位。
- 标准食材：`createIngredient` 允许任意英文名 + 单位登记；同名同单位幂等，同名不同单位
  `409 UNIT_CONFLICT`；查询按 NFKC/大小写/空白规范化并支持别名。
- 匹配：`matchRecipes` 只读计算可用量与覆盖率，不做扣减；最终的做饭扣减（`confirmCooking`）
  仍属 records，服务端 501，待后续阶段。
- 客户端：`feature-ac/inventory` 的原生冰箱页；视觉使用 `civa_*` 颜色与 `CivaWidget`
  按钮令牌（C 组 demo 的绿色配色已按共享设计系统重映射）。

## Public recipe catalogue (2026-11-23)

A 组 1093 道与 BD 组 312 道的合并目录（去重后 1307 道）作为**只读**资源放在服务端：
`GET /api/v1/recipe-catalog`（q / category / tag / highProtein / page / size）与
`GET /api/v1/recipe-catalog/{recipeId}`（IC-20261123-CATALOG-01）。

- 表 `recipe_catalog`：无家庭/用户外键；保留 A 组富字段（分类/菜系/口味/餐次/每份与每100g 营养/emoji）
  与自由文本食材（展示用，不强制映射标准食材）。
- 数据由 `scripts/merge_recipe_catalogs.py` 生成，id 为名称派生的确定性 UUID；重名去重优先保留 A 条目，
  并采用 BD 图片（`imageKey=recipes/images/*.jpg`，客户端资源）。
- 未知值（热量/营养/时长）保持 `null`；客户端渲染为"未提供"，不按 0 计算。
- 客户端菜谱 Tab 读该接口并做只读缓存（离线可看已缓存页）；今天页推荐池改为公共目录，
  BD 的 `recipes/catalog.json` 仅作为首次启动的离线兜底。
- 用户自建/家庭菜谱仍是后续阶段（P2/P3），届时通过现有 household recipe 接口并强制标准食材 ID。

## Personal recipes and favourites (2026-11-23)

- 个人菜谱：/api/v1/me/recipes（IC-20261123-PERSONAL-01），表 user_recipes，按 user_id 归属；
  自由文本食材；expectedVersion 乐观并发；Idempotency-Key 保证创建幂等；删除时清理该用户的个人收藏。
- 想吃：/api/v1/me/recipe-favorites（IC-20261123-FAVORITES-01），表 
ecipe_favorites(kind, recipe_id)，
  kind ∈ CATALOG/PERSONAL/HOUSEHOLD；列表内嵌名称/emoji/分类；家庭菜谱收藏校验成员身份。
- 客户端菜谱Tab 提供 公共/我的/想吃 三个来源；公共详情可保存到我的菜谱并收藏；个人详情可编辑/归档/删除。
- 旧 wishlists 仅覆盖家庭菜谱，保留给 P3 家庭菜谱接线时迁移。

## Household recipes (2026-11-23)

既有 RecipeApi 的 8 个操作已实现（契约无改动）：家庭菜谱以标准食材 ID 存储，任意 active 成员可读、
写；创建使用 Idempotency-Key，更新/归档使用 expectedVersion；wishlist 三件套保留。
创建家庭菜谱时在同一事务内把快照写入作者的 user_recipes（在家庭里添加的菜同时进入私人菜谱）。
V20261123203000__BD_starter_ingredient_dictionary.sql 恢复 26 条标准食材字典（此前为空）。
客户端家庭菜谱页面与加入家庭动作在 P3 客户端阶段接入。

## Local display profile and shared UI internals (2026-11-25)

- Existing `User.displayName` (fallback `username`) supplies the initial visible
  profile name; UUID remains separately labelled ID. Login credentials, auth and
  household APIs are unchanged. No server migration or generated API edits.
- EF owns local display overrides, not a second server User model. Version 1
  `files/profiles/<SHA256(userId)>/profile.json` contains `version`, `scope`,
  `name`, `avatar`; validate account scope and atomically write metadata. Names
  are trimmed and validated; avatar references are local validated filenames.
- The optional photo is bounded/EXIF-normalized on a worker, copied to private
  account storage on Save. Cancel discards the draft. Recheck the authenticated
  owner before committing. These local edits do not rename login accounts or
  synchronize across devices; a future server profile API needs its own review.
- `ChefCatView`, `BakeryWelcomeLayout`, `MeowSound` and framework-recreated
  `ProfileEditDialog` are registered as internalSymbols in the public-surface
  draft. Existing host interfaces and module dependency boundaries are unchanged.
- Full schema/ownership and transition notes: [EF_BAKERY_UI_HANDOFF.md](EF_BAKERY_UI_HANDOFF.md).


## Presentation-only cat/media revision (2026-11-25, round 2)

The second visual pass changes shared drawable/color resources, welcome layout,
default avatar selection and app-private intro playback. User/session/profile
metadata version, account scope, image-import persistence, recipe APIs and host
interfaces remain unchanged. No new public symbols, runtime dependencies or
server schema are introduced; legacy ChefCatView remains available to callers.
See [EF_CAT_REFRESH_HANDOFF.md](EF_CAT_REFRESH_HANDOFF.md).


## Membership removal and current-session leave (2026-11-25)

Existing removeMember is implemented using expectedVersion, household/member row locks and inactive membership history. Members can leave themselves; owners can remove ordinary members; owners cannot leave or be removed (OWNER_CANNOT_LEAVE). No new endpoint/schema/migration. AuthRepository.leaveHousehold(ApiCallback<Void>) is an additive helper: captured-user membership lookup, confirmed server delete, household-only invalidation, and getMe reconciliation of unknown network results; login and personal data remain. See [EF_FAMILY_CATALOG_HANDOFF.md](EF_FAMILY_CATALOG_HANDOFF.md) for consumers, errors and tests.

## Invitation receipt and household entry origin (2026-11-25)

Existing createInvitation is implemented locally: OWNER only, 128-bit / 26-character Base32, 24h expiry, single-use, hash-only invitation table. The candidate encrypts the code within idempotency response storage using AES-GCM and a separate persistent private media key, preserving public wire responses. Status: 用户已确认采用；小组整合时仍需按团队流程审阅。
No OpenAPI/public method/schema/migration/dependency changes. Android invitation pending records contain request identity, not returned plaintext code. Internal Fragment arguments persist return origin: initial setup → Today, Profile-origin setup → Profile, for close and successful create/join/leave. Owner leave restriction remains. See [EF_EMPTY_INVITATION_HANDOFF.md](EF_EMPTY_INVITATION_HANDOFF.md) for key lifecycle, replay policy and exact affected files.

## Equal members and permanent invitation (2026-11-25, current)

User explicitly supersedes prior owner-leave / one-use policies. All active users including creator are MEMBER and may leave themselves; none may remove someone else. Last self-leave closes household identity under the same row lock as join; no revival, preserve historical FKs. Additive migration V20261125210000 normalizes active OWNER, adds closure and permanent encrypted invitations. Existing populated homes receive one code on locked startup backfill; old temporary codes cease authorizing joins.
New getHouseholdInvitation GET reads Invitation for any active member, raw ApiCalls (no durable code cache). Required expiresAt is now nullable: JSON null for permanent. Legacy createInvitation POST remains idempotent/deprecated and returns the same fixed code. removeMember keeps version/path but becomes self-only, no OWNER_CANNOT_LEAVE. Constructor provisioner added to HouseholdWriteService and public-surface draft updated. Both generated sides rebuilt from the single spec.
Hash lookup plus AES-GCM sealed_code/private persistent key, never plaintext DB. Persist key and DB together; see [EF_SHARED_FAMILY_HANDOFF.md](EF_SHARED_FAMILY_HANDOFF.md) and [IC](../contracts/changes/IC-20261125-EF-PERMANENT-FAMILY.md) for exact compatibility, migration, affected callers and verification. This local user-authorized implementation is not a claimed team baseline approval.

## Household rename (2026-11-25, current)

New renameHousehold: PUT /api/v1/households/{householdId}, required expectedVersion query, existing CreateHouseholdRequest name body, existing Household response. Any active member; trim/nonblank/max120 Unicode characters; 400invalid, 403revoked, 404nonmember/closed, 409stale version. Lock household before authorization/version check, update name/version/updated_at only; same-name no-op keeps version. No schema migration/new DTO/public host signature/dependency.
EF uses ApiCalls/generated API. Save stays in settings; GET reconciliation on stale/unknown result preserves draft, only explicit retry may overwrite a newly read version. Profile reads shared updated name, fixed invitation/ID unchanged. Registration and exact verification: [IC](../contracts/changes/IC-20261125-EF-FAMILY-NAME.md), [EF_FAMILY_NAME_HANDOFF.md](EF_FAMILY_NAME_HANDOFF.md). Local implementation is user-authorized, not a claimed team contract freeze.


## Tablet presentation only (2026-11-26)

Bottom navigation, vertical meal measurement and explicit welcome-sheet clipping change presentation only. No public signature, DTO, API, schema, ownership, persistence or dependencies changed. See [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md) for the four-file audit; previous family contracts remain unchanged.


## Profile display names, plan authors and personal recipe images (2026-11-26)

Authorized by the user on 2026-11-26 (contract change allowed). Record: contracts/changes/IC-20261126-PROFILE-IMAGE-AUTHOR.md.

- PUT /api/v1/me/profile with UpdateProfileRequest { displayName: 1..120 chars } updates users.display_name and returns the updated User. Display names are now server data, so every household member sees them.
- Membership (and therefore listMembers / joinHousehold) gains nullable displayName and username, filled from users. The Android member list and future member pickers must use these fields.
- PlanSlot gains nullable lastEditorId / lastEditorName: the planning service records plan_slots.author_id on replace, lock/unlock and commit, and joins users.display_name. A null author means the slot predates this change or was never edited.
- PersonalRecipe, CreatePersonalRecipeRequest and UpdatePersonalRecipeRequest gain nullable imageId, referencing an uploaded Media. The server only accepts an image the caller can read (personal owner, or household-scoped media of an active member). The client uploads through the existing /api/v1/media multipart endpoint and shows the image in the detail screen; omitting the field keeps the previous image on update.
- Additive Flyway migration V20261126213000__profile_images_and_plan_author.sql adds user_recipes.image_id and plan_slots.author_id with indexes; no existing column changes.
- UI localization (2026-11-26): all user-visible text now resolves through Android string resources with a complete alues-en set, including login, navigation, recipe/fridge/diary/plan/profile screens. User-entered names, recipe notes and server data are never translated. Language choice persists through LocalePrefs/AppCompat per-app locales and is applied on startup.
