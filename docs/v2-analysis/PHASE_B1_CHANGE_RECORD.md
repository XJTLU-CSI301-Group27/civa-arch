# Phase B1 变更记录：RECIPE_ONLY 数据模型、服务端读写与契约

日期：2026-11-27（Asia/Shanghai）
基线：`4042aaf`（设计文档）
状态：仅 B1；未实施 Android 编辑器、导入处置、自定义食材/图片。

## 1. 数据库迁移

新增：

`server/src/main/resources/db/migration/V20261127130000__recipe_only_ingredients.sql`

```sql
ALTER TABLE recipes
    ADD COLUMN recipe_only_ingredients jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE recipes
    ADD CONSTRAINT recipes_recipe_only_ingredients_check
        CHECK (jsonb_typeof(recipe_only_ingredients) = 'array');
```

- 版本号大于现有 `20261127120000`，兼容 PostgreSQL 16/Flyway。
- 已有菜谱默认空数组；不修改 `recipe_ingredients`、quantity/unit、库存批次、CookingEvent、InventoryMovement。
- 不迁移历史 `track_stock=false`。
- 开发库迁移已通过启动新服务执行并记录成功；测试库由测试启动时 Flyway 迁移。

### 备份

- 迁移前开发库 `civa` 已 `pg_dump -Fc` 到：
  `review/civa_phase_b1.dump`
- 用 `pg_restore --list` 验证可读。
- 另恢复到临时库 `civa_b1_restore_check`，确认备份可恢复（8 recipes；无新列），随后删除临时库。

## 2. OpenAPI 变化

新增 `RecipeOnlyIngredient`：

```json
{
  "name": "string(1..120), required",
  "amountText": "nullable string(<=200)",
  "note": "nullable string(<=500)"
}
```

`Recipe`、`CreateRecipeRequest`、`UpdateRecipeRequest`：

- 增加 `recipeOnlyIngredients` 数组（max 100，nullable）；
- `ingredients` 的 `minItems` 从 1 改为 **0**，允许只有说明辅料的菜谱；
- `RecipeOnlyIngredient.additionalProperties=false`；
- 创建/更新请求、响应字段均走同一 Contract。

服务端生成器增加：

```json
"containerDefaultToNull": true
```

原因：生成模型默认把可选数组初始化为空列表，导致 **Update 请求未传字段也变成 `[]`，会误清空辅料**。设为 null 后：

- Update 未传/显式 null → `null` → 保留旧值；
- Update `[]` → 空列表 → 清空；
- Create 未传/null → null → 按空数组保存。

Android 生成客户端仍默认空数组；B1 的 `HouseholdRecipeEditorFragment` 更新时显式 `.recipeOnlyIngredients(null)`，避免旧菜谱被清空。Phase A 编辑器接入后会按实际行设置列表。

## 3. 创建/更新/读取语义

### Create

- `recipeOnlyIngredients` 缺失或 null → 保存 `[]`。
- 传入数组 → 按数组顺序保存。
- 允许 `ingredients=[]` 且 recipe-only 非空（只有辅料的菜谱）。
- `ingredients` 与 recipe-only 不能同时为空。
- TRACKED 和 RECIPE_ONLY 在同一个事务写入；失败无部分数据。

### Update

| 请求值 | 语义 |
| --- | --- |
| 字段未提供 | 保留 |
| 显式 null | 保留 |
| `[]` | 清空 |
| 非空数组 | 替换全部，保留顺序 |

- 所有字段更新仍用 `expectedVersion` 乐观锁；版本递增一次。
- 任意校验/DB 失败整个事务回滚。
- `ingredients` 更新仍替换 `recipe_ingredients`；recipe-only 独立更新。
- 旧客户端只改名称/时间/步骤、不传新字段时保留辅料。

### Read/List

- `Recipe` 统一由 `HouseholdRecipeService.map` 输出，`recipeOnlyIngredients` 从 JSONB 读取；旧记录返回 `[]`。
- 列表、详情、创建/更新/归档响应复用同一 mapper。
- 顺序为 JSONB 数组顺序；Unicode 原样保留。

## 4. 个人快照

`HouseholdRecipeService.snapshot`：

- TRACKED 继续写为个人菜谱规范 ingredient（name/quantity/unit）。
- RECIPE_ONLY 追加到个人菜谱的自由文本 `ingredients` JSON：
  - `name` 原样；
  - `quantity` = amountText（可为 null）；
  - `unit` = null；
  - `note`、`sourceText` 保留说明；
- 不伪造数量，不写库存。

现有测试验证快照包含“食盐/适量”“酱油/2 勺”“香菜”。

## 5. 库存与做饭

- `InventoryCalculator`、`matchRecipes`、`previewCooking`、`confirmCooking` 未改。
- RECIPE_ONLY 只存在于 `recipes` JSONB，不进入 `recipe_ingredients`，因此：
  - 不参与 ENOUGH/MISSING/UNKNOWN；
  - 不产生缺料；
  - 不参与分配/扣减/InventoryMovement。
- 只有 recipe-only 的菜谱：
  - 创建成功（`ingredients=[]`）；
  - match 返回 `lines=[]`、`coverage=1`；
  - cooking preview 的 allocations/shortages 为空；
  - 无库存流水。
- 历史 `track_stock=false` 行仍保留并跳过；已加测试验证 GET 保留 false，match 只返回 true 行。

## 6. 测试与结果

新增 `HouseholdRecipeOnlyHttpIntegrationTest`：

- 食盐 适量、酱油 2 勺、香菜无用量：创建/读取/列表、顺序、Unicode；
- 个人快照保留说明；
- 旧客户端未传字段保留；显式 null 保留；`[]` 清空；非空替换；
- 只有 RECIPE_ONLY 的菜谱：match 空行、preview 无分配/缺料、无库存流水；
- 空名称、超长 amount、未知字段、非成员、版本冲突；
- 更新失败不产生部分辅料；
- 创建失败不留下菜谱/快照；
- 历史 false 行保留且不进入匹配。

| 命令 | 结果 |
| --- | --- |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **201 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `.\gradlew.bat validateContract`（checkContracts 内） | 通过 |
| `git diff --check` | 无空白错误 |

## 7. 数据库验证/清理

- 开发库迁移后：
  - `flyway_schema_history` 最新 `20261127130000 recipe only ingredients`，success=t；
  - `recipes.recipe_only_ingredients jsonb NOT NULL`；
  - 历史 recipes 全部空数组；
  - recipe_ingredients 仍 9 行，库存批次 12、CookingEvent 5、InventoryMovement 11，未变化（以迁移前/后查询核对）。
- 临时恢复库 `civa_b1_restore_check` 已删除。
- 未删除开发库任何业务数据。

## 8. 未完成/未执行

- Android 结构化编辑器（Phase A）未做。
- ImportReviewDialog 的 recipe-only 处置未做（Phase D）。
- 自定义食材/图片（Phase C）未做。
- 旧版 APK 的 **实际旧客户端二进制** 请求未单独安装验证；服务端已用“字段缺失”的 HTTP 请求覆盖兼容语义。
- 仅 recipe-only 菜谱的 **confirmCooking** 未单独 HTTP 测试；preview 已通过，匹配/扣减不受影响。
- 长文本/极端 JSON 负载的 fuzz 未做。
- 开发库迁移后未做全量设备回归（本轮无 Android UI 改动）。

## 9. 剩余风险

- `containerDefaultToNull` 是**服务端生成器全局设置**，改变了所有可选数组的默认值；本轮 105 个服务端测试通过，但后续新增服务端模型需注意 null 处理。
- Android 生成客户端仍把可选数组默认初始化空；B1 仅在家庭菜谱编辑器更新时显式置 null。Phase A 必须继续确保未编辑 recipe-only 时发送 null/省略，而不是 []。
- `recipeOnlyIngredients` 在响应 schema 中 nullable；服务端实际始终返回数组，但旧客户端/未来客户端需处理 null。
- `Recipe.ingredients` 现在允许空数组；Phase A UI 需要明确“只有辅料”的菜谱展示。
- 历史 false 行保留，但不是新 RECIPE_ONLY 的正式表示；Phase A 编辑器需显式处理。
- 迁移回滚会丢失新 recipe-only 数据，必须备份。

## 10. Phase A 前置条件

1. 接受本 B1 的 JSONB/Contract/服务端语义并验收。
2. Phase A 编辑器必须区分 TRACKED/RECIPE_ONLY，切换保留双状态。
3. Android 更新请求未修改辅料时传 null/省略，不能传 []。
4. 编辑器保存 `ingredients=[]` + recipe-only 的菜谱需要 UI 支持。
5. 详情/列表展示 recipe-only；库存匹配不读取。
6. 历史 false 行按“旧数据”展示，不自动转 recipe-only。
7. 需要继续使用个人快照的自由文本 ingredients 保留说明。
