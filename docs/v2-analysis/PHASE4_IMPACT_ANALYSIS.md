# Phase 4 · 变更影响分析（Impact Analysis）

> 对象：Phase 3 的 P0/P1 差距（P2/P3 只做汇总）
> 目标：识别连锁影响、给出"兼容优先、范围最小"的实现路径、明确不能碰的东西
> 说明：本阶段仍**不修改代码**；所有"建议"均待 Phase 5 计划确认后执行

---

## 4.0 变更簇（Change Clusters）

把 30+ 个差异按"共享同一批文件/同一批测试/同一次迁移"聚成 8 个变更簇，避免逐条改动造成的反复返工：

| 簇 | 名称 | 包含差距 | 是否碰数据库 | 是否碰契约 | 预计波及文件 |
| --- | --- | --- | --- | --- | --- |
| C1 | 家庭权限与房主 | G-01、G-02、G-07、G-09、G-40、G-41、G-50 | ✅（1 个加法迁移） | ✅（Household 增加 ownerUserId；计划错误码） | 12–16 |
| C2 | 做饭/食记闭环 | G-03、G-04、G-05、G-08、G-34、G-49 | ❌ | ❌（复用现有字段） | 6–8 |
| C3 | 计划候选与跨范围引用 | G-22、G-23、G-24、G-46 | 取决于路径（A 不碰 / B 碰） | 取决于路径 | 8–14 |
| C4 | 库存优先与缺料 | G-18、G-26、G-44、G-45 | ❌ | ✅（match 聚合语义，若做） | 8–12 |
| C5 | 1/14 天生成与预览 | G-42、G-43 | ❌ | ❌（复用 commitPlan） | 6–10 |
| C6 | 菜谱数据保真 | G-11、G-12、G-13、G-15、G-16、G-17、G-21 | ✅（来源字段，若做） | ✅（复制来源，若做） | 10–14 |
| C7 | 健壮性与体验 | G-06、G-10、G-14、G-29、G-30、G-31、G-32、G-33、G-36、G-37、G-38 | ❌ | ❌ | 15–20 |
| C8 | 待决策 | PD-04、PD-06、PD-12 | 可能 | 可能 | 视决策 |

**执行顺序建议**：C7 的"小修"（G-06/G-10）→ C2 → C1 → C4 → C5 → C3 → C6 → C7 的"体验项"。理由：C7 小修零风险且有立即收益；C2 独立且直接修 P0 违规；C1 是 C5/C3 的前置；C4 是 C5 的前置（生成需要可做状态）。

---

## 4.1 C1 · 家庭权限与房主

### 影响矩阵

| 维度 | 影响 |
| --- | --- |
| **UI 与导航** | 计划页每行的"选菜/锁定"旁新增"删除/减份"（仅房主可见）；今天页家庭模式的加菜按钮保持所有人可用；新增"权限不足"提示样式；`我的`页显示"房主"标识 |
| **Activity / Fragment** | `PlanFragment`（行操作区、错误映射）、`BdHomeFragment`（家庭加菜、锁提示）、`ProfileFragment`/`HouseholdFragment`（房主标识、成员管理入口） |
| **ViewModel / Controller** | 新增 `HouseholdRole` 状态（建议放 `AppContainer` + `SessionStore`，避免各 VM 重复）；`PlanFragment` 增加"刷新并重试"动作；`HouseholdViewModel` 暴露 `isOwner` |
| **Repository / Service** | 新增 `PlanPermissions`/`PlanDiff`（服务端，纯函数 + 统一入口）；`PlanningService.replace/commit/lock` 接入；`HouseholdWriteService` 创建时写 owner；`HouseholdReadService` 返回 owner；`IdempotencyExecutor` 的 replay 回调接入 |
| **Database** | 加法迁移：`ALTER TABLE households ADD COLUMN owner_user_id UUID REFERENCES users(id)`；回填"该家庭最早 active 成员"；索引 `households_owner_idx`。**不删除、不改 `memberships.role`**（保留历史兼容） |
| **API Contract** | `Household` 增加 `ownerUserId`（required、可非空）；`listMembers` 语义不变；计划写接口新增 `403 PLAN_PERMISSION_DENIED`（或复用 `FORBIDDEN`）；OpenAPI 描述里"所有成员平等"的说明需更新。**必须走 IC 流程** |
| **家庭权限** | 这是变更本身：读=成员、ADD=成员、DELETE/DECREASE/REPLACE/MOVE=房主、锁/备注/增份=按 PD-01/02 |
| **数据同步** | 无协议变化；但"成员被拒"时客户端要能区分 403 与 409（不能都提示"家人修改"） |
| **历史记录** | 无影响（`plan_slots.author_id` 已有）；若做软删除/审计则新增表 |
| **现有测试** | **会失败的测试**：`HouseholdHttpIntegrationTest` 断言 `role<>'MEMBER'` 计数为 0（若引入 OWNER 角色）；`HouseholdLeaveTest` 的 `OWNER_CANNOT_LEAVE` mock（本身已陈旧）；`PlanHttpIntegrationTest` 中成员写入的用例（需按新矩阵拆分）；`PlanAuthorHttpIntegrationTest`（若 lock 权限变化） |
| **新增测试** | 身份 × 操作矩阵（3×6）、diff 12 组合、重放鉴权、并发 |

### 迁移方案（若 PD-11 选"加法 owner 字段"）

```
当前模型：households(id,name,zone_id,version,closed_at,created_at,updated_at)
          memberships(role) 全部为 MEMBER，无 owner 语义

目标模型：households 增加 owner_user_id（可空，指向 users）
          memberships.role 保持不变（历史兼容，不再承载权限）

迁移：
V2026xxxx__household_owner.sql
  ALTER TABLE households ADD COLUMN owner_user_id UUID REFERENCES users(id);
  UPDATE households h SET owner_user_id = (
      SELECT m.user_id FROM memberships m
      WHERE m.household_id = h.id AND m.active
      ORDER BY m.created_at, m.id LIMIT 1
  ) WHERE h.owner_user_id IS NULL;
  CREATE INDEX households_owner_idx ON households(owner_user_id);

风险：
  - 已注销家庭(closed_at 非空)可能无 active 成员 → owner 为 NULL，读时按"无房主"处理
  - 回填依据"最早成员"，与产品预期（创建者）可能不一致 → 需在 IC 中说明并接受
  - 房主退出后 owner_user_id 悬空 → 必须有"退出时转移或禁止退出"的规则（PD-08）
```

### 兼容性风险与替代方案

| 方案 | 优点 | 缺点 | 建议 |
| --- | --- | --- | --- |
| A. `households.owner_user_id`（加法） | 不推翻"普通成员平等"的历史语义；不回填 role；未来可支持转让 | 多一处归属字段；需处理房主退出 | **推荐** |
| B. 恢复 `memberships.role='OWNER'` | 与 V2 术语一致；`Role` 枚举已在契约中 | 直接推翻 2026-11-25 决定；`memberships_one_active_owner` 索引需恢复；所有"全员平等"测试与文档失效 | 不推荐（除非用户坚持术语一致） |
| C. 只做客户端隐藏 | 零后端改动 | **明确违反 PRM-05**（权限不能只靠隐藏按钮），QA-07 必挂 | 禁止 |

---

## 4.2 C2 · 做饭 / 食记闭环

| 维度 | 影响 |
| --- | --- |
| **UI 与导航** | 食记"在家吃"流程改造（选菜谱 → 预览 → 确认 → 记录）；缺料对话框统一（明细 + 出口）；做饭按钮改为忙碌态 + 结果待确认态 |
| **Fragment** | `DiaryFragment`（最大改动：`consumeThenRecord/consumeBatch/estimateAndRecord/recordHomeMeal` 一条链）、`RecipeCatalogDetailFragment.confirmCooking` |
| **ViewModel** | 无（都在 Fragment 内），但建议抽出 `CookingFlow` 以免两条路径再次分叉 |
| **Service** | `CookingEventService.confirm/normalise/deduct`（校验强化）；`MealEntryService.create`（replay 鉴权） |
| **Database** | ❌ 不需要迁移。`meal_entries.cooking_event_id` 已存在，足以表达"这顿饭来自哪次做饭" |
| **API Contract** | ❌ 不需要改。已有 `previewCooking`/`confirmCooking`/`createMealEntry` 三个操作；只需服务端在错误码上更严格（`INVALID_ALLOCATION` 可复用 `VALIDATION_ERROR`） |
| **家庭权限** | 做饭写操作仍是"成员即可"（与 PD-09 无关） |
| **数据同步** | 无变化 |
| **历史记录** | 做饭快照不变；食记会新增 `cookingEventId` 关联（更可追溯） |
| **现有测试** | `CookingEventHttpIntegrationTest` 需新增"空分配/错配料/过期批次"拒绝用例；现有正常路径断言不变；`DiaryFragment` 无测试，需新建纯函数测试（kcal 折算、缺料格式化） |
| **连锁影响** | 食记"在家吃"若改为走做饭，会**产生烹饪事件**（家庭可见），这是行为语义变化，需要产品确认（PD-07）；若改为"仅记录食记"，则库存不会被消耗，用户需要另外去冰箱页扣减——两条路都有 UX 代价 |

**关键取舍**：V2 只规定"个人用餐不得扣库存"，没有规定"用户在家吃但没做饭时如何扣库存"。因此**最小合规改动**是：
- 删除"先扣库存再记餐"的耦合；
- "在家吃"只写食记（不碰库存），并在 UI 明确提示"库存未变动，如需扣减请到冰箱或确认做饭"；
- 保留"计划菜→吃了"的正确链路（已是 confirmCooking → MealEntry）。
这样不需要新接口、不需要迁移、不引入新的语义争议。

---

## 4.3 C3 · 计划候选与跨范围引用

### 路径对比（必须先决策，PD-10 + PD-11 之外的独立决策）

| 维度 | 路径 A：转换复制（不改计划模型） | 路径 B：计划直接引用公共菜谱 |
| --- | --- | --- |
| 数据库 | 无需迁移 | `plan_items` 增加 `catalog_recipe_id` + CHECK；`PlanItem` 契约变更 |
| 服务端 | `validate` 不变（仍只允许家庭菜谱） | `validate` 支持 catalog；匹配对 catalog 返回"无法判断" |
| 客户端 | 选公共菜谱时先"加入家庭菜谱"（非阻断转换）再排餐 | 选公共菜谱直接提交 |
| 公共菜谱库污染 | 家庭菜谱列表会出现大量副本 | 无污染 |
| 工作流复杂度 | 低（复用现有 createRecipe） | 高（契约 + 迁移 + 三处读写 + 历史兼容） |
| V2 字面符合度 | 中（用户视角"能排公共菜"，但数据上是家庭副本） | 高 |
| 风险 | 中低 | 高 |
| 时间 | 1–2 人日 | 3–4 人日 |

**建议**：课程交付时间（距 12/7 约 10 天）下选 **路径 A**，并在报告/演示中明确"公共菜谱通过家庭副本进入家庭计划"，不声称"计划直接引用公共库"。若团队坚持字面符合，则必须接受路径 B 的连带风险：`plan_items` 复合外键要改、`cooking_events` 的 `recipe_id` 是否也要支持 catalog（做饭时只能做家庭菜谱，需在 UI 拦截）、匹配状态要新增"无法判断"。

### 影响矩阵（路径 A）

| 维度 | 影响 |
| --- | --- |
| UI | 家庭计划"选菜"弹窗增加来源分组：家庭菜谱 / 从公共菜谱添加；转换失败时给出"无法匹配食材"的可操作提示（选择标准食材/跳过该行） |
| Fragment | `PlanFragment.chooseRecipe`、`BdHomeFragment.addHouseholdDish`、`RecipeCatalogDetailFragment.addToHousehold` |
| Service | `HouseholdRecipes`（转换器重写：不再阻断、份数/时长/标签可传）；`HouseholdRecipeService.create` 不变 |
| DB | ❌ |
| 契约 | ❌ |
| 权限 | 转换产生的家庭菜谱遵循家庭菜谱权限（PD-09） |
| 测试 | 转换器纯函数测试（20 条真实菜谱样本）、加入家庭后可在计划中选中 |
| 连锁 | 家庭菜谱列表变长 → 需要搜索/分组（G-17）、需要区分"用户创建"与"来自公共"（可选 `tags` 标记） |

### 影响矩阵（路径 B，若选）

| 维度 | 影响 |
| --- | --- |
| DB | 迁移：`plan_items.catalog_recipe_id UUID REFERENCES recipe_catalog(id)`，`ALTER COLUMN recipe_id DROP NOT NULL`，`CHECK (num_nonnulls(recipe_id,catalog_recipe_id)=1)`；**复合 FK 无法再约束 catalog**（需改 FK 或加触发器） |
| 契约 | `PlanItem` 增加 `recipeKind` + `catalogRecipeId`；所有 plan 响应变化；客户端生成代码重编 |
| Service | `PlanningService.validate/writeItems/map`；`InventoryMatchingService`（catalog → UNKNOWN）；`CookingEventService`（catalog 不可做饭，需拒绝或转换） |
| 历史 | 旧 `plan_items` 全部 `recipe_kind=HOUSEHOLD`，需回填或默认 |
| 风险 | 外键完整性下降；历史数据回填；契约破坏性变更 |

---

## 4.4 C4 · 库存优先与缺料

| 维度 | 影响 |
| --- | --- |
| **UI** | 新增"匹配徽标"（可做/部分不足/缺少/无法判断）；选菜弹窗分组与排序；筛选 chip（全部/仅可做/缺料）；缺料 Snackbar/详情；计划页汇总条 |
| **Fragment** | `PlanFragment`、`BdHomeFragment`、`BdFlowDialog`、`RecipeCatalogDetailFragment`（详情页展示可做状态） |
| **ViewModel** | `BdHomeViewModel` 增加候选池匹配状态；`PlanFragment` 引入轻量 `PlanMatchState`（或独立 VM） |
| **Service** | 新增 `MatchRepository`（feature-ac 内，封装 `matchRecipes` + 缓存 + 失效）；**不改服务端算法**，除非做 G-18 累计（服务端改动） |
| **DB** | ❌ |
| **契约** | 若做 G-18：`MatchRequest` 增加 `aggregate`/或新增 `planMatch` 操作 → **契约改动 + IC**。若只做逐菜匹配：❌ |
| **权限** | `matchRecipes` 只要求成员即可（与角色无关） |
| **同步** | 匹配是读操作，无同步问题；但每次计划变更后需重算（客户端缓存按 `householdId + inventoryVersion` 失效；**当前无 inventory version 字段**，只能用"页面刷新"或时间戳兜底） |
| **历史** | 无 |
| **现有测试** | 服务端匹配测试不受影响（除非改语义）；客户端需新增匹配状态映射的纯函数测试 |
| **连锁影响** | ①"可做"状态依赖库存是否准确 → 库存页的每次变动都可能导致计划页状态过期；②公共/个人菜谱无法匹配 → 必须显示"无法判断"，否则会让用户误以为"可做"；③排序变化会让已有 UI 测试/截图失效（需更新快照） |

**风险最高的点**：把"未知"渲染成"可做"。建议在 `MatchRepository` 里显式建模四态，且**默认状态是 `UNKNOWN`**，只有服务端返回 `ENOUGH` 才显示"可做"。

---

## 4.5 C5 · 1/14 天生成与预览

| 维度 | 影响 |
| --- | --- |
| **UI** | 新增生成条件页（范围/餐次/人数/口味/时长/库存优先开关）→ 预览页（可编辑/取消单道）→ 确认；计划页入口 |
| **Fragment/Activity** | 新增 1–2 个 Fragment（`PlanGenerateFragment`、`PlanPreviewFragment`）+ `AppNavigator` 路由注册 + `MainActivity` 宿主接口 |
| **ViewModel** | 新增 `PlanGenerateViewModel`（候选池、规则、预览状态、提交） |
| **Service** | 客户端生成器（纯函数，可单测）；提交走 `commitPlan`（服务端已具备，≤42 槽） |
| **DB** | ❌ |
| **契约** | ❌（复用 `commitPlan`）。但需确认 `commit` 的"锁槽静默跳过"是否满足预览承诺 → 建议服务端返回 `skipped`（小改，非破坏） |
| **权限** | 生成写入必须走 C1 的 diff 校验；成员生成只能"填空位/新增"，不能覆盖 → 与 C1 强耦合 |
| **同步** | 提交时携带每个 slot 的 `expectedVersion`（预览时读取）→ 并发会 409，需提示刷新 |
| **历史** | 无 |
| **测试** | 生成器单测（锁定保留、缺料不阻断、候选不足、口味过滤）；集成测试（预览不改库、确认后内容一致、并发 409） |
| **连锁影响** | ①预览页需要"可做状态"，依赖 C4；②"14 天"与计划页日期范围组件（G-28）共用；③新路由要登记 `public-surface.draft.json`，否则 `PublicSurfaceCompletenessTest` 会失败 |

**重要**：`commitPlan` 目前零调用，属于"未验证路径"。启用前必须先用集成测试验证：正常 42 槽提交、锁槽跳过、版本冲突、幂等重放（G-07 修完后）。

---

## 4.6 C6 · 菜谱数据保真

| 维度 | 影响 |
| --- | --- |
| **UI** | 个人编辑器增加数量/单位/标签/份数/时长；家庭编辑器增加份数/时长/标签；列表增加"已归档"筛选；复制按钮显示"已存在"状态 |
| **Fragment** | `PersonalRecipeEditorFragment`、`HouseholdRecipeEditorFragment`、`RecipeCatalogFragment`（归档筛选、家庭搜索）、`RecipeCatalogDetailFragment`（复制来源、归档恢复） |
| **Service** | 客户端 `HouseholdRecipes`（转换器）；服务端 `HouseholdRecipeService`（图片校验、事务）、`PersonalRecipeService`（来源字段）、`RecipeFavoriteService`（可见性） |
| **DB** | 若做来源：`user_recipes.origin_catalog_recipe_id UUID`（可空）加法迁移；若不做则 ❌ |
| **契约** | 若做来源：`CreatePersonalRecipeRequest` + `PersonalRecipe` 增加字段 → IC。其余 ❌ |
| **权限** | 家庭菜谱图片校验属于安全加固，不改变权限模型 |
| **同步** | 无 |
| **历史** | 不改动历史快照；但 `HouseholdRecipeService.snapshot`（创建家庭菜谱时给作者写个人副本）会产生"陈旧个人副本"，建议：要么不写（改为显式复制），要么在 update 时同步——**这是行为变更，需产品确认** |
| **现有测试** | `HouseholdRecipeHttpIntegrationTest`（update 事务化后断言不变）、`PersonalRecipeHttpIntegrationTest`（来源字段）、`RecipeFavoriteHttpIntegrationTest`（可见性过滤） |
| **连锁影响** | 编辑器表单变更会影响"从公共加入家庭"的默认值（G-12/G-22 共用转换器）；归档筛选会影响列表空态与分页 |

**建议的"不破坏"顺序**：先修 G-12/G-13（表单与转换器，不动契约）→ 再修 G-11/G-17（列表能力）→ 最后做 G-21（来源字段，需迁移+契约）。这样前三步可独立交付、独立回滚。

---

## 4.7 C7 · 健壮性与体验

| 差距 | 影响面 | 主要波及 | 风险 |
| --- | --- | --- | --- |
| G-06 事务化 | 服务端 1 个方法 | `HouseholdRecipeService.update` + 1 个测试 | 极低 |
| G-10 首插冲突 | 服务端 1 个方法 | `PlanningService.replace/commit` + 错误映射 | 极低 |
| G-14 未来日期 | 客户端 + 服务端各 1 处 | `DiaryFragment` 日期格、`MealEntryService` 校验 | 低（时区边界） |
| G-29 统一状态组件 | `core-ui` 新增 2 个类 + 资源 | `core-ui`、`public-surface.draft.json`、5 个页面 | 中（共享模块，需过架构测试） |
| G-30 平板/状态保持 | 多页面布局与状态 | `MainActivity`、`BdHomeFragment`、`PlanFragment`、`RecipeCatalogFragment` | 中（纯表现） |
| G-31 英文键 | 1 个 XML | `bd_elastic_choices.xml` | 极低 |
| G-32 食记快照 | 契约 + 迁移（若做完整快照） | `meal_entries`、`MealEntryService`、`DiaryFragment` | 中 |
| G-33 汇总结论文案 | 1 个页面 | `DiaryFragment` | 极低 |
| G-36 409 体验 | 2 个页面 | `PlanFragment`、`BdHomeFragment` | 低 |
| G-37 兜底与日志 | 服务端 1 个 advice + 客户端 3 处 | `ApiExceptionHandler`、`ProfileFragment`、`BdTodayPlanReader` | 低 |
| G-38 缓存隔离 | 2 个存储 | `CatalogCache`、`ApiCalls`（若启用） | 低-中 |

---

## 4.8 C8 · 待决策项的影响预判（供决策参考）

| 决策 | 若选"是"，影响 | 若选"否"，影响 |
| --- | --- | --- |
| PD-04 家庭想吃清单 | +1 表或 +2 列、+3 接口、+1 页面区块、权限矩阵再扩一列 | 明确写入"首版不做"，报告说明 |
| PD-06 单人库存 | `inventory_batches` 允许无家庭 + 全局查询改造 + 匹配 + 权限，**高风险** | 保持"需家庭"提示，报告说明限制 |
| PD-12 个人计划上服务端 | `plan_slots` 增加 scope/owner + 个人计划接口 + 同步 + 冲突；**工作量≈C5** | 保持本地草稿，标注"仅本机" |
| PD-07 缺料做饭 | 做饭流程增加"确认修正库存"分支；可能新增接口 | 维持"禁止超扣 + 可只记食记"，两条路径统一文案 |

---

## 4.9 连锁影响地图（改 A 会碰到 B）

```
C7-G06（update 事务化）
   └─ 无连锁

C2（做饭/食记）
   ├─ 食记"在家吃"改动 → 影响 DiaryFragment 的 4 个方法 → 影响缺料对话框复用（G-34）
   ├─ 幂等键接入 PendingWriteStore → 影响 AppContainer 注入 → 影响所有 VM 构造
   └─ confirmCooking 校验强化 → 影响客户端 preview 依赖 → 影响详情页/食记两条路径

C1（权限）
   ├─ households.owner_user_id → Household DTO → 生成代码 → 所有读 household 的页面（Profile/Household/Today/Inventory）
   ├─ PlanDiff → replace/commit/lock → 影响 Today 加菜 + Plan 选菜 + 未来生成
   ├─ 403 新错误码 → ApiCalls 错误映射 → Feedback 文案
   └─ 客户端权限状态 → AppContainer/SessionStore → 所有写入口的门控

C4（匹配 UI）
   ├─ matchRecipes 调用 → 需要候选池 ID 列表 → 依赖 C3 的候选池结构
   ├─ 排序变化 → 计划页/弹窗顺序 → 影响截图与手工验收脚本
   └─ 四态建模 → 新徽标组件（与 G-29 共用）

C5（生成）
   ├─ 依赖 C4 的可做状态
   ├─ 依赖 C1 的 diff 权限
   ├─ 新增路由 → public-surface 登记 + AppNavigator
   └─ 启用 commitPlan（未验证路径）→ 需先补测试

C3（候选池/跨范围）
   ├─ 路径 A：依赖 C6 的转换器改造（G-22）
   ├─ 路径 B：影响 plan_items 模型 → 影响 C5 生成 + C4 匹配 + 做饭（catalog 不可做饭）
   └─ 影响家庭菜谱列表长度 → 影响搜索（G-17）与分页
```

---

## 4.10 兼容性优先的实现策略（统一原则）

1. **只加不改**：新字段可空、新接口新增、旧接口形状不变；需要改变语义时（如 match 聚合）优先新增参数/新操作，而不是改默认行为。
2. **契约改动集中**：每次 IC 记录一个簇，避免一个任务改三次契约。
3. **迁移只前向**：不删列、不改列类型、不回填破坏性数据；回填失败时允许 NULL 并降级。
4. **客户端双轨过渡**：新 UI 组件先新增、再逐页替换；权限门控先"隐藏入口"，但**服务端强制必须同时上线**（否则 QA-07 失败）。
5. **测试先行**：每条 P0 先写失败测试（服务端集成/客户端单测），再改实现，确保"修改前失败、修改后通过"。
6. **可回滚**：每个任务独立提交；数据库迁移与代码分开提交；迁移只做加法，回滚只需回退代码。

---

## 4.11 明确"不要碰"的清单

| 不要碰 | 原因 |
| --- | --- |
| 三张菜谱表合并 | V2 明确不强制；成本高、收益低、会破坏现有隔离与测试 |
| `SessionStore.generation` 机制 | 已是客户端一致性的核心，改动风险极高 |
| `IdempotencyExecutor` + `idempotency_requests` 设计 | 服务端幂等已正确，只需补 replay 鉴权 |
| `InventoryCalculator` 的计算规则 | 覆盖率/分配/过期/单位处理已被测试覆盖；只在必要时**扩展**，不重写 |
| `CivaTheme` / `civa_*` 颜色与字体 | 用户要求保留既有 UI 风格 |
| `AppNavigator` 的整体路由模型 | 只需新增路由，不改为 Navigation Component |
| 生成代码（api-client / server api） | 只能通过 OpenAPI 重新生成 |
| `memberships.role` 的存量语义 | 改它会连带推翻 2026-11-25 的决定与多个测试；用加法字段替代 |
| 现有 24 项 Fully Implemented | 见 Phase 3 §3.1 |

---

## 4.12 影响总结

- **碰数据库的只有 2 个簇**：C1（`households.owner_user_id`，必做）与 C3/C6（可选：`plan_items` 跨范围 / `user_recipes` 来源）。其余全部是代码/契约/UI 层。
- **碰契约的有 3 处**：C1（Household.ownerUserId + 计划 403）、C4（可选 match 聚合）、C6（可选复制来源）。都可集中为 1–2 个 IC。
- **最容易被低估的连锁**：C1 的 403 会改变客户端错误提示语义；C4 的排序会改变所有选菜列表的顺序（影响演示脚本与截图）；C5 启用 `commitPlan` 是"从未被调用过的路径"，必须先验证。
- **最安全的高收益顺序**：G-06 → G-10 → C2 → C1 → C4 → C5，前两步几乎零风险且立刻减少数据损坏概率。
