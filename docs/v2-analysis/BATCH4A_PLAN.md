# Batch 4A 分析与最小方案：菜谱库存匹配接入

日期：2026-11-27（Asia/Shanghai）
基线：`b9bb720`（Batch 3 关闭）
状态：**仅分析，未修改业务代码**。

## 1. 结论摘要

- 服务端**已有**库存匹配接口 `POST /households/{id}/recipe-matches`（`matchRecipes`），并已有纯计算器、临期/过期过滤、逐行 `ENOUGH/MISSING/UNKNOWN`。
- **Android 完全没有调用该接口**；客户端只在“导入到家庭后的菜谱详情”调用 `previewCooking`，且只显示笼统的“缺食材”。
- 匹配只支持**家庭菜谱**（`recipes` + `recipe_ingredients` 的结构化 ingredientId/unit/trackStock）。个人菜谱与公共菜谱的食材是自由文本（名称/数量/单位字符串），**不能直接匹配**。
- 4A 可以**零服务端改动**完成：家庭菜谱列表 + 家庭计划选菜显示真实库存状态、缺料明细、可制作优先排序，且不阻止缺料菜谱加入计划。
- 主要风险：单位不一致目前被服务端算成 `MISSING(0)`，客户端必须用食材字典的单位二次判定为 `UNKNOWN`；公共/个人菜谱只能显示“无法判断/导入后可查”；导入流程会丢失自由文本单位，导致匹配数字不准。

## 2. 现有架构与数据流

### 2.1 数据表

| 数据 | 表 | 是否结构化 |
| --- | --- | --- |
| 家庭菜谱 | `recipes` | 是；食材在 `recipe_ingredients`（ingredient_id, quantity, unit, track_stock） |
| 个人菜谱 | `user_recipes` | 否；`ingredients` JSON 为 name/quantity/unit 自由文本 |
| 公共菜谱 | `recipe_catalog` | 否；`ingredients` JSON 为 name/quantity/unit 自由文本 |
| 食材字典 | `ingredients` + `ingredient_aliases` | 是；单位 G/ML/COUNT |
| 库存 | `inventory_batches` | 是；ingredient_id, quantity, unit, expires_on |
| 收藏 | `recipe_favorites` | kind=catalog/personal/household + recipe_id |

### 2.2 匹配数据流

```
Android (未接入)
  → POST /households/{id}/recipe-matches  {entries:[{recipeId, servings}]}
  → InventoryController.matchRecipes
  → InventoryMatchingService.match
      → 权限校验（家庭 active 成员）
      → 读取 quantity>0 的 inventory_batches
      → 逐个 recipeId 查询 recipes（仅家庭菜谱, NOT archived）
      → 读取 recipe_ingredients
      → InventoryCalculator.calculate（纯计算）
  → List<RecipeMatch>（逐行 MatchLine）
```

关键实现位置：

- 路由/控制器：`server/src/main/java/edu/xjtlu/civa/server/inventory/InventoryController.java:107`
- 服务：`InventoryMatchingService.java:18`（匹配）、`:51`（做饭预览复用同一计算器）
- 纯计算：`InventoryCalculator.java:17`
- 契约：`contracts/openapi.json` 的 `matchRecipes`、`RecipeMatch`、`MatchLine`、`MatchStatus`
- Android 生成客户端：`api-client/.../InventoryApi.java:148`（**当前无调用方**）

### 2.3 做饭预览

- Android 唯一调用点：`RecipeCatalogDetailFragment.java:481`（仅当该菜谱已导入家庭、存在 `householdRecipe`）。
- `previewCooking` 复用同一 `InventoryMatchingService.preview`；`shortages` 就是 `MatchLine` 列表。
- 客户端只判断 `shortages` 是否为空，非空时弹笼统提示 `ac_detail_cook_shortage`；**不显示缺什么、缺多少**。
- 确认做饭时才写 `cooking_events` 并扣库存；记录个人用餐（`meal_entries`）不触碰库存（Batch 2 已验证）。

### 2.4 现有 Android 页面映射

| 页面 | 数据来源 | 当前库存状态 |
| --- | --- | --- |
| `RecipeCatalogFragment`（菜谱 Tab） | PUBLIC=`recipe-catalog`，PERSONAL=`/me/recipes`，HOUSEHOLD=`/households/{id}/recipes`，FAVORITES | 无 |
| `RecipeCatalogDetailFragment` | 三种来源详情；导入家庭后才可做饭 | 仅做饭预览，笼统缺料提示 |
| `HouseholdRecipeEditorFragment` | 家庭菜谱编辑器 | 仅字典，不查库存 |
| `PersonalRecipeEditorFragment` | 个人菜谱编辑器 | 无库存 |
| `PlanFragment` 选菜 | 家庭菜谱 `listRecipes(home)` | 无 |
| `PlanFragment` 个人模式 / `DiaryFragment` | 本地 `TodayPlanReader`/`PlannedDish` | 无服务端结构化菜谱 |

## 3. 已有 API 与调用位置

| API | 用途 | Android 调用 |
| --- | --- | --- |
| `POST /households/{id}/recipe-matches` | 批量匹配 1–100 个家庭菜谱 | **无** |
| `POST /households/{id}/cooking-previews` | 做饭前分配/缺料 | 仅 `RecipeCatalogDetailFragment` |
| `GET /ingredients` | 食材字典（名称、别名、单位） | 家庭菜谱编辑器、菜谱详情、库存页 |
| `GET /inventory-batches` | 库存批次/过期状态 | 库存页 |
| `GET /households/{id}/recipes` | 家庭菜谱列表 | 菜谱页 HOUSEHOLD、计划选菜 |

## 4. 菜谱范围支持矩阵

| 来源 | 结构化食材 | `matchRecipes` | 4A 可展示状态 | 说明 |
| --- | --- | --- | --- | --- |
| 家庭菜谱 | ✅ ingredientId/quantity/unit/trackStock | ✅ | `ENOUGH / MISSING / UNKNOWN` | 唯一可靠匹配来源 |
| 导入后的家庭菜谱 | ✅（转换后） | ✅ | 同上，但受导入精度影响 | 公共/个人菜谱导入后才有 |
| 个人菜谱 | ❌ 自由文本 | ❌ | `UNKNOWN`（或“导入家庭后可查”） | 无 ingredientId，无法安全匹配 |
| 公共菜谱 | ❌ 自由文本 | ❌ | `UNKNOWN`（或“导入家庭后可查”） | 名称/单位是文本（“克/勺/适量”） |
| 收藏 | 取决于 kind | 仅 household kind 可匹配 | household 同家庭；其余 `UNKNOWN` | 4A 可先不展示状态 |

## 5. 服务端输出能力

### 5.1 三类状态

- `MatchStatus`：`ENOUGH / MISSING / UNKNOWN`，**逐行**返回。
- **没有顶层 status**；`coverage` 是“够用的 tracked 行数 / tracked 行数”，不是整体状态：
  - 全部 tracked 行够 → `coverage=1`；
  - 部分缺 → 0–1 之间；
  - 有 UNKNOWN 行时 coverage 仍按“够/总行数”计算，不能直接当状态；
  - 只有 `trackStock=false` 行时 `lines=[]`、`coverage="1"`（没有需要库存的食材）。
- 客户端必须自行归并：
  1. 任一行 `UNKNOWN` → `UNKNOWN`；
  2. 否则任一行 `MISSING` → `MISSING`（可附 coverage 表示部分缺料）；
  3. 否则 `ENOUGH`（`lines=[]` 时显示“无需库存”，不要显示“库存充足”）。

### 5.2 缺料数量与单位

- `MatchLine` 有 `ingredientId, required, available, unit, status`，**没有 ingredientName，也没有 missing 字段**。
- `missing = required - available`（仅当 status=MISSING 且 required 非空）；按 ingredientId+unit 聚合多行。
- `required=null` → `UNKNOWN`（无法计算），此时不能显示 0。
- 单位枚举仅 `G / ML / COUNT`；服务端不做单位换算。
- `available` 是满足单位、未过期、quantity>0 的批次总量；不扣预留。

### 5.3 过期与零库存

- 匹配读取 `inventory_batches WHERE quantity>0`。
- 计算器再排除 `expires_on < today` 的批次（按家庭时区）。
- 数量为 0 的批次不进入计算；过期批次不进入 `available`。
- 因此“冰箱为空/只有过期/数量为 0”都表现为 `available=0`、`status=MISSING`；**没有“已过期”标记**。
- `earliestExpiry` 只取参与计算的未过期批次的最早日期；不是“缺料”提示。

### 5.4 单位不一致的行为

- 计算器只匹配 `batch.unit == recipeIngredient.unit`。
- 若菜谱写的单位与食材字典/库存单位不同（例如菜谱 COUNT、库存 G），该行 `available=0`、`status=MISSING`，**这不是真实的“缺 0 g”，而是无法比较**。
- 服务端菜谱校验只要求 unit 非空，**未强制等于食材字典单位**；直接调 API 可写入不一致单位。
- 客户端应结合 `GET /ingredients` 的字典单位判断：`line.unit != ingredient.unit` → `UNKNOWN`，绝不显示“缺 0”。

## 6. 现有缺陷与缺口

### 6.1 已有但未接入

1. `matchRecipes` 服务端/生成客户端完整，但 Android 无调用。
2. `MatchLine` 已能给出 required/available/status，缺料数量可计算。
3. `listIngredients` 已有字典，可补 ingredientName。
4. 做饭预览已返回 shortages（但 UI 未展示明细）。

### 6.2 已实现但存在缺陷/限制

1. **仅家庭菜谱**：个人/公共菜谱无结构化 ingredientId，无法匹配。
2. **无顶层状态**：coverage 语义不等于状态；全 untracked 时 coverage=1 易被误读。
3. **单位不一致被算作 MISSING(0)**：应视为 UNKNOWN。
4. **过期/零库存不区分**：都归为 MISSING；无“已过期”提示。
5. **无名称/缺料字段**：需客户端用字典补名称并计算 missing。
6. **导入转换不保真**：
   - `HouseholdRecipes.catalogIngredientLines/personalIngredientLines` 只拼“名称 + quantity”，**丢弃 unit**；
   - `HouseholdRecipes.request` 只把最后一个空格后的数字当数量；`2 个`/`2勺`/`适量` 会变成默认 1；
   - `trackStock` 一律 true；
   - 公共菜谱单位示例：“克/勺/适量”，字典单位是 G/ML/COUNT。
   - 结果：导入后的“可制作/缺料”可能不准，必须标注“按导入换算估算”。
7. **服务端菜谱校验不强制单位一致/不查重**：可写入 unit 与字典不一致、重复 ingredient 行。
8. **排序**：家庭菜谱按 `updated_at DESC`；无库存优先排序。
9. **个人计划**：本地 `PlannedDish`（TodayPlanReader），无服务端计划/选菜 API；无法可靠匹配。

### 6.3 确实缺失

- Android 菜谱列表/计划选菜的库存状态展示。
- 缺料名称与数量的用户可读摘要。
- 可制作优先排序（不隐藏）。
- 公共/个人菜谱的“无法判断/导入后可查”说明。
- 过期库存的独立提示（需要服务端字段或客户端读批次；4A 不做）。
- 个人计划的库存匹配（本地自由文本，4A 不做）。

## 7. 业务规则核对（Batch 4A 必须保持）

| 规则 | 现状 | 4A 处理 |
| --- | --- | --- |
| 冰箱为空可创建个人/家庭菜谱 | 服务端不检查库存；编辑器可注册食材 | 不改 |
| 冰箱为空可收藏/导入公共菜谱 | 收藏不查库存；导入只查字典，不查库存 | 不改；导入后状态可能为 MISSING |
| 冰箱为空可加入计划 | 计划只校验菜谱存在/未归档 | 不改；不得按库存禁用 |
| 库存充足优先展示 | 无 | 客户端稳定排序 |
| 缺料菜谱仍可见可选 | 是 | 保持；只加标签 |
| 展示缺什么/缺多少 | 无 | 4A 新增 |
| 库存不足不阻止计划 | 是 | 不改 |
| 只有明确做饭才扣库存 | 是（confirmCooking） | 不改 |
| 个人用餐不扣库存 | 是（Batch 2） | 不改 |

### 7.1 服务端能力缺口与最小变更（4A 不做）

4A **不需要**服务端变更：现有 `matchRecipes` + `listIngredients` 足以让客户端派生状态。若 4B 需要更精确的展示，最小变更候选：

| 缺口 | 最小变更 | 影响 |
| --- | --- | --- |
| 顶层状态 | `RecipeMatch.status`（ENOUGH/MISSING/UNKNOWN） | OpenAPI + IC，服务端计算；客户端可直接显示 |
| 缺料名称 | `MatchLine.ingredientName` | 省一次字典调用；契约/IC |
| 已过期提示 | `MatchLine.expiredAvailable` 或 `hasExpired` | 需要读过期批次；契约/IC |
| 单位不一致 | 服务端返回 `UNKNOWN` 而非 `MISSING` | 语义修正；现有测试需更新 |
| 导入保真 | 不改 API；改客户端导入解析/单位映射 | 不需要契约变更 |

这些都不是 4A 的阻塞项；4A 不应为此扩大范围。若 4B 要做，需要单独批准 Contract 变更。

## 8. 4A 最小客户端方案

### 8.1 纯逻辑层（可单测）

新增 `RecipeStockPresenter`（`feature-ac/.../catalog`）：

- 输入：`Recipe`、`RecipeMatch`、`Map<UUID, Ingredient>` 字典、展示份数。
- 输出：
  - `State { ENOUGH, MISSING, UNKNOWN }`
  - `List<MissingLine> { name, missing, unit }`
  - `summary`（例：“可制作”/“缺 鸡蛋 2 个”/“无法判断”/“无需库存”）
- 规则：
  1. match 为 null / 请求失败 → UNKNOWN；
  2. `lines` 为空 → ENOUGH，但文案用“无需库存”；
  3. `required == null` → UNKNOWN；
  4. 字典缺失该 ingredient 或 `line.unit != ingredient.unit` → UNKNOWN（覆盖服务端 MISSING 0）；
  5. `available == null` → UNKNOWN；
  6. 行 `MISSING` → `missing=max(required-available,0)`，按 ingredientId+unit 聚合；
  7. 任一行 MISSING → MISSING；否则任一行 UNKNOWN → UNKNOWN；否则 ENOUGH。
- 不猜菜名、不猜单位、不把 unknown 当 0。

### 8.2 家庭菜谱列表

`RecipeCatalogFragment`（HOUSEHOLD source）：

1. 加载家庭菜谱页后，收集 `recipeId`（最多 100，当前页 60）。
2. 调用一次 `matchRecipes(home, entries)`；`servings = yieldServings`（无则 "1"）。
3. 并行/复用一次 `listIngredients(null,0,100)` 字典（分页取全）。
4. 生成 `Map<UUID, StockView>`；`RecipeCard` 增加可选 `stock` 字段或 adapter 外挂 map。
5. 排序：`ENOUGH` → `MISSING` → `UNKNOWN`；同状态保持服务端顺序；**不删除任何卡片**。
6. 卡片新增一行小字：`可制作` / `缺 鸡蛋 2 个` / `无法判断` / `无需库存`。
7. 请求失败：全部 UNKNOWN，列表照常显示；不显示 0。

非 HOUSEHOLD 源（PUBLIC/PERSONAL/FAVORITES）：
- 不调用 match（无家庭 id 或非结构化），不显示 0；
- 可显示“导入家庭后可查”或不显示状态，避免误导。建议 4A 只对 HOUSEHOLD 卡片展示库存标签。

### 8.3 家庭计划选菜

`PlanFragment` 的家常菜谱选择器（`chooseRecipe`）：

1. 家庭菜谱列表加载后，按 1 份调用一次 `matchRecipes`（与列表相同的字典）。
2. `pickRecipe` 的 item 文案追加状态：
   - `番茄炒蛋 · 可制作`
   - `番茄炒蛋 · 缺 鸡蛋 2 个`
   - `番茄炒蛋 · 无法判断`
3. 缺料项仍可点击；`addDish` 不增加任何库存校验。
4. 锁定槽/权限规则保持不变。
5. 匹配失败时显示“无法判断”，选择器仍可用。

### 8.4 详情/做饭预览（可选 P2）

- `RecipeCatalogDetailFragment` 已有 `CookingPreview.shortages`；用字典补名称/数量，展示具体缺料。
- 不改变“确认做饭才扣库存”的流程。
- 4A 可先不做，避免扩大范围；建议 4B 做。

## 9. 预计修改文件

| 文件 | 改动 |
| --- | --- |
| `feature-ac/.../catalog/RecipeStockPresenter.java`（新增） | 状态归并、缺料聚合、文案 |
| `feature-ac/.../catalog/RecipeCard.java` | 增加可选 stock 字段（或 adapter map） |
| `feature-ac/.../catalog/CatalogAdapter.java` | 绑定状态行 |
| `feature-ac/res/layout/ac_item_catalog.xml` | 新增状态 TextView |
| `feature-ac/.../catalog/RecipeCatalogFragment.java` | HOUSEHOLD 批量 match、字典、排序 |
| `feature-ac/.../plan/PlanFragment.java` | 选菜 match + 状态文案 |
| `feature-ac/res/values*/ac_strings.xml` | 可制作/缺料/无法判断/无需库存 |
| `feature-ac/src/test/.../RecipeStockPresenterTest.java`（新增） | 状态/单位/缺料/聚合测试 |
| `feature-ac/src/test/.../RecipeCardTest`（如需） | 新字段兼容 |

无服务端、数据库、OpenAPI 修改；无新依赖。

## 10. 测试矩阵

| 场景 | 期望 |
| --- | --- |
| 家庭菜谱、库存充足 | ENOUGH；列表排在前；计划可选 |
| 家庭菜谱、库存部分不足 | MISSING；显示缺料名称+数量；仍可加入计划 |
| 冰箱为空 | 所有 tracked 行 MISSING(0)；显示缺料，不显示 0 库存为“未知” |
| 只有过期/零库存批次 | MISSING(0)；不崩溃；不显示“可制作” |
| required=null | UNKNOWN；不显示 0 |
| 菜谱单位与字典单位不一致 | UNKNOWN；不显示“缺 0” |
| 字典缺少 ingredient | UNKNOWN |
| 全部 trackStock=false | ENOUGH，文案“无需库存” |
| match 请求失败 | 全部 UNKNOWN；列表照常 |
| 多行同 ingredient | 缺料聚合求和 |
| 非家庭来源 | 不显示 0；显示“导入后可查/无状态” |
| 计划选菜缺料 | 仍可点击并成功加入计划 |
| 做饭确认 | 仍只在确认时扣库存；缺料阻止确认 |
| 个人用餐 | 不改库存 |
| 家庭成员/权限 | 4A 不改 Batch 3 规则 |

服务端已有 `InventoryCalculatorTest` / `InventoryHttpIntegrationTest` 覆盖匹配；4A 不新增服务端逻辑，但需要回归 server 98 项。

## 11. 工作量与交付时间

| 工作 | 估算 |
| --- | --- |
| Presenter + 单测 | 1 人日 |
| 家庭菜谱列表接入（match/字典/排序/卡片） | 1.5 人日 |
| 计划选菜接入 | 0.5–1 人日 |
| 文案/布局/空态 | 0.5 人日 |
| 实机与回归 | 1 人日 |
| **4A 合计** | **3.5–4.5 人日** |

当前距 12/07 约 10 天。建议：
- 先做 4A（3–4 天）；
- 4B（导入保真/单位与个人/公共菜谱说明）2–3 天，视剩余时间；
- 4C（个人计划/推荐/自动生成）3–5 天，**本轮不建议**。

## 12. 4A / 4B / 4C 顺序建议

1. **4A（本方案）**：家庭菜谱列表 + 家庭计划选菜的库存状态/缺料/排序；零服务端改动，收益最高、风险最低。
2. **4B**：导入转换保真（unit/quantity/`trackStock`）、单位不一致的服务端校验/UNKNOWN 语义、公共/个人菜谱的明确状态、做饭预览缺料明细；必要时增加 `RecipeMatch.status`/`ingredientName`/`expired` 字段（需 Contract + IC）。
3. **4C**：个人计划/自动生成/库存推荐；依赖 4A/4B 的可靠状态，风险与工作量最高，建议课程交付后再做。

## 13. 需要确认的决策

1. 非家庭来源（公共/个人）是否显示“导入后可查”，还是完全不显示库存标签？
2. 家庭菜谱列表按“整道菜 yieldServings”匹配，还是统一按 1 份展示？
3. 缺料摘要最多显示几项（建议 2 项 + “等 N 项”）？
4. 是否在 4A 同时补做饭预览的缺料明细，还是留 4B？
5. 是否接受“仅在当前加载页内排序”（服务端无库存排序接口），还是 4A 就要求全局排序（需分页取全/服务端支持）？
