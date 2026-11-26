# Phase 3 · 需求差异分析（Gap Analysis）

> 对照基准：V2 需求 ID（Phase 2）+ 工程实际代码（Phase 1，全部经代码核实）
> 判定口径：`Fully` = 已实现且符合；`Partial` = 部分实现；`Incorrect` = 已实现但业务逻辑不符；`Missing` = 未实现；`Pending` = 待确认
> 每条含 9 项：Requirement / Current / Gap / Files / Required Changes / Dependencies / Risk / Priority / Verification

---

## 3.0 统计与阅读顺序

| 分类 | 条目数 | 其中 P0 | P1 | P2 | P3 |
| --- | --- | --- | --- | --- | --- |
| Fully Implemented（不列开发任务） | 24 | — | — | — | — |
| Incorrect Implementation | 20 | 7 | 6 | 5 | 2 |
| Partially Implemented | 17 | 0 | 6 | 8 | 3 |
| Missing Feature | 12 | 1 | 6 | 4 | 1 |
| Pending Confirmation | 13 | 1（总开关） | — | — | — |

**读法**：先看 §3.6 的跨模块流程结论（这是最容易漏的部分），再看 §3.2/§3.3/§3.4 的详细块，最后看 §3.7 汇总。

**优先级定义**
- **P0**：业务错误 / 数据安全 / 权限可绕过 / 数据会被写坏 → 必须在交付前修
- **P1**：V2 已确认但未实现的重要功能 → 本轮必须做或明确裁剪
- **P2**：影响体验或数据一致性的优化
- **P3**：视觉/扩展

---

## 3.1 Fully Implemented（已实现且符合，**不得重复开发**）

| 需求 ID | 内容 | 实际实现证据 | 备注 |
| --- | --- | --- | --- |
| RCP-03 | 公共菜谱只读，无写端点 | `RecipeCatalogService.java:28-53` 仅查询；契约无 catalog 写操作 | 保留 |
| RCP-05 | 个人菜谱仅 owner | `PersonalRecipeService.java:75-76,103-107` 全部带 `user_id=?` | 保留 |
| RCP-06 | 家庭菜谱本家庭成员可读 | `HouseholdRecipeService.java:205-209` `requireMember` | 保留（权限强度见 PD-09） |
| RCP-02 | 空库存可创建/编辑菜谱 | recipe 服务不依赖 inventory；`HouseholdRecipeEditorFragment.java:118` 有明确注释；集成测试在零库存家庭创建成功 | 保留，且**不要**加库存前置 |
| PLN-12 | 想吃与计划数据层分离 | `recipe_favorites` vs `plan_items`；想吃不会生成计划 | 保留 |
| PLN-02 | 家庭计划服务端共享 | `plan_slots` + `GET/PUT plan-slots` | 保留 |
| PLN-10 | 锁定槽不被 commit 覆盖 | `PlanningService.java:134-137` 锁槽直接跳过 | 保留（但需补"静默跳过"提示，见 G-43） |
| STK-08 | 批次化库存（数量/单位/库位/到期/批次） | `inventory_batches` 表 + `InventoryFragment` 表单 | 保留 |
| STK-09 | 消耗/丢弃/修正 + 变动留痕 + 非负 | `InventoryService.change` + `inventory_movements` + DB CHECK | 保留 |
| STK-07（UI 部分） | 冰箱单一入口，无个人/家庭双标签 | `InventoryFragment` 只有一套列表 | 保留 |
| CKG-01 | 六个概念数据层分离 | 六张表互不替代 | 保留 |
| CKG-02 | 创建计划不扣库存 | `PlanningService` 无 inventory 写入 | 保留 |
| CKG-04 | 个人食记按用户隔离 | `meal_entries.user_id` + 所有查询带 actor | 保留 |
| CKG-06 | 做饭日期与用餐日期分开 | `cooking_events.cooked_at` vs `meal_entries.date` | 保留 |
| CKG-10 | 热量四种 basis + 每份/每100g 计算 | `MealEntryService.java:152-156` + DB CHECK | 保留 |
| CKG-11（做饭侧） | 做饭快照冻结，编辑菜谱不改写 | `cooking_events.recipe_snapshot` JSONB | 保留 |
| DAT-01/02 | 单一 PostgreSQL + 按业务域分表 | 10 个迁移、14 域表 | 保留，**不要**拆库 |
| DAT-08 | 匹配结果实时派生，不冗余 canCook | 无该列；匹配每次实时计算 | 保留 |
| DAT-11（服务端） | 确认做饭原子 + 唯一请求 ID | `IdempotencyExecutor` + 单事务 + 并发测试 | 保留 |
| DAT-12（版本/操作者） | 并发版本 + 最后编辑人 | `expectedVersion` → 409；`plan_slots.author_id` | 保留 |
| PRM-01/PRM-10 | 家庭计划共享 + 刷新可见 | 服务端共享，无实时推送 | 保留（符合首版） |
| CKG-09 | 家庭做饭不自动生成每人的食记 | `confirmCooking` 不写 `meal_entries` | 保留 |
| UI-01 | 五个主入口不变 | `app_navigation.xml` 5 项 | 保留 |
| EC-06/EC-12 | 契约门禁与 CI 有效 | `ci.yml` + `checkContracts` | 保留 |

> 这 24 项在 Phase 5 **不会**出现在开发任务里；相关任务只允许"在其上增量"，不允许重写。

---

## 3.2 Incorrect Implementation（已实现但不符合要求）

### G-01 无房主角色，任何成员可删除/减少/覆盖家庭计划 —— **P0**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PRM-03/04/05/06/07、PLN-15、DAT-10、UI-09：仅房主可删除或减少家庭计划内容；权限在可信写入层强制；成员尝试删减返回明确错误 |
| **Current** | 迁移 `V20261125210000:3-4` 删除 owner 唯一索引并把所有 `OWNER` 降级为 `MEMBER`；建户固定写 `'MEMBER'`（`HouseholdWriteService.java:36`）；服务端计划写操作只调 `requireMember`（`PlanningService.java:229-233`）；客户端全仓无 `role/isOwner`。结果：任何 active 成员都能清空/覆盖任意槽，房主概念不存在 |
| **Gap** | 缺少 ①房主的持久化定义 ②服务端角色判定 ③基于内容 diff 的删减识别 ④客户端权限状态与 UI 门控 ⑤明确的权限错误码 |
| **Files** | `server/.../plan/PlanningService.java:57-154,229-233`；`server/.../household/HouseholdWriteService.java:36`；`server/src/main/resources/db/migration/*`；`contracts/openapi.json`（Role 描述、plan-slots 错误码）；`core-data/.../SessionStore.java`；`app/.../AppContainer.java`；`feature-ac/.../plan/PlanFragment.java:253-341`；`feature-bd/.../BdHomeFragment.java:401-475` |
| **Required Changes** | ①（待 PD-11 决策）新增 `households.owner_user_id`，创建时写创建者，历史数据回填"最早 active 成员" ②新增 `PlanPermissions` 统一判定：读=成员；`ADD`=成员；`DELETE/DECREASE/REPLACE/MOVE`=房主 ③`replace`/`commit` 在事务内先读旧 items，再 diff 出新旧集合，命中删减且非房主 → `403 PLAN_PERMISSION_DENIED` ④客户端：统一 `HouseholdRole` 状态（来自 `getHousehold`/`listMembers`），非房主隐藏或禁用删除/减少/替换/移动入口 ⑤错误提示走统一 `Feedback` |
| **Dependencies** | PD-11（房主定义）、PD-01/02/03（增份/锁/移动）、G-02（diff 语义）、G-09（锁不是权限边界） |
| **Risk** | **高**。动到共享数据模型 + 所有家庭写接口 + 两个页面的交互；迁移涉及历史数据回填；`PRODUCT_RULES.md` 需同步 supersede，否则文档自相矛盾。若实现不当会造成"成员无法正常加菜"的回归 |
| **Verification** | 服务端集成测试：成员 ADD 成功 / 成员 DELETE 403 / 成员 DECREASE 403 / 成员 REPLACE 403 / 成员清空 403 / 房主全部成功 / 移除后 403；客户端：成员界面无删除按钮但可加菜；深链/旧版本构造请求被拒 |

---

### G-02 计划写入只有"整槽替换"，无 ADD/DELETE 语义 —— **P0**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PLN-14、DAT-10：成员"新增"必须是真正的新增，不得借新增覆盖原项；删减必须由服务端按内容判定 |
| **Current** | 客户端每次加菜都发 `ReplacePlanSlotRequest(items=全部旧项+新项)`（`BdHomeFragment.java:446-455`），选菜=替换成单道（`PlanFragment.java:304-308`），清空=`items=[]`（`:299-300`）。服务端 `replace` 直接 `DELETE FROM plan_items` 再全量插入（`PlanningService.java:79-80`）；`commit` 同理（`:151-152`） |
| **Gap** | 数据层没有"追加"语义；服务端无法区分"追加"与"替换"；因此要么全员可删（现状），要么成员无法加菜（粗暴收紧） |
| **Files** | `feature-bd/.../BdHomeFragment.java:446-475`；`feature-ac/.../plan/PlanFragment.java:277-322`；`server/.../plan/PlanningService.java:130-154` |
| **Required Changes** | 服务端在 `replace`/`commit` 内实现 `PlanDiff.diff(oldItems, newItems)`，产出 `added/deleted/decreased/replaced/moved`；仅当 `deleted|decreased|replaced|moved` 非空才要求房主；客户端保持整槽提交（**不新增端点**），由服务端做语义判定；返回体可附带 `appliedOperations` 便于客户端提示 |
| **Dependencies** | G-01、PD-01/03 |
| **Risk** | 中高。纯服务端改动可保留契约形状（避免契约大改），但新增了"内容感知"逻辑，必须用表驱动测试覆盖 12 种前后组合 |
| **Verification** | 参数化测试：`[A]→[A,B]` 允许成员；`[A,B]→[A]` 拒绝成员；`[A(2份)]→[A(1份)]` 拒绝成员；`[A]→[B]` 拒绝成员；`[]→[A]` 允许成员；房主全部允许 |

---

### G-03 个人食记"在家吃"直接扣库存 —— **P0**

| 项 | 内容 |
| --- | --- |
| **Requirement** | CKG-05、STK-10：个人用餐记录不得扣减原材料；只有确认做饭才扣 |
| **Current** | `DiaryFragment` 的"在家吃"流程逐批调用 `inventory().changeInventory(reason=CONSUME)`，成功后写 `MealEntry`（`DiaryFragment.java:753-790`）。不经 `CookingEvent`，非原子，`after.max(ZERO)` 把超扣夹成 0（`:772-774`），中途失败不回滚 |
| **Gap** | 存在"个人用餐扣原材料"的独立通道；缺事务；缺"这是库存变动还是做饭"的语义区分；`CookingEvent` 与 `MealEntry` 的边界被绕过 |
| **Files** | `feature-ac/.../diary/DiaryFragment.java:690-853`；相关字符串 `ac_strings.xml` 的 `ac_diary_at_home*` |
| **Required Changes** | 二选一（需产品确认，见 PD-07）：**(A) 推荐** 把"在家吃"改为：选菜谱 → `previewCooking` → `confirmCooking`（扣一次 + 写烹饪事件）→ `createMealEntry(cookingEventId)`；手填菜名无菜谱时只记食记、不碰库存。**(B)** 保留独立"消耗食材"入口，但明确标注为"库存变动（非做饭）"，与食记解耦、单事务、不夹 0。无论哪种，都必须删除"先扣库存再记餐"的顺序耦合 |
| **Dependencies** | PD-07、G-04（幂等）、G-05（服务端校验） |
| **Risk** | 中。改动集中在 `DiaryFragment` 一个流程，但会改变用户可见行为（需要文案配合）；需回归"计划菜→吃了"路径 |
| **Verification** | 记录一餐后断言 `inventory_movements` 中**没有** `CONSUME` 行且有且仅有一条 `COOK`（若走 A）；或走 B 时食记不产生任何库存行；重复点击不重复扣 |

---

### G-04 客户端做饭/食记幂等键每次新生成 —— **P0**

| 项 | 内容 |
| --- | --- |
| **Requirement** | CKG-03、DAT-11、QA-12：双击/重试不得重复扣库存或重复写记录 |
| **Current** | `confirmCooking(home, UUID.randomUUID(), ...)`（`RecipeCatalogDetailFragment.java:490-491`、`DiaryFragment.java:328`）、`createMealEntry(UUID.randomUUID(), ...)`（`:399,840,1012`）、`replacePlanSlot` 无幂等键（服务端也不需要）。服务端幂等机制本身正确，但客户端超时重试会换 key → 服务端视为新请求 |
| **Gap** | 缺少"同一次用户意图复用同一 key"的客户端机制；做饭链路未使用已有的 `PendingWriteStore` |
| **Files** | `feature-ac/.../catalog/RecipeCatalogDetailFragment.java:481-502`；`feature-ac/.../diary/DiaryFragment.java:320-340,390-412,826-853`；`core-data/.../PendingWriteStore.java`；`app/.../AppContainer.java` |
| **Required Changes** | ①为 `confirmCooking`/`createMealEntry` 在用户开始操作时生成 key，存入 `SavedStateHandle`/`PendingWrite`，提交成功后丢弃 ②请求期间锁定按钮（`SubmitButton` 已具备） ③结果未知时提示"结果待确认"并允许同 key 重试 ④接入用户/家庭作用域校验，避免跨账号复用 |
| **Dependencies** | G-05（服务端要能接受重放） |
| **Risk** | 中低。纯客户端，复用现成设施；但需覆盖进程死亡恢复（`PendingWrite` 已有设计） |
| **Verification** | MockWebServer 单测：同 key 连续两次只产生一次请求结果处理；模拟超时后重试仍用同 key；服务端集成测试：同 key 重复 POST 只产生 1 条 `cooking_events` + 1 组 `COOK` 移动 |

---

### G-05 服务端确认做饭不校验分配完整性 —— **P0**

| 项 | 内容 |
| --- | --- |
| **Requirement** | CKG-03、STK-09、DAT-11：按"确认的食材用量"扣库存；不得扣错、不得空扣、不得负库存 |
| **Current** | `confirm` 只校验 `allocations != null`（`CookingEventService.java:67-69`），**允许 `[]`**；`normalise` 只校验"该配料属于本配方、单位一致、数量>0"（`:156-180`），不要求覆盖配方需求；`deduct` 仅按 `household_id+id` 取批次（`:140-143`），**不校验批次配料/单位**；不校验批次是否过期（`EXPIRED_BATCH` 在契约中存在但代码从不抛出） |
| **Gap** | 可写出"做了饭但一滴不扣"、"用 B 配料批次冲抵 A 配料"、"扣到过期批次"；破坏"历史与库存一致" |
| **Files** | `server/.../record/CookingEventService.java:65-104,138-180`；`contracts/openapi.json`（`EXPIRED_BATCH` 声明） |
| **Required Changes** | ①校验每个 `trackStock=true` 配料都有分配且合计 = 需求（`CEILING` 同一精度） ②`deduct` 增加 `ingredient_id` 与 `unit` 匹配校验，不匹配 → `INVALID_ALLOCATION` ③过期批次 → `EXPIRED_BATCH` 409 ④`expectedVersion` 缺失 → 400 而非 NPE 500 ⑤补集成测试 |
| **Dependencies** | 无（可独立做） |
| **Risk** | 中。收紧校验会让现有客户端在"部分分配"时失败——但当前客户端总是提交 preview 的完整 allocations，所以正常路径不受影响 |
| **Verification** | 空 allocations → 400/409；少分配 → 拒绝；错配料批次 → 拒绝；过期批次 → 409；正常路径仍成功；并发重试仍只扣一次 |

---

### G-06 家庭菜谱更新非事务 —— **P0（数据会被写坏）**

| 项 | 内容 |
| --- | --- |
| **Requirement** | EC-08（历史/数据一致）、RCP-07；隐含要求：更新菜谱不能留下半成品数据 |
| **Current** | `HouseholdRecipeService.update` 顺序执行 `UPDATE recipes` → `DELETE recipe_ingredients` → 逐条 `INSERT`，**没有事务**（`:101-113`）；而 `create` 用了 `transactions.execute`（`:89`）。若 INSERT 失败（重复 ingredientId、非法引用），菜谱会变成"没有食材" |
| **Gap** | 缺少事务边界；客户端 `HouseholdRecipes` 的子串匹配很容易产出重复 `ingredientId`，触发概率不低 |
| **Files** | `server/.../recipe/HouseholdRecipeService.java:101-113` |
| **Required Changes** | 用 `transactions.execute` 包裹整个 update；`DELETE+INSERT` 前先做去重/校验；补测试：提交重复 ingredientId 时旧数据保持不变 |
| **Dependencies** | 无 |
| **Risk** | 低（改动小、语义明确） |
| **Verification** | 集成测试：构造会失败的 update，断言原 `recipe_ingredients` 仍完整、version 未递增 |

---

### G-07 幂等重放不重新鉴权 —— **P0（越权读取）**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PRM-05/11、§5.3"重放仍校验当前权限"：移除成员后不能继续读取家庭内容 |
| **Current** | `PlanningService.commit`（`:126`）、`CookingEventService.confirm`（`:103`）、`MealEntryService.create`（`:76`）的 `authorizeReplay` 是空实现 `ignored -> { }`。被移除成员持原 key + 原请求体重放，可拿回完整响应（家庭计划/烹饪事件）。对照：`InventoryService`（`:282,343`）与 `HouseholdWriteService` 会重新鉴权 |
| **Gap** | 三条写链路的幂等重放绕过成员校验 |
| **Files** | `server/.../plan/PlanningService.java:107-128`；`server/.../record/CookingEventService.java:65-104`；`server/.../record/MealEntryService.java:67-76` |
| **Required Changes** | 三个 replay 回调改为重新执行 `requireMember`/owner 校验（与 `InventoryService` 同款），失败返回 403/404 |
| **Dependencies** | G-01（若同时引入角色，重放也要校验角色） |
| **Risk** | 低；但必须补测试，避免影响正常重放 |
| **Verification** | 集成测试：提交成功 → 移除成员 → 同 key 重放 → 403/404，不返回业务数据 |

---

### G-08 食记缺料行把"未知可用量"显示为 0 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | CKG-12、STK-04、EC-08：未知不是零 |
| **Current** | `DiaryFragment.java:348-350`：`required == null ? "?" : required`、`available == null ? "0" : available` |
| **Gap** | 未知可用量被渲染成"0"，会让用户误以为"一点都没有" |
| **Files** | `feature-ac/.../diary/DiaryFragment.java:342-360`；`ac_strings.xml` |
| **Required Changes** | `available null → "未知"`（新增字符串），`required null → "无法精确估算"`；缺料行统一走同一格式化函数 |
| **Dependencies** | 无 |
| **Risk** | 低 |
| **Verification** | 单测覆盖格式化函数三态（有值/未知/无法计算） |

---

### G-09 计划锁不是权限边界 —— **P1（并入 G-01 修）**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PRM-05：权限不能靠"状态位"代替 |
| **Current** | 任何成员可 `PUT .../lock` 解锁（`PlanningService.java:85-105` 只 `requireMember`），再覆盖内容；`commit` 遇锁槽静默跳过（`:134-137`），调用方无法感知 |
| **Gap** | 锁被当成保护机制但可被任意成员解除；生成方无法知道哪些槽被跳过 |
| **Files** | 同 G-01/G-02 |
| **Required Changes** | 锁/解锁纳入权限矩阵（PD-02）；`commit` 返回 `skippedLockedSlots` 或对显式目标槽返回 409 |
| **Dependencies** | G-01、PD-02 |
| **Risk** | 低 |
| **Verification** | 成员解锁 → 按决策允许/403；commit 含锁槽 → 响应明确标注跳过 |

---

### G-10 计划槽并发首插返回 500 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PRM-09：并发冲突不静默覆盖、要可识别 |
| **Current** | `INSERT INTO plan_slots`（`:68-70,141-143`）未处理 `(household_id,date,meal_type)` 唯一键冲突；`PlansErrors` 只映射 `PlanFault` → `DuplicateKeyException` 变成 500 |
| **Files** | `server/.../plan/PlanningService.java`、`PlansErrors.java` |
| **Required Changes** | 捕获 `DuplicateKeyException` → `VERSION_CONFLICT` 409；补并发测试 |
| **Dependencies** | 无 |
| **Risk** | 低 |
| **Verification** | 两线程同时首插同一槽 → 一个成功、一个 409（不是 500） |

---

### G-11 归档菜谱在 App 内不可见、无法恢复 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | RCP-09：保留归档而非破坏历史；应能找回 |
| **Current** | 服务端支持 `archived` 过滤与归档切换；客户端所有列表硬编码 `archived=false`（`RecipeCatalogFragment.java:273,293`），无"查看已归档"入口 |
| **Files** | `feature-ac/.../catalog/RecipeCatalogFragment.java`、`RecipeCatalogDetailFragment.java` |
| **Required Changes** | 我的/家庭 Tab 增加"已归档"筛选 chip；列表显示归档态；详情可"恢复" |
| **Dependencies** | 无 |
| **Risk** | 低 |
| **Verification** | 归档 → 切"已归档"可见 → 恢复 → 回到正常列表 |

---

### G-12 家庭菜谱编辑器把份数/时长/标签重置为硬编码 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | RCP-07：菜谱应包含制作份数、预计时长、口味标签 |
| **Current** | `HouseholdRecipes.request` 固定 `yieldServings("2")`、`durationMinutes(30)`、`tags=["家庭菜谱"]`（`:84-88`）；编辑时用同一组装逻辑（`HouseholdRecipeEditorFragment.java:225-233`）→ 每次编辑都把这些字段**覆盖回默认值** |
| **Files** | `feature-ac/.../catalog/HouseholdRecipes.java:58-89`、`HouseholdRecipeEditorFragment.java`、`ac_fragment_household_editor.xml` |
| **Required Changes** | 编辑器增加份数/时长/标签输入；编辑时读取原值回填；转换器改为接收这些字段而不是硬编码；默认值仅在新建且用户未填时使用 |
| **Dependencies** | 无 |
| **Risk** | 中。改表单与转换器会影响"从公共/个人加入家庭"路径（那条路径需要合理默认值） |
| **Verification** | 新建填 4 份/45 分钟/标签 → 保存 → 详情显示正确 → 再次编辑不被重置 |

---

### G-13 个人菜谱编辑丢失数量与单位 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | RCP-07：食材必须含数量与单位 |
| **Current** | 编辑器读取时只回填 `item.getName()`（`PersonalRecipeEditorFragment.java:212-216`），保存时只构造 `name`（`:204-210`）→ 编辑一次就把原有数量/单位抹掉 |
| **Files** | `feature-ac/.../catalog/PersonalRecipeEditorFragment.java` |
| **Required Changes** | 回填并保留 `quantity/quantityPrefix/unit/note`；UI 至少提供数量与单位输入（可复用 `QuantityInputs`） |
| **Dependencies** | 无 |
| **Risk** | 低-中（表单改动） |
| **Verification** | 建含"番茄 200 g"→ 编辑其他字段保存 → 数量单位仍在 |

---

### G-14 未来日期可记录实际用餐 —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | CKG-07：未来日期只做计划，不记录实际用餐 |
| **Current** | 月历所有日期格可点（`DiaryFragment.java:538-545`）；服务端只做日期格式解析（`MealEntryService.java:142`），无上限 |
| **Files** | `DiaryFragment.java`、`MealEntryService.java` |
| **Required Changes** | 客户端禁用未来日期新增；服务端拒绝 `date > 今天(用户 zone)`（按 `users.zone_id`） |
| **Dependencies** | 无 |
| **Risk** | 低（注意时区边界：以用户 zone 的"今天"为准） |
| **Verification** | 明天新增 → 客户端不可点；直接调 API → 400；今天/过去仍可 |

---

### G-15 家庭菜谱图片不校验归属 —— **P1（安全）**

| 项 | 内容 |
| --- | --- |
| **Requirement** | EC-08/§7 "图片 ID 必须属于可访问范围且 scope 匹配" |
| **Current** | 个人菜谱走 `validateImage`（`PersonalRecipeService.java:160-170`）；家庭菜谱直接把 `body.getImageId()` 写库（`HouseholdRecipeService.java:104-108`），DB 只保证 media 行存在 → 可挂他人/跨家庭图片，读取时被 `MediaService` 拒绝，表现为坏图 |
| **Files** | `HouseholdRecipeService.java`、`MediaService.java` |
| **Required Changes** | 家庭菜谱 create/update 增加 `validateImage(actor, home, imageId)`（household scope 或本人 personal） |
| **Dependencies** | 无 |
| **Risk** | 低 |
| **Verification** | 用他人 personal media → 400/403；用本家庭 media → 成功 |

---

### G-16 收藏列表不复查可见性 —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PRM-11：退出/移除后不能继续读取家庭内容 |
| **Current** | `RecipeFavoriteService.java:18-26` 对 HOUSEHOLD 只按 id 连接，无 membership 过滤 → 退出家庭后仍能看到该菜谱的名称/emoji/分类 |
| **Files** | `RecipeFavoriteService.java` |
| **Required Changes** | 列表与详情查询对 HOUSEHOLD kind 增加 `EXISTS active membership` 过滤；不可见时跳过或标记不可用 |
| **Dependencies** | 无 |
| **Risk** | 低 |
| **Verification** | 收藏家庭菜谱 → 退出家庭 → 想吃列表不再显示该菜谱 |

---

### G-17 家庭菜谱 Tab 的搜索框空转 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | RCP-08：列表支持搜索 |
| **Current** | 家庭 Tab 传 `q=null`（`RecipeCatalogFragment.java:273`），但搜索框仍显示（`:107`）→ 输入无反应 |
| **Files** | `RecipeCatalogFragment.java` |
| **Required Changes** | 传 `q`；或按决策隐藏家庭 Tab 搜索框。推荐传 `q`（服务端已支持） |
| **Dependencies** | 无 |
| **Risk** | 低 |
| **Verification** | 家庭 Tab 输入关键字 → 结果收敛；清空恢复 |

---

### G-18 匹配不累计多菜需求 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | STK-12、QA-03：多道菜共享库存时应在计划汇总重新估算缺口 |
| **Current** | `InventoryMatchingService.match` 对每个 entry 都用**同一份完整 stock** 独立计算（`:32-46`），stock 不扣减；两菜各 600g、库存 800g 会双双 `ENOUGH` |
| **Files** | `server/.../inventory/InventoryMatchingService.java`、`InventoryCalculator.java` |
| **Required Changes** | 新增"计划级匹配"能力：按 entry 顺序扣减工作库存副本，累计需求；或在 `match` 增加 `aggregate=true` 语义（**契约改动，需 IC**）。UI 只展示结果 |
| **Dependencies** | G-26（客户端接线）、契约变更流程 |
| **Risk** | 中。若改 `match` 语义会影响既有调用方（当前无生产调用方，风险可控） |
| **Verification** | 两菜合计超库存 → 第二道报 `MISSING` 且 `available` 为剩余量；单菜不受影响 |

---

### G-19 计划列表分页 `total` 错误 —— **P3**

| 项 | 内容 |
| --- | --- |
| **Requirement** | 契约一致性（`total` 应为总数） |
| **Current** | `PlanningService.list` 用 `slots.size()` 冒充总数（`:54`） |
| **Files** | `PlanningService.java` |
| **Required Changes** | 单独 count 查询 |
| **Risk** | 低 |
| **Verification** | 请求 size=1 时 `total` 仍为总条数 |

### G-20 计划 `PLAN_SLOT_LOCKED` 与契约 `PLAN_LOCKED` 不一致 —— **P3**

| 项 | 内容 |
| --- | --- |
| **Requirement** | 契约与实现一致（EC-06） |
| **Current** | 服务端抛 `PLAN_SLOT_LOCKED`（`PlanningService.java:75`），契约声明 `PLAN_LOCKED`（`openapi.json`） |
| **Required Changes** | 统一错误码（优先改服务端对齐契约） |
| **Risk** | 低；需同步客户端文案映射 |

---

## 3.3 Partially Implemented（部分实现）

### G-21 公共→个人副本：无来源、丢字段、按名字去重 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | RCP-04：公共菜谱可复制为可编辑个人副本，避免修改公共原件；建议记录来源 |
| **Current** | 客户端 `saveToMine()` 先按**名称**查询我的菜谱去重（`RecipeCatalogDetailFragment.java:363-395`），再 `POST /me/recipes` 字段级复制（`:397-431`）。不设置 `originHouseholdRecipeId`（也无 catalog 来源字段），丢失 `servings/servingSize/sourceUrl` |
| **Gap** | 无来源追踪 → 无法可靠去重/追溯；字段丢失导致副本信息不完整 |
| **Files** | `RecipeCatalogDetailFragment.java`、`PersonalRecipeEditorFragment.java`、`contracts/openapi.json`、`user_recipes` 迁移 |
| **Required Changes** | ①迁移新增 `user_recipes.origin_catalog_recipe_id`（可空、无强 FK 或 FK 到 catalog）②`CreatePersonalRecipeRequest` 增加可选来源字段（契约改动 + IC）③客户端复制时写入来源并补 `servings/servingSize` ④去重改为按来源优先、名称兜底 |
| **Dependencies** | 契约变更流程（IC）、EC-07 |
| **Risk** | 中。契约 + 迁移 + 客户端三处联动；历史数据无来源需容忍 null |
| **Verification** | 复制 → 副本带来源；再次复制 → 提示已存在并可打开；改副本 → 公共详情不变；两用户各自副本互不影响 |

---

### G-22 公共/个人→家庭：字典子串匹配，多数失败 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | RCP-04/RCP-07/PLN-04：公共/个人菜谱应能进入家庭空间（家庭计划候选需要公共菜谱） |
| **Current** | `HouseholdRecipes.request` 用 26 条字典做 `equals/startsWith/contains` 匹配（`:58-89`），任一名称不匹配即 `return null`，UI 提示"食材未知"并中止（`RecipeCatalogDetailFragment.java:531`）；份数/时长/标签硬编码；数量取"最后一个空格后的数字"；丢失原单位语义 |
| **Gap** | 1307 条公共菜谱中绝大多数食材名不在 26 条字典内 → 转换成功率极低；转换结果字段失真 |
| **Files** | `feature-ac/.../catalog/HouseholdRecipes.java`、`RecipeCatalogDetailFragment.java`、`server/.../inventory/InventoryService.java`（词典接口）、`V20261123203000__BD_starter_ingredient_dictionary.sql` |
| **Required Changes** | ①把"未知食材"从**阻断**改为"保留为不计库存的自由行"或"引导用户选择/新增标准食材"（二选一需产品确认）②扩词典（按 V2 RCP-11 建议）③份数/时长/标签改为可编辑 ④数量解析至少支持"200g/2个"等常见写法 ⑤提供"无法映射"的可视化说明 |
| **Dependencies** | G-12、PD-10（是否允许复制到家庭）、词典扩充策略 |
| **Risk** | 中高。这是"家庭计划用公共菜谱"的必经之路；若只扩词典而不改阻断逻辑，覆盖率仍低 |
| **Verification** | 抽样 20 道公共菜谱：转换成功率、字段保真度、未知食材提示是否清晰；转换后能进入家庭计划 |

---

### G-23 家庭计划候选池只有家庭菜谱 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PLN-04：家庭计划可用公共 + 家庭菜谱 |
| **Current** | 客户端只 `listRecipes(householdId)`（`BdHomeFragment.java:283-284`、`PlanFragment.java:120`）；服务端 `validate` 强制 `recipes.household_id=? AND NOT archived`（`PlanningService.java:199-201`）；`plan_items` 复合 FK 也只允许本家庭菜谱（`V1__baseline.sql:166`） |
| **Gap** | 三层（UI / 服务端校验 / 外键）都不支持公共菜谱直接进入家庭计划 |
| **Files** | `V1__baseline.sql`、`PlanningService.java`、`contracts/openapi.json`（PlanItem）、`PlanFragment.java`、`BdHomeFragment.java` |
| **Required Changes** | **路径 A（推荐，兼容性最好）**：不改进计划引用模型，改为"从公共菜谱加入家庭计划时，显式创建家庭菜谱副本"（复用 G-22 转换，但改为**非阻断**），计划仍只引用家庭菜谱。**路径 B（更符合 V2 字面）**：`plan_items` 增加 `catalog_recipe_id` + `CHECK(num_nonnulls(recipe_id, catalog_recipe_id)=1)`，`PlanItem` 增加 `recipeKind`，服务端允许 catalog 引用（匹配状态为"无法判断"）。路径 B 需要迁移 + 契约 + 客户端 + 匹配降级，工作量约为 A 的 2–3 倍 |
| **Dependencies** | PD-10、G-22、G-18（匹配降级）、契约变更流程 |
| **Risk** | **高**（路径 B）：动共享表结构 + 复合外键 + 契约 + 所有计划读写 + 历史数据。路径 A 风险中低，但会在家庭菜谱库中产生大量"公共副本" |
| **Verification** | 路径 A：公共菜谱 → 加入家庭 → 出现在家庭计划候选 → 可排餐；路径 B：直接选公共菜谱 → 计划保存成功 → 匹配显示"无法判断"且不阻断 |

---

### G-24 个人计划候选只有公共目录，且不上服务端 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PLN-01/PLN-03：我的计划可用公共 + 个人 + 当前家庭菜谱；默认仅本人管理 |
| **Current** | 个人侧候选 = 打包资产 `assets/recipes/catalog.json` + 公共目录 API（`BdHomeViewModel.java:99-110,420-445`）；**个人菜谱与家庭菜谱完全不出现**；加菜只有"盲盒/偏好盲盒/手写菜名"三种（`BdFlowDialog.java:169-243`），没有"从我的菜谱挑一道"；计划保存在本地 JSON，与服务端无关 |
| **Gap** | 候选池缺两类；缺少可浏览的选择器；跨设备不可用；无"从菜谱库选"的入口 |
| **Files** | `feature-bd/.../BdHomeViewModel.java`、`BdFlowDialog.java`、`BdDraftStore.java`、`BdDraft.java`、`feature-ac/.../plan/PlanFragment.java:172-207` |
| **Required Changes** | ①新增个人计划选择器：Tab 或分组展示"公共 / 我的 / 家庭"三来源（`/recipe-catalog`、`/me/recipes`、`/households/{id}/recipes`）②选中后写入草稿（`recipeId` + 名称 + 图片 + 食材快照）③个人计划与今天页共用同一数据源 ④**是否上服务端由 PD-12 决定**；若保持本地，需在文档与 UI 中明确"仅本机" |
| **Dependencies** | PD-12、G-25（想吃）、G-33（草稿恢复） |
| **Risk** | 中。改动集中在 feature-bd + 计划页；不碰数据库即可完成"UI 层候选池" |
| **Verification** | 个人模式可从"我的"选一道自建菜 → 保存 → 重进仍在；家庭菜谱可选但不出现在家庭计划；无家庭用户仍可用 |

---

### G-25 想吃清单只有个人维度 —— **P2（待 PD-04）**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PLN-12（已满足：想吃≠计划）；PLN-13：家庭想吃候选是否独立展示待确认 |
| **Current** | `recipe_favorites(user_id, kind, recipe_id)` 纯个人；旧 `wishlists` 也按 `user_id`，且只能家庭菜谱；无家庭共享想吃清单 |
| **Gap** | 无家庭维度；若 PD-04 确认需要，则缺表/接口/UI |
| **Files** | `V20261123202100__AC_recipe_favorites.sql`、`RecipeFavoriteService.java`、`RecipeCatalogFragment.java:312` |
| **Required Changes** | 待决策。若需要：新增 `scope`/`household_id`/`created_by`（或新表），加家庭想吃接口，权限与计划一致（成员可加，房主可删） |
| **Dependencies** | PD-04、G-01（权限模型） |
| **Risk** | 中（新增表 + 契约 + UI） |
| **Verification** | 待需求确认后定义 |

---

### G-26 库存匹配完全没有接入 UI —— **P1（V2 的核心体验）**

| 项 | 内容 |
| --- | --- |
| **Requirement** | STK-02/03/04、QA-02、§4"默认可做优先 + 缺料标签 + 入口" |
| **Current** | 服务端 `POST /recipe-matches` 与 `POST /cooking-previews` 可用且质量高；**客户端零调用 `matchRecipes`**（全仓只有服务端与测试命中）；计划/今天/菜谱列表都没有"可做"标记、没有排序、没有筛选、没有缺料清单。唯一与库存相关的交互是做饭时的阻断 |
| **Gap** | 整个"库存感知推荐"在 UI 层不存在 |
| **Files** | `feature-ac/.../plan/PlanFragment.java`、`feature-bd/.../BdHomeFragment.java`、`BdFlowDialog.java`、`feature-ac/.../catalog/RecipeCatalogFragment.java`、`core-data/.../ApiProvider.java` |
| **Required Changes** | ①封装 `MatchRepository`（feature 内，不违反模块边界）批量调用 `matchRecipes` ②在选菜弹窗/计划页/详情页展示 `可做/部分不足/缺少/无法判断` 徽标 ③默认可做优先排序 + "全部/仅可做/缺料"切换 ④缺料时展示"缺什么、缺多少"，按钮保持可用（Snackbar 提示）⑤未知不显示为可做 ⑥只对家庭菜谱计算；公共/个人显示"无法判断" |
| **Dependencies** | G-18（累计）、G-22/G-23（公共菜谱可匹配的前提） |
| **Risk** | 中。只读调用，不动数据；但涉及三个页面的布局与状态，需统一的"匹配徽标"组件（否则会破坏 UI 一致性） |
| **Verification** | 空冰箱 → 全部"缺少"仍可选/可保存；有料菜谱排前；切"全部"缺料可选；未知显示"无法判断"；计划汇总缺料正确 |

---

### G-27 计划页个人模式只读、无改份数 —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PLN-07：手动排餐可修改计划份数 |
| **Current** | `PlanFragment` 个人模式只渲染（`:172-207`）；家庭模式每行只有"选菜/锁定"，份数固定 `"1"`（`:307`），无 +/- 或输入 |
| **Required Changes** | 个人模式可编辑（与 Today 一致）；家庭模式增加份数编辑（受权限矩阵约束，PD-01） |
| **Dependencies** | G-24、PD-01 |
| **Risk** | 中（涉及权限矩阵） |
| **Verification** | 改份数后服务端保存正确；成员减份按决策处理 |

---

### G-28 计划页固定"今天起 14 天"，无 1 天/自选范围 —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PLN-06：日期支持今天/明天/自选/连续 14 天 |
| **Current** | `start = LocalDate.now()` 且从不改变（`PlanFragment.java:51,146-147`）；无 1 天视图、无翻页 |
| **Required Changes** | 增加日期范围切换（1 天 / 7 天 / 14 天）+ 上一段/下一段；保留选择状态（`UI-13`） |
| **Dependencies** | G-42（生成）共用日期范围组件 |
| **Risk** | 低 |
| **Verification** | 切范围/翻页后数据正确、不丢选择、不重复提交 |

---

### G-29 空态/错误态/加载态各自为政 —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | UI-10/UI-14：统一组件反馈 |
| **Current** | 只有 `Feedback`（Snackbar）与 `SubmitButton` 是共享的；空态/错误态/加载态在 ac/bd/ef 各自手写（`RecipeCatalogFragment.java:333-346`、`InventoryFragment` 的 `ProgressBar+ac_message+ac_recovery`、`DiaryFragment` 内联 TextView）；公开/个人详情加载失败是空 `onError`（`RecipeCatalogDetailFragment.java:112,168`） |
| **Required Changes** | 在 `core-ui` 新增 `CivaStateView`（loading/empty/error 三态 + 重试回调）与 `CivaStatusBadge`（可做/缺料/未知/权限）；逐步替换各 feature 的内联实现 |
| **Dependencies** | 无（但需遵守 resourcePrefix 与 PublicSurface 登记） |
| **Risk** | 中低。`core-ui` 是共享模块，改动需过 `PublicSurfaceArchTest`；建议先新增不替换，再逐页迁移 |
| **Verification** | 三态在窄/宽窗口、深浅背景、长文案下不溢出；失败的详情页有可见错误与重试 |

---

### G-30 平板/横竖屏状态保持不完整 —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | UI-12/UI-13：切换窗口后保留 scope/筛选/日期/草稿；14 天窄屏可浏览 |
| **Current** | 无 `layout-sw600dp`/`land` 资源；Today 的 scope 只存 `static`（进程死亡丢失，`BdHomeFragment.java:47-49`）；Plan 无起始日选择；菜谱无 master-detail；冰箱有双栏但写死 720dp/270dp/450dp |
| **Required Changes** | ①scope/筛选/日期进 `SavedStateHandle` ②菜谱列表+详情双栏（≥600dp）③14 天窄屏按周/日期分段 ④窗口断点收敛到 `NavigationMode` 类似工具 |
| **Dependencies** | G-29（状态组件） |
| **Risk** | 中。纯表现层，但涉及多页面 |
| **Verification** | 横竖屏/分屏切换不丢日期与选择；大字体不截断；窄屏 14 天可分页浏览 |

---

### G-31 英文环境缺 11 个字符串 —— **P3**

| 项 | 内容 |
| --- | --- |
| **Current** | `bd_elastic_choices.xml` 中文 19 键 / 英文 8 键（缺 `bd_dish_spicy_*`、`bd_dish_meat_*`、`bd_dish_quick_*`）→ 英文下回落中文 |
| **Required Changes** | 补 11 个英文键 |
| **Risk** | 极低 |
| **Verification** | 英文环境 Today 加菜偏好选项无中文 |

---

### G-32 食记快照偏弱 —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | CKG-11：保存当时的菜名、份量和热量快照；修改菜谱不回写 |
| **Current** | `meal_entries` 存 `name/amount/basis_kcal/total_kcal/note`，`recipe_id` 为弱引用，不存食材/步骤快照；菜谱删除后 `recipe_id` 悬空（DB 无 ON DELETE 处理） |
| **Required Changes** | 至少在创建时把菜名与热量基准固化（已部分具备）；补"菜谱已删除"的展示降级；若要完整快照，需新增 `entry_snapshot` JSONB（契约改动） |
| **Dependencies** | 契约变更流程 |
| **Risk** | 中（契约 + 迁移） |
| **Verification** | 删除个人菜谱后历史食记仍显示菜名与热量，不崩溃 |

---

### G-33 食记日汇总缺"另有 N 条未估算"的显式展示 —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | CKG-12：日汇总显示"已记录 X kcal，另有 N 条未估算" |
| **Current** | 服务端返回 `knownKcal/unknownCount`；客户端展示 known 与 unknownCount（`DiaryFragment.java:137-160`），但日摘要依赖月摘要缓存，月摘要失败时显示"这天还没有记录"（把错误当空态） |
| **Required Changes** | 明确文案与错误/空态区分 |
| **Risk** | 低 |
| **Verification** | 有 1 条未知热量时显示"另有 1 条未估算"；接口失败显示错误而非空 |

---

### G-34 做饭缺料时无"缺什么/缺多少/只记录"的统一出口 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | STK-04、§3.3"缺料只提示不阻断"（针对计划）；做饭侧需按 PD-07 决定 |
| **Current** | 详情页只要 preview 有 shortage 就 toast "还差一些食材" 并中止（`RecipeCatalogDetailFragment.java:476-479`），不展示明细；食记路径有明细对话框 + "仍记一餐"（`DiaryFragment.java:342-360`）——两条路径行为不一致 |
| **Required Changes** | 统一为一个 `ShortageDialog`：展示缺料明细（未知显式标注）+ 两个出口（"调整用量"/"仍记录做饭并修正库存"或"仅记录食记"），按 PD-07 决定出口语义 |
| **Dependencies** | PD-07、G-03 |
| **Risk** | 中（行为变化 + 文案） |
| **Verification** | 缺料时能看到"鸡蛋缺 2 个"；按决策可继续或提示修正；不产生负库存 |

---

### G-35 单人（无家庭）不可用冰箱与库存匹配 —— **P2（待 PD-06）**

| 项 | 内容 |
| --- | --- |
| **Requirement** | STK-07（UI 单入口）、V2 §11"单人模式如何对应统一库存"待确认 |
| **Current** | `inventory_batches.household_id NOT NULL`；`InventoryViewModel` 无家庭直接报"需登录/去建家庭"（`:119-124`）；无家庭用户无法使用冰箱，也无法匹配 |
| **Required Changes** | 待 PD-06。若需要：允许"个人库存空间"（`household_id` 可空 + `owner_user_id`，或创建隐式个人 household），并明确建/入家庭时是否迁移 |
| **Dependencies** | PD-06、G-01 |
| **Risk** | 高（表结构 + 全局库存查询 + 匹配 + 权限） |
| **Verification** | 待决策 |

---

### G-36 家庭计划跨设备同步"部分"（无冲突提示体验） —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PRM-09/QA-18：并发冲突提示刷新确认，不静默覆盖 |
| **Current** | 服务端 409 正确；客户端把 409 一律显示成"计划已被家人修改"（`PlanFragment.java:317-320`），不刷新、不给"重新加载"动作，用户只能退出重进；家庭加菜无 in-flight 保护，重复点会连发两个 replace |
| **Required Changes** | 409 时自动重载该槽 + 提示"已刷新，请重试"；加菜/锁定加提交锁 |
| **Risk** | 低 |
| **Verification** | 两会话同时改同一槽 → 后提交者看到刷新提示且数据为最新 |

---

### G-37 错误处理缺少兜底与日志 —— **P2**

| 项 | 内容 |
| --- | --- |
| **Requirement** | 交付可用性；不影响业务规则但影响可诊断性 |
| **Current** | 服务端无 `Exception` 兜底（未预期异常走 `/error`，非契约信封）、全仓 0 Logger；客户端无全局崩溃处理、3 处真正静默失败（`ProfileFragment.java:62,113`、`BdTodayPlanReader.java:37-39`） |
| **Required Changes** | 服务端加 `@ExceptionHandler(Exception.class)` + 结构化日志（traceId）；客户端至少把 3 处静默失败改为可见提示 |
| **Risk** | 低 |
| **Verification** | 人为触发异常 → 返回契约信封 + 服务端有 traceId 日志；草稿损坏 → 用户看到提示而非"今天没有计划" |

---

### G-38 缓存跨账号残留 + Room 缓存未接线 —— **P2（安全/一致性）**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PRM-11：切账号隔离缓存 |
| **Current** | 设计良好的 `ApiCalls.enqueueCached`（按 userId+householdId 分区、TTL、登出清理）**生产零调用**；实际生效的 `ac_catalog_cache_v1` 不含账号键、登出不清理（可残留上一账号的个人菜谱快照，需命中同 UUID 才可读，低危）；`bd_catalog_cache_v2` 只含公共数据 |
| **Required Changes** | ①`CatalogCache` 加 userId 前缀 + 登出清理 ②决定是否启用 Room 缓存（若启用则替换 SP 缓存） |
| **Risk** | 低-中（改动影响离线体验） |
| **Verification** | 账号 A 存缓存 → 登出 → 账号 B 登录 → 看不到 A 的数据 |

---

## 3.4 Missing Feature（尚未实现）

### G-40 房主角色与统一权限状态 —— **P0，阻塞于 PD-11**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PRM-03/04/05/16、UI-09、UI-14"权限来自统一状态/接口" |
| **Current** | 无角色；客户端无权限状态 |
| **Required Changes** | ①（PD-11 决策）`households.owner_user_id` 或恢复 `memberships.role='OWNER'`；推荐前者（加法迁移、不推翻"全员平等"对普通成员的语义）②`Household` 响应增加 `ownerUserId`（契约改动 + IC）③客户端 `SessionStore`/`AppContainer` 暴露 `isHouseholdOwner()`，各页面统一读取 ④UI 门控 |
| **Dependencies** | PD-11、G-01 |
| **Risk** | 高（跨数据/契约/客户端） |
| **Verification** | 房主/成员/非成员三种身份在 8 个写操作上的矩阵测试 |

---

### G-41 服务端内容 diff 权限判定 —— **P0，阻塞于 PD-01/03**

| 项 | 内容 |
| --- | --- |
| **Requirement** | DAT-10、PRM-08、PLN-20 |
| **Current** | 无（见 G-02） |
| **Required Changes** | 新增 `PlanDiff`（纯函数、可单测）+ 在 `replace`/`commit` 事务内调用；产出语义操作集并据此鉴权；同一实现供"生成写入"复用 |
| **Dependencies** | G-01、G-40 |
| **Risk** | 中（逻辑复杂但可纯函数化测试） |
| **Verification** | 12 种前后组合的表驱动单测 + HTTP 集成测试 |

---

### G-42 1 天 / 14 天计划生成 + 预览 —— **P1**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PLN-08/09/11、UI-08 |
| **Current** | 服务端无生成端点；客户端无生成 UI；`commitPlan`（≤42 槽）已实现但零调用 |
| **Required Changes** | **推荐客户端生成 + 服务端 commit**：①新增生成器（纯函数：候选池、餐次、人数、口味、时长、库存优先排序、锁定/已有保留）②预览页展示菜品/日期/餐次/份数/可做状态/汇总缺料 ③确认后走 `commitPlan`（带 expectedVersion，保留锁槽）④权限：成员生成若命中删减 → 拒绝或降级为"只填空位"（依赖 G-41） |
| **Dependencies** | G-41、G-23/G-24（候选池）、G-26（可做状态） |
| **Risk** | 高（新功能 + 与权限交叉 + 与锁定交叉） |
| **Verification** | 生成 1 天/14 天预览不改库；确认后只写预览内容；锁定槽不变；成员生成不覆盖他人内容；缺料不阻止生成 |

---

### G-43 生成/重生成对锁定与权限的遵守 —— **P1（与 G-42 同批）**

| 项 | 内容 |
| --- | --- |
| **Current** | `commit` 静默跳过锁槽（`PlanningService.java:134-137`）但不告知；生成覆盖无权限概念 |
| **Required Changes** | 预览阶段即标注"锁定槽将跳过"；确认响应返回 `skippedLocked` 列表；成员生成走 G-41 |
| **Dependencies** | G-41、G-42 |
| **Risk** | 中 |
| **Verification** | 含锁定槽的 14 天生成 → 预览标注 → 确认后锁槽内容不变且提示 |

---

### G-44 计划汇总缺口 + "按目前库存估算" —— **P1（依赖 G-18）**

| 项 | 内容 |
| --- | --- |
| **Current** | 无累计缺口；14 天计划无库存估算说明 |
| **Required Changes** | 计划页/预览页展示"本计划共缺 X 项，其中可估算 Y 项" + 14 天标注"按目前库存估算，未来库存可能变化" |
| **Dependencies** | G-18、G-26 |
| **Risk** | 中 |
| **Verification** | 两菜共享库存 → 汇总缺料正确；14 天页面有免责说明 |

---

### G-45 匹配四态（部分不足/无法判断）在 UI 的呈现 —— **P1（依赖 G-26）**

| 项 | 内容 |
| --- | --- |
| **Current** | 服务端只有 `ENOUGH/MISSING/UNKNOWN`，"部分不足"并入 `MISSING`；客户端无任何呈现 |
| **Required Changes** | UI 层基于 `available/required` 派生"部分不足"（不改服务端枚举，避免契约改动）；`UNKNOWN` 显示"无法判断"；统一徽标组件 |
| **Dependencies** | G-26 |
| **Risk** | 低 |
| **Verification** | 三态 + 未知的视觉与文案快照 |

---

### G-46 家庭计划直接引用公共菜谱（数据层） —— **P1/P2，取决于路径选择**

见 G-23（路径 A 不改模型，路径 B 改）。**若选路径 A，本项标为"不做"并记录裁剪理由。**

---

### G-47 家庭想吃清单 —— **P2，阻塞于 PD-04**

见 G-25。

---

### G-48 食记选周/选月回顾 —— **P3**

| 项 | 内容 |
| --- | --- |
| **Requirement** | CKG-13（可在稳定后做） |
| **Current** | 月历 + 月摘要；无周视图 |
| **Required Changes** | 周视图 + 周汇总（服务端 `meal-summary` 已支持任意 from/to） |
| **Risk** | 低 |
| **Verification** | 周边界按周一到周日；跨月周正确 |

---

### G-49 缺料时"记录真实做饭 + 引导修正库存" —— **P1，阻塞于 PD-07**

见 G-34。

---

### G-50 客户端统一权限状态 —— **P0（与 G-40 同批）**

| 项 | 内容 |
| --- | --- |
| **Requirement** | UI-14"权限来自统一状态/接口，不能各页面手写房主判断" |
| **Current** | 无 |
| **Required Changes** | `SessionStore` 或 `AppContainer` 暴露 `householdRole()`；提供单一 `PlanPermissions` 工具；所有页面读同一来源 |
| **Dependencies** | G-40 |
| **Risk** | 中 |
| **Verification** | 代码检索：全仓只有一处房主判断实现；页面无重复逻辑 |

---

### G-51 软删除与审计日志 —— **P3**

| 项 | 内容 |
| --- | --- |
| **Requirement** | PRM-12（建议） |
| **Current** | 计划项物理删除；`plan_slots.author_id` 记录最后编辑人，但无操作历史 |
| **Required Changes** | 可选：`plan_item_audit` 表或软删除标记 |
| **Risk** | 中（表结构 + 所有查询过滤） |
| **Verification** | 审计可回答"谁在何时删了哪道菜" |

---

## 3.5 Pending Confirmation（不得自行实现）

| ID | 待确认 | 阻塞的任务 | 无决策时的安全做法 |
| --- | --- | --- | --- |
| PD-11 | 房主如何定义（与"全员平等"冲突） | G-01/G-40/G-41/G-50 | 只做不依赖角色的部分（G-03/04/05/06/07） |
| PD-01 | 成员能否增加已有项份数 | G-41 的 `INCREASE` 分支 | 默认拒绝并返回明确错误，等待确认 |
| PD-02 | 成员能否改锁/备注 | G-09/G-41 | 默认仅房主可改锁 |
| PD-03 | 成员能否移动日期/餐次 | G-41 的 `MOVE` | 默认禁止 |
| PD-04 | 家庭想吃清单是否独立持久化 | G-25/G-47 | 不做，保留个人收藏 |
| PD-05 | 公共菜谱来源/版权 | 内容治理 | 不做内容投稿功能 |
| PD-06 | 单人模式库存归属 | G-35 | 保持"无家庭不可用冰箱"，在文档说明 |
| PD-07 | 缺料时做饭如何处理 | G-03/G-34/G-49 | 保留现状但统一两条路径的提示，不新增自动扣减 |
| PD-08 | 房主转移/退出 | G-40 | 房主不可退出或退出即转移需决策；暂不实现转移 |
| PD-09 | 家庭菜谱/冰箱/做饭记录权限是否收紧 | — | 保持现状（成员可写），不做隐性收紧 |
| PD-10 | 是否允许公共→家庭复制 | G-22/G-23/G-46 | 保持现有"允许但常失败"，仅改善可观测性 |
| PD-12 | 个人计划是否上服务端 | G-24 | 保持本地草稿，明确标注"仅本机" |
| PD-13 | 成员能否撤销自己未提交的草稿 | — | 客户端本地草稿可自由撤销（无共享写入），不涉及服务端 |

---

## 3.6 跨模块业务流程检查（不是单页检查）

> 这一节是"页面都看起来对、但流程串起来就错"的部分。

### F-01 空冰箱 → 建菜谱 → 加想吃 → 排餐 → 生成

| 环节 | 结论 |
| --- | --- |
| 建菜谱 | ✅ 无库存前置（个人/家庭都可） |
| 加想吃 | ✅ 无库存前置（`recipe_favorites` 不查库存） |
| 手动排餐 | 🟡 可保存，但**家庭计划只能选家庭菜谱**（G-23）；个人计划只能选公共目录（G-24） |
| 生成 1/14 天 | ⚪ **不存在**（G-42） |
| 缺料展示 | ⚪ 不存在（G-26） |
| **整体结论** | "不阻断"这一条成立，但"可选范围"和"缺料提示"两条不成立。V2 验收 QA-01 **部分不通过** |

### F-02 公共菜谱 → 个人副本 → 编辑 → 原件不变

| 环节 | 结论 |
| --- | --- |
| 复制 | 🟡 可用，但按名称去重、无来源（G-21） |
| 编辑副本 | ✅ 独立记录，不回写 |
| 原件不变 | ✅ 公共目录只读 |
| 其他用户副本 | ✅ 互不影响 |
| **结论** | QA-09 **通过**；追溯与去重不足（P1 优化，非阻塞） |

### F-03 公共菜谱 → 家庭计划

| 环节 | 结论 |
| --- | --- |
| 加入家庭菜谱 | 🟡 依赖 26 条字典，多数失败（G-22） |
| 进入家庭计划候选 | ⚪ 只有家庭菜谱（G-23） |
| 服务端接受 | ⚪ `plan_items` FK 与 `validate` 双重阻止 |
| **结论** | 该流程**事实上不可用**（除少数命中字典的菜）。这是 V2 §3.2 最关键的功能缺口之一 |

### F-04 成员新增家庭计划 → 其他成员刷新可见

| 环节 | 结论 |
| --- | --- |
| 成员新增 | ✅ 可以（`replace` 放行） |
| 其他设备刷新 | ✅ `listPlanSlots` 返回共享数据 |
| **隐患** | 所谓"新增"实际是整槽覆盖（G-02）；如果该槽已有他人内容，会被替换掉 |
| **结论** | 功能"看起来"通过，语义**不符合** V2 的"新增不得覆盖原项" |

### F-05 成员删除/减少家庭计划 → 服务端

| 环节 | 结论 |
| --- | --- |
| 客户端删减入口 | 无"删除单项"，但"清空"与"替换"等价于删除（`PlanFragment.java:299-300`） |
| 服务端 | ✅ 允许（无角色） |
| **结论** | **QA-06 直接失败**，且 QA-07（构造未授权请求）也失败——因为服务端根本没有这个授权规则 |

### F-06 计划 → 确认做饭 → 扣库存 → 食记

| 环节 | 结论 |
| --- | --- |
| 从计划"吃了" | ✅ `previewCooking` → `confirmCooking` → `createMealEntry(cookingEventId)` |
| 扣库存一次 | ✅ 服务端正确（事务 + 幂等表） |
| 重试 | 🔴 客户端每次新 key（G-04） |
| 缺料 | 🟡 有明细 + "仍记一餐"（食记路径），详情页路径直接阻断（G-34） |
| **结论** | 主链正确，旁路与健壮性不达标；QA-08/QA-12 **在重试场景下会失败** |

### F-07 个人食记 → 库存

| 环节 | 结论 |
| --- | --- |
| "在外吃" | ✅ 不碰库存 |
| "计划菜" | ✅ 走确认做饭后不二次扣 |
| "在家吃" | 🔴 **直接 `CONSUME` 扣库存**，与烹饪事件无关（G-03） |
| **结论** | **CKG-05 违规**；QA-14（次日吃剩菜不再次扣）也有风险 |

### F-08 修改菜谱 → 历史记录

| 环节 | 结论 |
| --- | --- |
| 做饭历史 | ✅ `recipe_snapshot` 冻结 |
| 食记 | 🟡 只存 name/kcal，`recipe_id` 弱引用；菜谱删除后可能悬空（G-32） |
| 家庭菜谱更新 | 🔴 非事务，可能写坏当前菜谱（G-06） |
| **结论** | 历史"基本不回写"，但当前数据有被写坏的风险 |

### F-09 两人并发改同一槽

| 环节 | 结论 |
| --- | --- |
| 已有槽 | ✅ `expectedVersion` → 409 |
| 首次创建同一槽 | 🔴 唯一键冲突 → 500（G-10） |
| 客户端体验 | 🟡 409 一律提示"家人修改"，不自动刷新（G-36） |
| **结论** | 服务端语义正确，实现有洞，体验不足 |

### F-10 移除成员 → 继续访问

| 环节 | 结论 |
| --- | --- |
| 计划读取 | ✅ 404 |
| 库存读取 | ✅ 403 `HOUSEHOLD_ACCESS_REVOKED` |
| 幂等重放 | 🔴 可拿回完整响应（G-07） |
| 收藏列表 | 🔴 仍显示家庭菜谱名称（G-16） |
| 客户端缓存 | 🟡 SP 缓存残留（G-38） |
| **结论** | **PRM-11 部分失败**；重放是最严重的口子 |

### F-11 切换账号 / 断网

| 环节 | 结论 |
| --- | --- |
| 断网读缓存 | 🟡 公共目录有 SP 缓存；计划/库存无缓存，显示错误 |
| 断网写 | ✅ 不排队、不假提交（符合 V2 首版） |
| 切账号 | 🟡 文件按账号哈希隔离；SP 缓存不隔离（G-38） |
| **结论** | 基本符合"只读缓存 + 不承诺离线合并"；缓存隔离需补 |

### F-12 14 天计划 / 预览 / 锁定

| 环节 | 结论 |
| --- | --- |
| 预览 | ⚪ 不存在 |
| 锁定槽保留 | ✅ `commit` 跳过锁槽（但静默） |
| 确认前不改库 | ⚪ 无预览 |
| **结论** | QA-11 **无法验证**（功能不存在） |

---

## 3.7 汇总：差距清单与优先级

### P0（交付前必须修）

| ID | 问题 | 类型 | 工作量估计 | 阻塞 |
| --- | --- | --- | --- | --- |
| G-01 | 无房主，成员可删减/覆盖计划 | Incorrect | 大（3–4 人日） | PD-11 |
| G-02 | 无 ADD/DELETE diff 语义 | Incorrect | 中（1–2 人日） | PD-01/03 |
| G-03 | 在家吃直接扣库存 | Incorrect | 小（0.5–1 人日） | PD-07 |
| G-04 | 客户端幂等键不稳定 | Incorrect | 小（0.5–1 人日） | — |
| G-05 | confirmCooking 校验不足 | Incorrect | 中（1 人日） | — |
| G-06 | 家庭菜谱更新非事务 | Incorrect | 极小（0.2 人日） | — |
| G-07 | 幂等重放不鉴权 | Incorrect | 小（0.3 人日） | — |

### P1（V2 已确认但未实现 / 重要缺陷）

| ID | 问题 | 工作量估计 |
| --- | --- | --- |
| G-22 | 公共/个人→家庭转换成功率极低 | 中（1–2 人日） |
| G-23 | 家庭计划候选缺公共菜谱 | 大（路径 B 3–4 人日 / 路径 A 1–2 人日） |
| G-24 | 个人计划候选缺个人/家庭菜谱 | 中（1–2 人日） |
| G-26 | 库存匹配未接入 UI | 中大（2–3 人日） |
| G-42 | 1/14 天生成 + 预览 | 大（3–4 人日） |
| G-44/45 | 汇总缺口 + 四态呈现 | 中（1–2 人日，依赖 G-18/26） |
| G-18 | 多菜累计缺口 | 中（1 人日） |
| G-17 | 家庭 Tab 搜索空转 | 极小（0.1 人日） |
| G-12/13 | 编辑器字段丢失/硬编码 | 小（0.5 人日） |
| G-15 | 家庭菜谱图片越权 | 小（0.3 人日） |
| G-34/G-49 | 缺料做饭出口不统一 | 小（0.5 人日，依赖 PD-07） |
| G-11 | 归档不可见 | 小（0.5 人日） |
| G-10 | 首插 500 | 小（0.3 人日） |

### P2 / P3（可裁剪）

G-08、G-14、G-16、G-25/G-47、G-27、G-28、G-29、G-30、G-32、G-33、G-35、G-36、G-37、G-38、G-48、G-51、G-19、G-20、G-31。

### 裁剪建议（若时间不足）

按 V2 的"最少必要修改"原则，**必须保 P0 全部 + G-26 的"只读可做徽标/缺料提示"最小版本**；可裁剪 G-42（生成）与 G-23 路径 B，改为：
- 家庭计划候选 = 家庭菜谱 + "从公共菜谱加入家庭"（路径 A，配合 G-22）；
- 不提供 1/14 天自动生成，仅保留手动排餐；
- 在报告/演示中明确说明这是首版裁剪，不声称已实现生成功能。

（最终裁剪需用户确认，见 Phase 5 §5.6）
