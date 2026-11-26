# Batch 4A-1 变更记录：库存匹配 Presenter 与家庭菜谱列表

日期：2026-11-27（Asia/Shanghai）
基线：`553dbe2`（Batch 4A 计划）
范围：仅 4A-1；未实施 4A-2（计划选菜）、4B、4C。未修改服务端、数据库、OpenAPI。

## 1. 已确认决策

| ID | 落地 |
| --- | --- |
| D1 | 仅 HOUSEHOLD 菜谱显示库存状态；PUBLIC/PERSONAL 不显示标签、不显示 0 |
| D2 | 列表按 `yieldServings`，缺失/无效按 1 份，并在文案中显示“按 X 份” |
| D3 | 最多显示 2 项缺料 + “等 N 项”；完整列表保留在 `StockView.missing` |
| D4 | 不做饭预览 UI 改动；确认做饭/扣库存逻辑未动 |
| D5 | 当前加载页内稳定排序 ENOUGH → MISSING → UNKNOWN，同状态保持服务端顺序，不隐藏任何状态 |

## 2. RecipeStockPresenter 计算规则

新增 `feature-ac/.../catalog/RecipeStockPresenter.java`（纯 JVM，可单测）：

1. match 为空、recipeId 不匹配、lines 为空之外的任何必要数据缺失 → `UNKNOWN`。
2. `lines` 为空（全部 `trackStock=false`）→ `ENOUGH`，`tracked=false`，UI 显示“无需库存”。
3. 任一行 `status=UNKNOWN`、`required/available=null`、数值非法、字典缺 ingredient、名称为空、单位与字典不一致 → 整体 `UNKNOWN`。
4. 全部可判断且至少一项 `required > available` → `MISSING`，缺料 = `max(totalRequired - sharedAvailable, 0)`。
5. 其余 → `ENOUGH`。

**不使用 `coverage == 1` 作为整体状态**；有单测覆盖“coverage=1 但实际缺料”的场景。

### 重复食材与共享库存

服务端每行 `available` 是该 ingredient+unit 的**共享库存总量**，不是每行可用量。Presenter：

- 按 `(ingredientId, unit)` 聚合 `required`；
- 同一组的 `available` 只取一次；
- 若同组多行给出不同的 `available` → `UNKNOWN`（数据不一致）；
- 同一 ingredient 以不同 unit 出现 → `UNKNOWN`（不换算）；
- 不把各行的 `(required - available)` 相加。

单测覆盖：重复行共享 available、required 求和、available 不一致、不同单位、缺料聚合。

### 单位与正常数据

现有数据库对 `recipe_ingredients(ingredient_id, unit)` 有到 `ingredients` 的复合外键，正常写入的家庭菜谱单位必然与字典一致；Presenter 的单位不一致判定是防御性降级（防脏数据/模拟 API），不会在正常数据上误报。

## 3. 库存 API 接入

`RecipeCatalogFragment` 的 HOUSEHOLD 分支：

- 家庭菜谱列表加载完成后（不阻塞列表显示）：
  1. `GET /ingredients` 分页加载字典（每页 100，直到 `total`；页数据不完整时降级 UNKNOWN，不截断后继续猜）。
  2. `POST /households/{id}/recipe-matches`，每 100 个 `MatchEntry` 一批；`servings = yieldServings`（无效则 1）。
- 使用 `stockToken` + 当前 home/source 校验：
  - 切换家庭、Tab、分类、搜索、刷新或销毁视图都会递增 token；
  - 旧 match/dictionary 响应不能覆盖新列表。
- 请求失败：相关卡片全部 UNKNOWN，列表照常可浏览。
- 不修改 `InventoryCalculator`、契约或库存数据。

## 4. 状态展示与排序

- `RecipeCard` 不变；`CatalogAdapter` 按 household 卡片 id 外挂状态文案。
- 卡片新增 `ac_item_stock` 行：
  - `可制作（按 2 份）`
  - `缺 土豆 2 克（按 2 份）`
  - `缺 A 2 个 等 3 项（按 2 份）`
  - `无需库存`
  - `无法判断`
- 排序：`ENOUGH(0) → MISSING(1) → UNKNOWN(2)`，使用稳定 `List.sort`，原顺序保持；不删除卡片。
- PUBLIC/PERSONAL/FAVORITES 不显示该行（adapter 只对 HOUSEHOLD 绑定）。
- 文案中英文均补齐。

## 5. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest -PdevStackOff` | 通过（新增 17 项 `RecipeStockPresenterTest`） |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **179 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（契约未变） |
| `server: .\gradlew.bat test --rerun-tasks` | **98 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

单测覆盖：足够/部分缺/空冰箱/过期或 0 库存/required null/available null/字典缺失/单位不一致/重复食材共享 available/available 不一致/不同单位/全部 untracked/match 失败/coverage 不参与/多缺料保留/份数回退/稳定排序。

## 6. 实机验证

设备 MI PAD 4 PLUS（1200×1920 / 320dpi），OurHome：

- 家庭菜谱 5 道：`FamilyTest2`、`TomatoEgg`、`Tomato Egg`、`联动测试菜` 显示“可制作（按 2 份）”并排在前；
- `Family repro` 显示“缺 土豆 2 克（按 2 份）”并排在最后，未被隐藏；
- 切换到“公共”分类：`ac_item_stock` 可见标签数为 **0**（公共菜谱不显示库存状态）；
- 截图：`review/stock-final.png`。

未实测设备上的 UNKNOWN（正常数据受复合外键约束，无法写入单位不一致；Presenter 单测覆盖）；空冰箱/过期/0 库存由 Presenter 与服务端既有测试覆盖。

## 7. 未执行

- 4A-2 计划选菜（下一步，需单独授权）。
- 家庭菜谱超过 100 条时的 chunk 分批（代码已实现，设备仅 5 条）。
- 字典超过 1 页（当前 36 个食材，单页 100）。
- 断网/超时下的列表降级与代际竞态的故障注入。
- 英文界面逐页人工检查（文案已补齐）。
- 空冰箱/全过期/0 库存的实机 UI 走查。

## 8. 剩余风险

- 列表状态基于 `yieldServings`；用户若按 1 份理解，可能觉得“按 2 份”提示多余（已在文案中明示）。
- 排序仅在已加载列表内，跨分页不保证全局顺序（D5 已确认接受）。
- 极长菜名/多缺料时状态行最多 2 行，可能省略（`maxLines=2`）。
- 导入菜谱的库存数字受 4B 的导入换算精度影响。
- 字典分页不完整时全部降级 UNKNOWN，偏保守。

## 9. 4A-2 前置条件

- 复用 `RecipeStockPresenter`，不要复制匹配逻辑。
- 计划选菜固定按 **1 份** 请求，不得复用列表 `yieldServings` 结果。
- match 在选菜对话框打开前/后异步加载均可，但不得阻塞选菜；失败显示 UNKNOWN，仍允许选择。
- 与 `PlanFragment` 的 `loadGeneration` 一致，防止旧家庭/旧请求覆盖。
- 缺料/UNKNOWN 不增加任何提交限制；Batch 3 权限逻辑不变。
