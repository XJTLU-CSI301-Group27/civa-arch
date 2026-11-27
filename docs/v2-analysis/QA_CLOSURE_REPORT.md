# Civa V2 QA Closure 报告

日期：2026-11-27（Asia/Shanghai）
基线：`34839134c5be69ee15a67685626ebd3a7bc3e239`
范围：QA Closure；未进入 Phase C / Final QA-2。

## A. 执行摘要

- 当前 HEAD：`3483913`
- 本轮业务代码修改：无；仅测试与报告。
- Android：**236 tests / 0 failures**
- Server：**105 tests / 0 failures**
- lint/assemble/checkContracts：通过
- 实际执行：
  - 做饭正常扣减、重复确认、库存不足：**3 项 HTTP/DB 实测**
  - MealEntry 创建/修改/删除：**1 组 HTTP/DB 实测**
  - 空冰箱导入/match/计划：沿用上一轮实测证据
  - 360dp ImportReviewDialog：**1 次真机**
- 结果计数（本轮 QA Closure 范围）：
  - PASS：**6 组**
  - FAIL：**0**
  - NOT RUN：**4 组**
  - BLOCKED：**1 组（家庭邀请接口 500）**
- 已确认 P0 缺陷：无新增；已有 fingerprint 校验 P0 已修复。
- 未确认 P0 缺陷：Pending 手工/导入冲突、损坏 pending UI 仍未实测。
- 已确认 P1：360dp 文案截断（已改文案，未复验）；C→TRACKED 替换提醒未真机验证。
- 发布结论：**NO-GO**。

## B. 测试组结果

### A. 做饭 / 库存 / MealEntry

#### A1 正常做饭 — PASS（HTTP/DB）

测试家庭：DemoHome `d8d967df-edc7-4559-aa2e-4518cf3b30f1`

准备：

- 库存批次 `b6e76441-f647-451e-9f33-8f351cd1ff8c`
- 鸡蛋 5 COUNT
- 家庭菜谱 `c05ac525-a77f-4212-9b6a-8e978761896c`
- 鸡蛋 2 COUNT + 食盐 适量（RECIPE_ONLY），份数 2

执行：

- POST `/cooking-previews`，servings=1
- allocations：1 个鸡蛋，无 shortage
- POST `/cooking-events`，Idempotency-Key

证据：

- 批次 5 → 4
- CookingEvent `73b5d1ac-ce20-4e3e-b32b-b782e2d88ed0`
- InventoryMovement `a58e85b1-...`：before 5 / after 4 / reason COOK / 关联 event
- 只出现鸡蛋 movement；食盐没有 movement
- recipeOnly 不参与扣减

结果：PASS。

#### A2 重复确认 — PASS（HTTP/DB）

- 相同 Idempotency-Key、相同 payload 再次 POST。
- 响应仍指向同一 event `73b5d1ac-...`（服务端返回 201 replay，不是第二次创建）。
- DB cooking_events 仍 1 条，movement 仍 1 条。
- 无二次库存扣减。

结果：PASS。

#### A3 不足库存 — PASS（HTTP/DB）

- 当前鸡蛋 4，按 servings=5 预览。
- preview shortages：required 5 / available 4 / MISSING。
- confirm 被拒绝：HTTP 400 `INVALID_RANGE`。
- 批次保持 4，CookingEvent 保持 1，无部分扣减/错误成功事件。

结果：PASS（服务端按现有规则拒绝；不是 409，但拒绝与无副作用符合要求）。

#### A4 MealEntry — PASS（HTTP/DB）

- 创建 MealEntry `340c09dd-b431-4b81-ac83-4580c38350c5`。
- 修改为 `RG3MealUpdated`，version 1→2。
- 使用 `expectedVersion=2` 删除成功。
- 三个操作前后：
  - 批次鸡蛋保持 4；
  - InventoryMovement 保持 1；
  - 未产生任何扣减。

结果：PASS。

**未做**：做饭/食记的真实 Android UI 操作；本轮为 HTTP/DB 集成验证。

### B. 家庭权限 — BLOCKED

尝试：

1. 创建新测试账号 `qa_member_...`（成功）。
2. Owner uilocal01 对 DemoHome 调用 `POST /households/{id}/invitations`。
3. 服务端返回 **500 INTERNAL_ERROR**（DemoHome 缺少永久 invitation 记录）。
4. 未能安全加入成员，未执行计划权限测试。

已清理：

- 删除该临时账号/session/preferences。
- 无 membership 变更。

结论：权限测试 **BLOCKED**，不是 PASS；这是测试环境/邀请数据阻塞，不能据此断定权限业务失败。房主/成员越权、计划删除/减份/锁定未验证。

### C. Pending 写入安全

#### C1 手工/导入冲突 — NOT RUN

未执行两个方向的实际 UNKNOWN 冲突：
- 手工 UNKNOWN → 导入；
- 导入 UNKNOWN → 手工。

代码审查已知：
- `ensureNoPendingCreate` 检查任意 create pending；
- 手工编辑器恢复 pending 并禁用保存；
- 不覆盖原 key/payload。
但没有 DB/UI 证据，不能 PASS。

#### C2 损坏 Pending UI — NOT RUN

六类 codec 自动化测试 PASS；但未在真机/隔离本地 DB 注入损坏记录。
未验证：
- UI 错误提示；
- 无网络请求；
- 原记录保留；
- 无新 key；
- 新创建不能静默覆盖。

#### C3 身份/旧回调

- 跨账号隔离：沿用前轮真机 PASS。
- 同账号家庭切换：**NOT APPLICABLE**（产品无任意切换入口；未构造退出/再加入）。
- 旧异步回调污染：NOT RUN。

### D. UI 兼容

- 360dp ImportReviewDialog：**PASS（基础）**，此前截图 `review/rg2-360.png`。
  - 原始来源、份数/时间、模式、取消/导入按钮可见；
  - “请选择食..”截断已通过将中文改为“选择食材”尝试修复；
  - **修复后未重新真机截图**。
- 平板竖屏：PASS（前轮）。
- 平板横屏：NOT RUN。
- 英文：NOT RUN。
- 字体放大：NOT RUN。
- 深色：NOT RUN。
- 长食材列表：NOT RUN。
- C→TRACKED 名称替换提示：代码已有，**真机未验证**。
- 家庭编辑器/搜索选择器/详情/UNKNOWN：前轮有部分证据，本轮未全矩阵复验。

### 历史兼容

- OurHome 6 道菜谱仍在，未被修改。
- 当前测试库中 8 道 recipes 的 `recipe_only_ingredients` 均为 `[]`；trackStock=false 样本为 0。
- 无真实 `trackStock=false` 历史样本用于本轮 UI 回归。
- 历史普通详情、空 recipe-only 分区、家庭→个人快照的语义由现有服务端测试/前轮证据覆盖；本轮未重新 UI 抽样。
- 结论：**NOT RUN（无历史 false 样本）**，不声称 PASS。

## C. 实际缺陷

### P0

- 无新增已确认 P0 缺陷。
- 已有 fingerprint 完整性缺陷已在 `c935910` 修复并通过 codec 测试。

### P1

- DemoHome 固定邀请缺失导致 invitation API 500：阻塞权限测试；属于测试环境/邀请数据问题，可能影响真实家庭邀请，需跟进。
- 360dp 文案截断：已修改为“选择食材”，未复验。
- C→TRACKED 替换提醒：未真机验证。
- 损坏 pending UI、手工/导入冲突仍未实测，属于关键验证缺口，不是已确认缺陷。

### P2

- 计划 UI 未验证（前轮仅 API）。
- 360dp 紧凑布局仍有视觉风险。

## D. 测试证据摘要

| 项 | 证据 |
| --- | --- |
| A1 正常做饭 | batch 5→4；event 73b5...；movement a58e... COOK |
| A2 重复确认 | same event；events=1；movements=1 |
| A3 不足库存 | shortage 5/4；confirm 400；stock4 unchanged |
| A4 Meal | create 201、update 200 v2、delete 204；stock4/movement1 unchanged |
| 360dp Dialog | `review/rg2-360.png` |
| 权限 | invitation API 500，BLOCKED |
| Pending codec | 236 tests incl. CreateAttemptStoreCodecTest |

## E. 数据清理与恢复

- DemoHome 恢复：0 菜谱、0 inventory batches、0 movements、0 cooking events、1 条原有 plan slot。
- OurHome：6 菜谱、12 批次、8 movements，未变。
- 总 recipes 8 / user_recipes 17 / inventory_batches 12 / inventory_movements 11 / cooking_events 5 / meal_entries 7 / plan_slots 5。
- 删除测试菜谱 `c05ac525...`、批次 `b6e...`、movement、CookingEvent、MealEntry、测试账号、RG3 个人快照。
- 本地 pending_writes=0。
- 家庭成员关系未变。
- Pinyin IME 恢复，ADBKeyboard 卸载；屏幕恢复 1200×1920/320dpi。

## F. 发布决策

1. **目前是否存在可复现的 P0 缺陷？**
   - 无新增已确认 P0。已知 fingerprint P0 已修复。
2. **是否存在无法用已有自动化证据替代的关键安全验证缺口？**
   - 是：Pending 手工/导入冲突、损坏 pending UI、权限。
3. **是否存在阻断核心操作的 P1 缺陷？**
   - 未确认核心操作 P1；但 360dp 文案已改未复验，权限测试 BLOCKED。
4. **若不通过，具体阻塞条件：**
   - Pending 冲突未执行；
   - 损坏 pending UI 未执行；
   - 家庭权限未执行（邀请 API 500）；
   - 做饭/食记 HTTP PASS，但无 UI 抽样；
   - 横屏/英文/字体/深色未执行。
5. **Final QA-2 是否可以开始？**
   - **不能。**
6. **是否适合实施 Phase C1？**
   - **不适合。**
7. **最小必要工作：**
   - 修复/绕过 DemoHome 邀请数据问题，完成 Owner/Member 权限 HTTP+DB；
   - 完成 Pending 两方向冲突；
   - 完成至少一类损坏 pending UI；
   - 复验 360dp + 横屏/英文/字体/深色；
   - 做饭/MealEntry 至少一次设备 UI 抽样。

## G. 最终结论

**NO-GO。**

- 做饭/库存/MealEntry HTTP/DB 已通过，空冰箱导入/计划已有证据；
- 但家庭权限被 invitation 500 阻塞，Pending 冲突/损坏 UI 未执行，UI 矩阵未完成；
- 不能因为这些是 NOT RUN 就宣布 GO，也不能把权限 BLOCKED 当作 PASS。

## H. Phase C1/C2 建议

### C1

前置：
- 家庭范围食材字段/目录；
- 家庭内名称/别名唯一约束；
- 单位不可变；
- 房主/成员权限；
- 迁移和 OpenAPI scope；
- 跨家庭搜索隔离。

最小范围：家庭自定义名称 + 规范单位 + 家庭别名 + 选择/编辑，不含图片。粗估 3–5 人日。

### C2

- 可复用 HOUSEHOLD Media，但需媒体归属、成员移除权限、孤儿清理、大小限制；
- 在 C1 稳定后实施；粗估 2–3 人日。
- 当前不建议开始。

## I. 停止

完成实际执行与报告；不进入 Phase C / Final QA-2。
