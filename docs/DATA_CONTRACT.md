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

The home screen keeps **personal, on-device** planning drafts. They are not a
second API and not the authority for shared household plans; the server remains
the authority once `plan_slots` is implemented.

- Storage: `filesDir/home-plan-drafts/<scope>.json`, written through
  `AtomicFile` and read back byte-for-byte before success is reported.
- Scope: `SHA-256("account:<userId>/<householdId>")`; `signed-out` for the
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
- Version policy: unknown or mismatched `version`/`scope` is rejected rather
  than silently migrated. A format change requires a new version and a
  documented migration.

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
