# Civa V2 菜谱食材结构化与库存管理简化设计分析

日期：2026-11-27（Asia/Shanghai）
基线：`cf6a593`
状态：**仅分析与设计，不修改业务代码、数据库、Flyway 或 OpenAPI。**

## 1. 结论摘要

- 现有 `trackStock=true` 可以直接表示 **TRACKED**；`trackStock=false` **不能**直接表示新的 **RECIPE_ONLY**，因为该行仍要求 `ingredientId`、`quantity`、`unit`，并通过单位复合外键约束，无法保存“适量/2 勺”的自由文本。
- 推荐 **不修改 `recipe_ingredients`**：新增 `recipes.recipe_only_ingredients JSONB`（独立于库存食材），保存 `{name, amountText, note?}`。库存匹配、做饭分配、扣减完全不受影响。
- 现有 `trackStock=false` 历史行保留为“旧数据：不参与库存”，不自动迁移；编辑时显式转换，避免丢失。
- 公共/个人菜谱导入的 A/B 仍可默认 TRACKED；C 类由用户明确选择 **RECIPE_ONLY**（保留原始用量文本）或补齐为 TRACKED，**不得自动全部转为 RECIPE_ONLY**。
- 自定义食材当前是**全局**模型：`ingredients` 无 household/owner/image/category，`ingredient_aliases.normalized_name` 是全局主键，创建时跨家庭去重。若要满足“家庭自定义且不互相污染”，必须引入 household scope，属于 **Phase C 的较大迁移**，不建议与编辑器改造混在一起。
- 图片可以复用现有 `media` + `MediaImages`，但当前 media 只有 PERSONAL/HOUSEHOLD 两种 scope；自定义食材建议 household-scoped + HOUSEHOLD 媒体。全局内置食材图片需要额外决策（家庭覆盖或 SYSTEM media）。
- 建议分阶段：**B 数据模型 → A 结构化编辑器 → D 导入适配 → C 自定义目录/图片**。课程剩余时间下，优先 A+B，C 单独批准。

## 2. 当前架构与限制

### 2.1 数据表

| 表 | 关键字段/约束 | 对本期的影响 |
| --- | --- | --- |
| `recipes` | `steps jsonb`、`yield_servings>0`、`duration_minutes 1..1440`、`version` | 有 JSONB 先例；可加 recipe-only JSON |
| `recipe_ingredients` | `quantity numeric(18,3) NOT NULL CHECK >=0`、`unit varchar(5) NOT NULL`、`track_stock boolean NOT NULL`；主键 `(recipe_id, ingredient_id)`；FK `(ingredient_id,unit) → ingredients(id,unit)` | 不能存 NULL/自由文本；同一菜谱不能重复 ingredientId |
| `ingredients` | `id,name,unit,active`；无 household/owner/image/category | 当前是全局目录；无法表达家庭自定义隔离 |
| `ingredient_aliases` | `normalized_name` 全局主键；`ingredient_id` | 不同家庭不能创建同名别名；全局去重 |
| `inventory_batches` | FK `(ingredient_id,unit)`；quantity/expiry | 单位/食材 ID 必须一致 |
| `media` | `scope` 仅 PERSONAL/HOUSEHOLD；owner/household；jpeg/png ≤5MB | 可复用上传/读取；无 SYSTEM/PUBLIC |
| `recipe_catalog` / `user_recipes` | `ingredients` 为自由文本 JSON（name/quantity/unit/quantityPrefix/note/sourceText） | 导入源；与家庭结构不同 |

### 2.2 当前调用链

```
matchRecipes → InventoryCalculator
  → 只读取 recipe_ingredients；track_stock=false 行被跳过
  → required = quantity × servings / yield
  → ENOUGH/MISSING/UNKNOWN

previewCooking → confirmCooking → CookingEvent + InventoryMovement
  → 只为 tracked 行分配/扣减
  → 缺料阻止确认

RecipeStockPresenter
  → 只使用 match lines；lines 为空时 ENOUGH/无需库存
```

`RecipeStockPresenter` 的语义不需要改变；只要 RECIPE_ONLY 不写入 `recipe_ingredients`，现有匹配/扣减自然忽略它。

### 2.3 当前编辑与导入

- `HouseholdRecipeEditorFragment` 仍是“名称 数量”多行 EditText；数量/单位/是否追踪没有结构化行。
- 编辑已有菜谱时显示单位提示；保存使用严格 `request`，不默认 1。
- `IngredientImportParser` 已区分 A/B/C；`ImportReviewDialog` 目前要求 C 行补齐为规范单位数值后才能创建。
- `HouseholdRecipes.request` 只生成 `RecipeIngredient`（trackStock=true）；没有 recipe-only 通道。

### 2.4 当前全局食材创建

`InventoryService.ingredients()` 无 household 参数，返回所有 active 食材。
`createIngredient` 要求用户有 active household，但会按全局 normalized name 查找/去重；同名跨家庭会复用同一个 ingredientId，单位冲突报 `UNIT_CONFLICT`。
**因此当前“自定义食材”天然是全局共享的，不能直接满足家庭隔离。**

## 3. 新旧业务模型对比

| 维度 | 旧模型 | 新模型 |
| --- | --- | --- |
| 家庭菜谱食材 | 全部 `recipe_ingredients`，每行有 id/quantity/unit/trackStock | TRACKED 保留原表；RECIPE_ONLY 存 recipe-only JSON |
| 自由文本 | 仅公共/个人菜谱 JSON；家庭菜谱不支持 | 家庭菜谱 recipe-only 支持自由文本 |
| 库存匹配 | 所有 `track_stock=true` 行 | 仅 TRACKED 行 |
| 做饭扣减 | tracked 行 | 仅 TRACKED 行 |
| `trackStock=false` | 跳过匹配/扣减；仍要求结构化字段 | 仅作为历史兼容，不用于新 RECIPE_ONLY |
| 导入 C 类 | 必须补齐为规范单位/数值 | 可明确选择 RECIPE_ONLY 保留原文，或补齐为 TRACKED |
| 食材目录 | 全局、无图片/类别/家庭 | 目标：全局内置 + 家庭自定义（图片/类别/默认建议） |

明确取消 `PRESENCE_ONLY`；只有 TRACKED 和 RECIPE_ONLY 两类。

## 4. 建议的数据结构

### 4.1 RECIPE_ONLY：`recipes.recipe_only_ingredients JSONB`

推荐最小方案：

```sql
ALTER TABLE recipes
  ADD COLUMN recipe_only_ingredients jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE recipes
  ADD CONSTRAINT recipes_recipe_only_ingredients_check
  CHECK (jsonb_typeof(recipe_only_ingredients) = 'array');
```

运行时结构：

```json
[
  { "name": "食盐", "amountText": "适量", "note": null },
  { "name": "酱油", "amountText": "2 勺", "note": null },
  { "name": "香菜", "amountText": "根据个人口味添加", "note": null }
]
```

- `name` 必填；`amountText` 必填；`note` 可选。
- 数组顺序即展示顺序。
- 不需要 ingredientId；不需要 unit/quantity；不参与 FK。
- 不进入 `recipe_ingredients`、`inventory_batches`、`InventoryCalculator`、`CookingEvent`。
- 不污染库存统计/分配。

**为什么不用新表？**
JSONB 足够表达纯描述性数据，且 `recipes` 已有 `steps` JSONB 先例；新表会增加迁移、排序、版本更新和 API 映射成本。若未来 recipe-only 需要结构化元数据（图片、链接、字典引用），再拆表。

**为什么不全塞进 `recipe_ingredients`？**
现有列 NOT NULL、单位 FK、主键 `(recipe_id, ingredient_id)` 不允许“盐 适量”这种无规范单位/无 ingredientId 的行；为了兼容它需要放宽约束/改主键/伪造 ID，污染库存语义。

### 4.2 TRACKED：保持 `recipe_ingredients` 不变

- `trackStock=true`。
- 仍然使用规范 `ingredientId`、quantity、unit。
- 继续参与匹配/分配/扣减。
- 同一 ingredientId 不可重复（现有 PK）；结构化编辑器应阻止重复行。

### 4.3 历史 `trackStock=false`

- 保留原行，不迁移、不改数量。
- 计算器继续跳过，做饭不扣减。
- 编辑器显示为“旧数据：不参与库存”，保存时原样保留（或用户显式转为 RECIPE_ONLY）。
- 新 UI/导入不再创建新的 false 行。

### 4.4 自定义食材（Phase C）

建议 household-scoped，而不是全局共享：

```sql
ALTER TABLE ingredients
  ADD COLUMN household_id uuid NULL,      -- NULL = 内置/全局
  ADD COLUMN created_by uuid NULL,
  ADD COLUMN image_id uuid NULL REFERENCES media(id),
  ADD COLUMN category varchar(40) NULL,
  ADD COLUMN default_track_stock boolean NOT NULL DEFAULT true;
```

但仅加列不够；现有 `ingredient_aliases.normalized_name` 是全局主键。需要同步改为按 household scope：

- 新增 `scope_household_id uuid NULL`（或等价 scope key）；
- 将唯一约束改为 `(scope_household_id, normalized_name)`；
- 全局行使用 NULL scope，家庭行使用 household id；
- 查询/创建/重名判断都带当前 household；
- 所有 inventory/recipe 写接口校验 ingredient 属于 global 或当前 household。

这是 Phase C 的主要迁移成本；不建议在本期编辑器改造中顺手做。

## 5. 数据库变更方案

### 5.1 Phase B 迁移

```sql
ALTER TABLE recipes
  ADD COLUMN recipe_only_ingredients jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE recipes
  ADD CONSTRAINT recipes_recipe_only_ingredients_check
  CHECK (jsonb_typeof(recipe_only_ingredients) = 'array');
```

- 不修改 `recipe_ingredients`。
- 不重写历史行（默认空数组）。
- 不修改 `recipes` 版本语义；更新仍走 `expectedVersion`。
- 回滚：`DROP COLUMN` 会丢失新辅料数据，必须先备份；不建议无备份回滚。

### 5.2 Phase C 迁移（仅方案，不执行）

1. `ingredients` 增加 household_id/created_by/image_id/category/default_track_stock。
2. 现有 36 个内置食材 household_id 保持 NULL。
3. `ingredient_aliases` 增加 scope_household_id；重建唯一索引：
   - global: `(scope_household_id IS NULL, normalized_name)`；
   - household: `(scope_household_id, normalized_name)`；
   - 需要处理现有全局唯一名与家庭名冲突。
4. 添加 `media` FK；图片使用 HOUSEHOLD media。
5. 写接口校验 ingredient 可见性。
6. 回滚需要恢复别名主键与删除 household 自定义行；先导出/备份。

### 5.3 不建议的迁移

- 把 `recipe_ingredients.quantity/unit` 改为 nullable：会影响 cooking 分配、库存校验、所有现有数据。
- 用 `quantity=0/1`、`trackStock=false` 表达自由文本：语义错误，明确禁止。
- 批量把历史 false 行改成 recipe-only：会改变历史数据；只允许用户显式转换。

## 6. OpenAPI 变更矩阵

### Phase B

| Schema/Operation | 变更 | 兼容性 |
| --- | --- | --- |
| `RecipeOnlyIngredient`（新增） | `name`, `amountText`, `note?` | 新 schema |
| `Recipe` | 增加 `recipeOnlyIngredients: RecipeOnlyIngredient[]`（响应） | 可选/新增字段，旧客户端忽略 |
| `CreateRecipeRequest` | 增加可选 `recipeOnlyIngredients` | 旧请求不传 = [] |
| `UpdateRecipeRequest` | 增加可选 `recipeOnlyIngredients` | 旧请求不传时怎么办需明确：建议 null=保留，[]=清空 |
| `RecipeIngredient` | 不变 | — |
| `RecipeMatch` / `CookingPreview` | 不变 | — |
| 导入 API | 无新端点；复用 CreateRecipeRequest | — |

### Phase C

| Schema/Operation | 变更 |
| --- | --- |
| `Ingredient` | `imageId?`, `category?`, `householdId?`, `defaultTrackStock?` |
| `CreateIngredientRequest` | `imageId?`, `category?`, `defaultTrackStock?`, household scope（query/body） |
| `UpdateIngredientRequest`（新增） | 图片/类别/默认建议/别名 |
| `GET /ingredients` | 支持 household scoped 过滤（或 `/households/{id}/ingredients`） |
| `Media` | 可保持不变（HOUSEHOLD scope）；若支持全局内置图片需新增 SYSTEM/PUBLIC scope 或覆盖表 |
| `POST /ingredients` | 校验 household membership、名称 scope、单位冲突 |
| `PATCH /ingredients/{id}` | 新增 |

## 7. Android UI 设计

### 7.1 编辑器结构

在 `HouseholdRecipeEditorFragment` 内做最小改造，不新建菜谱模块：

- 保留名称、图片、份数、时间、步骤。
- 把“名称 数量”多行 EditText 替换为 `RecyclerView`（现有依赖），行布局：

```
[食材名称 AutoCompleteTextView] [数量/自由文本] [单位 Spinner] [库存管理 Switch] [删除]
```

- TRACKED 行：
  - 名称：字典搜索/选择；可“新建食材”。
  - 数量：数字键盘；单位：G/ML/COUNT（由食材规范单位决定，UI 只展示/确认）。
  - 库存管理：开。
- RECIPE_ONLY 行：
  - 名称：自由文本；不要求字典。
  - 用量：单个自由文本（“适量/少许/2 勺/根据口味”）。
  - 库存管理：关；隐藏数字/单位控件。
- 新行默认 TRACKED；用户可立即切换。
- 删除/新增行。
- 校验：名称、tracked 正数/单位、recipe-only 非空用量；错误显示在行下方 + 保存按钮阻止。
- 可在列表上方分两段“库存食材 / 仅说明辅料”，避免混合排序问题。

### 7.2 切换不丢数据

每行保留两个状态：

```java
String name;
boolean tracked;
String trackedQuantity;
Unit trackedUnit;
String recipeOnlyText;
```

- TRACKED → RECIPE_ONLY：把 `quantity + unit` 生成到 `recipeOnlyText`（若为空）；保留 tracked 字段。
- RECIPE_ONLY → TRACKED：保留原 recipeOnlyText；数量/单位不自动猜测；用户必须填写有效值。
- 切换只改变显示/保存通道，不 silently 清空另一侧。
- 保存时只提交当前模式的数据。

### 7.3 RecyclerView 注意点

- 使用 `NestedScrollView` 承载现有页面，`RecyclerView` 设 `nestedScrollingEnabled=false`，或整页改为 RecyclerView（header/footer）；最小改动选前者。
- ViewHolder 中的 TextWatcher 要先 detach 再 bind，避免复用串值。
- 每行稳定 ID/状态对象；不要只靠 EditText 文本恢复。
- 旋转：用 Fragment `onSaveInstanceState` 或 `ViewModel` 保存行状态；当前编辑器是普通字段，需补。
- 图片选择继续用现有 `ActivityResultContracts.GetContent` + `MediaImages`。

### 7.4 导入核对 UI

在 `ImportReviewDialog` 增加每行“库存管理”选择：

- A/B：默认 TRACKED；用户可改为 RECIPE_ONLY（保留原文）。
- C：默认 **未选择**（不得自动 recipe-only）；用户选择：
  - “仅说明” → 保存原始 `amountText`，不要求数字/规范单位；
  - “计入库存” → 选择字典食材并填写规范数量/单位（现有逻辑）。
- 任何被标为 recipe-only 的行不得进入缺料/扣减。
- 原有 A/B/C 解析器不改；只增加处置方式。
- 保存前校验所有行已选择；C 未选择不能保存。

## 8. 食材图片存储与展示

### 8.1 可复用部分

- `MediaImages.upload(...)`：jpeg/png ≤5MB，支持 PERSONAL/HOUSEHOLD。
- `MediaImages.load(...)`：通过 `/media/{id}/content` 加载并显示。
- `Media` 的读取权限：owner 或 household active member。
- 现有图片选择器在家庭菜谱编辑器（GetContent）。

### 8.2 自定义食材

- `ingredients.image_id UUID NULL REFERENCES media(id)`。
- 自定义食材 household-scoped 时，上传 `MediaScope.HOUSEHOLD` + householdId；同家庭成员都能查看。
- `Ingredient.imageId` 返回给客户端；列表/冰箱用 `MediaImages.load`。
- 现有媒体缓存：`MediaImages.load` **没有缓存**，每次重新请求/解码；食材列表应加内存 LruCache 或 OkHttp disk cache，避免滚动重复请求。

### 8.3 内置/全局食材图片

当前 media 没有 SYSTEM/PUBLIC scope，不能直接给全局食材挂一个所有家庭可读的图片。两个选项：

1. Phase C 只支持家庭自定义食材图片；内置食材使用 emoji/占位图，不阻塞。
2. 增加 `MediaScope.SYSTEM`（或 PUBLIC）并调整 media check/get 权限；或者新增 `household_ingredient_images` 覆盖表（household_id, ingredient_id, image_id），允许每个家庭给全局食材补图，但图片不共享。

“已有食材能否补充图片”需产品决定：推荐先支持**家庭覆盖图**，避免全局媒体权限扩大；自定义食材图片直接挂 ingredient.image_id。

## 9. 库存匹配与扣减影响

| 操作 | TRACKED | RECIPE_ONLY |
| --- | --- | --- |
| matchRecipes | 参与 required/available/status | 不出现 |
| previewCooking | 参与 allocations/shortages | 不出现 |
| confirmCooking | 只扣 tracked | 不扣 |
| cooking event | 只记录 tracked | 仅说明 |
| InventoryMovement | 只 tracked | 无 |
| meal entry/diary | 不扣库存 | 不扣库存 |
| RecipeStockPresenter | lines 只含 tracked | 不影响 |
| 统计 | 计入 | 不计入 |

服务端不需要修改 `InventoryCalculator`；只需要 `Recipe` 读写 recipe-only 字段，并确保 `preview/confirm` 不读取它。`HouseholdRecipeService.snapshot` 需要把 recipe-only 复制到个人快照的自由文本 ingredient（可选，但避免丢失展示）。

## 10. 历史数据兼容

1. 现有家庭菜谱可直接打开；新列默认 `[]`。
2. `trackStock=true` 行完全按原逻辑计算。
3. `trackStock=false` 行保留在原表，计算器继续跳过；不批量迁移。
4. 历史库存批次、CookingEvent、InventoryMovement 不变。
5. 不批量改写历史 recipe_ingredients/quantity。
6. 编辑器对 false 行显示“旧数据：不参与库存”，保存时原样保留；用户可显式转换到 recipe-only（把 quantity+unit 转 amountText）。
7. 导入严格解析与人工核对继续有效；新 recipe-only 通道不会自动改历史。
8. 历史错误导入数据（默认 1/错误单位）不自动修复；用户手动编辑后 4A 状态重新计算。

开发库当前没有 `track_stock=false` 行（查询为 0），但生产/其他环境必须按上述兼容设计。

## 11. 导入解析兼容

- 现有 `IngredientImportParser` 保留 A/B/C 分类，不改解析规则。
- 导入核对增加每行“处置方式”：
  - A/B 默认 TRACKED；
  - C 默认需要用户选择，“仅说明”或“计入库存”；
  - 用户可把 A/B 也改为 RECIPE_ONLY。
- C 选择 RECIPE_ONLY：
  - `RecipeOnlyIngredient.amountText` 保存原始 `quantityPrefix + quantity + unit + note/sourceText` 的可读文本，如“适量”“2 勺”；
  - 不要求 ingredientId、不要求规范单位、不进入库存。
- C 选择 TRACKED：继续现有修正/严格校验。
- 禁止“解析失败就自动全变 RECIPE_ONLY”；必须逐行显式确认。
- `verifiedRequest` 拆分 tracked ingredients + recipe-only list；重复/单位/数量校验仅针对 tracked。
- 公共/个人导入后，recipe-only 不参与 `matchRecipes`。

## 12. Android 修改范围（预估）

| 文件 | 改动 |
| --- | --- |
| `HouseholdRecipeEditorFragment` | RecyclerView、行状态、校验、模式切换、保存 |
| 新 `IngredientRowAdapter` / row XML | 结构化行 |
| `ac_fragment_household_recipe_editor.xml` | 替换 ingredients EditText；保留其他字段 |
| `ImportReviewDialog` | 每行处置方式/recipe-only |
| `HouseholdRecipes` / 请求构造 | tracked + recipe-only |
| `RecipeCatalogDetailFragment` | 详情显示 recipe-only；做饭说明 |
| `Ingredient` UI（Phase C） | 自定义创建/搜索/图片 |
| `MediaImages` | 可选 LruCache |
| tests | parser/request/editor state/import/history |

## 13. 测试矩阵

| 场景 | 期望 |
| --- | --- |
| TRACKED 行 | 参与匹配/缺料/扣减 |
| RECIPE_ONLY 行 | 不出现于 match/preview；不扣库存 |
| 食盐 适量 | 保存原文；不要求 ingredientId/数字 |
| 酱油 2 勺 | 保存原文；不自动换算 |
| 切换 TRACKED↔RECIPE_ONLY | 原输入保留；不静默丢字段 |
| C 未选择处置 | 阻止保存 |
| C 选择 RECIPE_ONLY | 保存原文；导入成功 |
| C 选择 TRACKED | 走现有严格修正 |
| A/B 选择 RECIPE_ONLY | 保留原始文本；不匹配 |
| 历史 trackStock=false | 打开/保存不丢失；不扣减 |
| 历史 true 行 | 数量/单位/匹配不变 |
| 缺料菜谱 | 仍可加入计划 |
| 做饭 | 只扣 tracked；recipe-only 无流水 |
| 个人食记 | 不扣库存 |
| 图片 | 上传/显示/权限/尺寸错误 |
| 自定义食材 | 本家庭可见；其他家庭不可见/不可引用 |
| 同名/单位冲突 | 明确错误，不跨家庭污染 |
| 旋转/返回 | 行状态保留；不重复保存 |
| 长列表/窄屏 | 行操作可点、可滚动 |

## 14. 工作量与实施顺序

| 阶段 | 内容 | 估算 |
| --- | --- | --- |
| B1 | recipe-only JSON + OpenAPI + 服务端读写/快照 | 2–3 人日 |
| A | 结构化编辑器（RecyclerView、行状态、切换、校验） | 4–6 人日 |
| D | 导入处置/recipe-only + 详情/做饭展示 + 回归 | 2–3 人日 |
| C | 家庭自定义食材 + 图片 + scope/API/迁移 | 6–9 人日 |
| 总计 | | **14–21 人日** |

距 12/07 约 10 天（单人）：**A+B+D 已接近上限，C 无法安全塞入**。建议：
- 最小可用：B1 + A + D（8–12 天，需压缩视觉/测试）；
- C 另立 Phase C，必须单独批准，不阻塞 A/B；
- 若必须同时做 C，课程交付需要延期或减少其他功能。

推荐依赖顺序：

1. **B1**：先把 recipe-only 数据/契约/服务端读写做出来，避免 UI 用假字段。
2. **A**：结构化编辑器（tracked 行 + recipe-only 模式）。
3. **D**：导入核对与展示/回归。
4. **C**：家庭自定义食材/图片（独立迁移）。

用户建议“Phase A 先”可以理解为 UI 先行；但 A 的“仅说明”模式依赖 B 的存储，若严格先 A，只能先做 tracked 结构化 UI，再做模式，返工风险更高。建议 **B1 → A → D → C**。

## 15. 逐项回答重点问题

1. **现有 trackStock 能否直接表达两类？**
   - `true` = TRACKED；`false` 只能表示“旧的不追踪行”，不能表示自由文本 RECIPE_ONLY。新 RECIPE_ONLY 用 JSON 独立保存。

2. **自由文本用量保存在哪张表？**
   - 推荐 `recipes.recipe_only_ingredients` JSONB；不写入 `recipe_ingredients`。

3. **RECIPE_ONLY 是否必须有 ingredientId？**
   - 不需要；它不属于库存目录。用户可直接输入“香菜”。

4. **能否不创建库存食材就输入“香菜”？**
   - 可以，作为 RECIPE_ONLY；若要 TRACKED/库存/图片，则必须创建/选择字典食材。

5. **自定义食材属于全局、用户还是家庭？**
   - 推荐 **家庭（household）**；全局只保留内置字典。当前全局 + 全局 alias 唯一不满足隔离，需要 Phase C 迁移。

6. **图片能否直接关联 ingredientId？**
   - 可以：`ingredients.image_id → media(id)`；自定义食材用 HOUSEHOLD media。内置全局食材图片需要家庭覆盖表或 SYSTEM/PUBLIC media 决策。

7. **单位复合外键是否需要调整？**
   - TRACKED 不需要；RECIPE_ONLY 不进入该表，不需要 FK。已有食材的单位变更仍不安全，建议单位不可变或做全引用迁移。

8. **两类切换如何避免丢数据？**
   - 行状态同时保留 trackedQuantity/unit 和 recipeOnlyText；切换只改显示/保存通道，不自动清除另一侧。

9. **家庭菜谱编辑器如何最小改造？**
   - 保留现有 Fragment/名称/步骤/份数/时间/图片，把 ingredients EditText 换为 RecyclerView + 行 adapter；不做新菜谱模块。

10. **库存匹配/做饭确认是否改服务端？**
    - 匹配/扣减算法不需要改；服务端需读写 recipe-only 字段，并确保 preview/confirm 不读取它。快照可选复制。

11. **公共/个人导入如何兼容？**
    - 保留 A/B/C 解析；A/B 默认 TRACKED；C 必须用户选择 RECIPE_ONLY（保留原文）或补齐 TRACKED；不得自动全转。

12. **如何避免家庭自定义食材污染？**
    - household scope + 查询过滤 + 写接口校验 + 家庭范围唯一名/别名；不能只靠客户端过滤。

13. **历史菜谱如何正常？**
    - 新列默认空；true/false 行原样保留；false 继续跳过；不批量重写；编辑器显式转换。

14. **工作量？**
    - A 4–6、B 2–3、D 2–3、C 6–9，总计 14–21 人日；课程剩余 10 天只能优先 A+B+D，C 需单独批准。

## 16. 主要风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| Phase C 别名主键迁移 | `ingredient_aliases.normalized_name` 是全局 PK；改 scope 会触及所有食材查询/创建 | 独立迁移、备份、先加列后切约束；不要与 A/B 同批 |
| 自定义食材 visibility 未服务端校验 | 客户端过滤可被绕过，家庭数据串用 | 所有写接口校验 global/household scope；查询按 household |
| 全局 media 无 SYSTEM scope | 内置食材无法共享图片 | 先家庭覆盖图/占位图；或新增 SYSTEM scope 单独评审 |
| 编辑器 RecyclerView 状态丢失 | 切换/旋转丢行 | 行状态对象 + ViewModel/onSaveInstanceState |
| recipe-only JSON 无关系约束 | 名称/用量质量依赖客户端校验 | 服务端校验 name/amountText 非空、数组上限；不做库存引用 |
| 历史 false 行 | 保存时可能被误删/误迁移 | 原样保留；显式转换；测试 |
| OpenAPI 可选字段兼容 | 旧客户端/旧服务端互操作 | null=保留、[]=清空；契约测试 |
| 进度 | A+B+D 已接近 10 天 | C 单独批准；优先最小可用 |

## 17. 需要产品负责人批准的具体决策

1. 自定义食材采用 **家庭 scope** 还是接受全局共享？推荐家庭 scope。
2. recipe-only 用 `recipes` JSONB，还是单独表？推荐 JSONB。
3. RECIPE_ONLY 是否需要可选 ingredientId（用于未来图片/统计）？推荐不需要；纯文本。
4. 内置全局食材图片：家庭覆盖图 vs 新增 SYSTEM/PUBLIC media scope；推荐先家庭覆盖/占位图。
5. 是否允许用户把 A/B 类也手动设为 RECIPE_ONLY？建议允许；默认仍 TRACKED。
6. C 类导入默认行为：必须让用户选择，还是默认 RECIPE_ONLY？推荐**未选择，禁止保存**，不自动。
7. 历史 `trackStock=false`：保留原样直到用户显式转换，还是自动视为 recipe-only？推荐保留原样。
8. 自定义食材是否允许修改默认单位（影响现有菜谱/库存 FK）？推荐创建后不可变，或仅未引用时可改。
9. 课程交付范围：仅 A+B+D，还是 C 也必须在 12/07 前完成？C 需要额外 6–9 人日。
