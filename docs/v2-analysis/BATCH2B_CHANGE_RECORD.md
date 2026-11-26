# Batch 2B 修改记录（T-07 / T-06）

日期：2026-11-27　分支：`refactor/core-ui-and-bd-modules`　基线：`5a0b531`（Batch 2A）
范围：仅 Android 客户端（`feature-ac`）。无服务端、契约、数据库、Gradle 依赖变更。

---

## 1. T-07 稳定幂等键与重复提交防护

### 1.1 问题（修改前）

| 写入 | 修改前 |
| --- | --- |
| `confirmCooking`（菜谱详情的"做饭"） | 每次点击 `UUID.randomUUID()`；无提交锁；超时后重试 = 新 key = 新业务操作 |
| `confirmCooking`（食记"计划菜→吃了"） | 同上 |
| `createMealEntry`（手工记一餐 / 计划菜记录 / 原"在家吃"） | 每次点击 `UUID.randomUUID()`；超时/旋转后重试会重复创建食记 |

服务端幂等本身是可靠的（Batch 2A 已补强），但客户端从未复用 key，因此"同一次操作"在服务端看来每次都是新的。

### 1.2 设计：四段生命周期与实现映射

| 阶段 | 需求 | 实现 |
| --- | --- | --- |
| **A 请求尚未发送** | 开始新操作时创建新标识 | `IdempotentAction.begin(summary)` 只在 `IDLE` 时生成新 UUID；`IN_FLIGHT`/`UNCONFIRMED` 时返回 false（按钮被禁用，重复点击无效果） |
| **B 请求已发送** | 重试复用原 key；界面重建不产生新操作 | key 与请求体存入 **Activity 作用域的 `RecordSubmissionViewModel`**（旋转、Tab 切换、Fragment 替换都不丢）；`onViewCreated` 时若发现 `IN_FLIGHT`（旧 view 已销毁、回调不可能再到达）则转为 `UNCONFIRMED` 并提示，而不是新建操作 |
| **C 结果不确定** | 超时 ≠ 失败；保留 key 与内容；重试复用原意图 | `IdempotentAction.ambiguous(ApiError)` 只把 `TIMEOUT / NETWORK_ERROR / TEMPORARILY_UNAVAILABLE / INTERNAL_ERROR / INVALID_RESPONSE` 视为不确定；此时 `uncertain()` 保留 key + 请求体，弹出"结果待确认"对话框，[重试] 用**存储的原始请求**重发（不是用户当前输入），[放弃] 才清除 |
| **D 操作结束** | 成功结束；明确失败可改输入重来；新操作新 key | `succeeded()` / `rejected()` 清除 key；下一次 `begin()` 生成新 UUID。明确失败（如 `INSUFFICIENT_STOCK`、`VERSION_CONFLICT`、`VALIDATION_ERROR`）会删除 pending 记录并允许编辑后重新提交 |

### 1.3 持久化与进程死亡

复用现有 `PendingWriteStore`（Room `pending_writes`，此前已用于库存与家庭写入）：

- 发送前 `record(key, userId, householdId, operationId, summary)`，**存储失败则不发送**（与库存流程一致）；
- 成功 → `confirm`；明确拒绝 → `confirm`；不确定 → **保留**；
- 进程死亡后重新进入食记页，`listPending` 发现 `confirmCooking` / `createMealEntry` 行 → 提示"有未确认的记录"，要求用户先核对食记与做饭历史，关闭后才清除。**不自动重放**（此时内存中的原始请求体已丢失，无法安全复用 key）。

### 1.4 明确未做的取舍

- 进程死亡后**不自动重放**：没有可解析的请求体，强行用新 key 重试会违反"未确认前不得创建新操作"。改为提示核对。
- 旋转期间发出的请求若成功，旧回调因 `isAdded()` 为 false 不会更新界面；pending 行也未被删除。重新进入页面会提示"结果待确认"，用户核对到记录后关闭即可。这是刻意选择的保守行为（宁可提示，不静默重发）。
- `createRecipe` / `createPersonalRecipe` 仍使用一次性随机 key（不在本批范围；它们没有"计划→做饭→食记"的链式重试场景）。

---

## 2. T-06 "在家吃"与库存解耦

### 2.1 修改前数据流

```
食记 → 新增 → 选"在家吃（用冰箱食材）"
   → 勾选库存批次 + 填写用量
   → 逐批 POST /inventory-batches/{id}/movements (CONSUME)   ← 直接扣库存，非原子
   → POST /nutrition/estimates（用所选食材估算热量）
   → POST /me/meal-entries
```

问题：`CONSUME` 扣减属于个人用餐操作，绕过 `CookingEvent`；逐批调用无事务，中途失败只扣一半；`after.max(0)` 会把超扣夹成 0；且与 V2「个人食记不得扣库存」直接冲突。

### 2.2 修改后数据流

```
食记 → 新增 → "记一餐"（单一入口）
   → 填写餐次/名称/份量/热量基准/备注（顶部提示：只记录，不扣冰箱）
   → POST /me/meal-entries（稳定幂等键）
```

- **不再调用任何库存写接口**（`DiaryFragment` 现在对 inventory 只有只读的 `listIngredients`，用于缺料名称展示）。
- 删除 `addHomeMeal` / `showHomeMealDialog` / `consumeThenRecord` / `consumeBatch` / `estimateAndRecord` / `recordHomeMeal`。
- 保留：餐次、名称、份量、四种热量基准、备注、编辑、删除、月历、日/月汇总、计划菜快捷记录、从做饭历史预填（`cookingEventId` 仍然关联）。
- 语义澄清：移除"在家吃 / 在外吃"二选一（两者原本都只是写食记）；"确认做饭"仍只在菜谱详情与计划菜"吃了"两处，并在食记新增对话框顶部明确提示"只记录，不扣库存"。

### 2.3 已知的伴随变化

- 移除了"在家吃"的 AI 热量估算（它依赖所选库存批次的食材行）。手动/按份/按克热量记录仍在；"今天"页的个人菜品营养估算不受影响。
- 移除了"在家吃"的一次性勾选批次体验；用户若需要扣库存，应使用菜谱详情的"做饭"或计划菜的"吃了"（这两条路径本来就会写 `COOK` 流水）。

---

## 3. 涉及文件

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `feature-ac/.../record/IdempotentAction.java` | 新增 | 幂等键状态机 + 不确定结果判定 |
| `feature-ac/.../record/CookingIntent.java` | 新增 | 跨页面重建保留的做饭请求 |
| `feature-ac/.../record/RecordSubmissionViewModel.java` | 新增 | Activity 作用域的操作状态与原始请求 |
| `feature-ac/.../record/RecordSubmissions.java` | 新增 | 与 `PendingWriteStore` 的衔接（记录/确认/放弃） |
| `feature-ac/.../diary/DiaryFragment.java` | 修改 | T-06 解耦 + T-07 做饭/食记幂等与恢复 |
| `feature-ac/.../catalog/RecipeCatalogDetailFragment.java` | 修改 | T-07 做饭幂等与恢复 |
| `feature-ac/src/main/res/values/ac_strings.xml` | 修改 | 12 条新文案 |
| `feature-ac/src/main/res/values-en/ac_strings.xml` | 修改 | 对应英文 |
| `feature-ac/src/test/.../record/IdempotentActionTest.java` | 新增 | 8 个用例 |
| `feature-ac/src/test/.../record/RecordSubmissionViewModelTest.java` | 新增 | 3 个用例 |

未改动：服务端任何代码、`contracts/openapi.json`、数据库迁移、`build.gradle`、依赖、主题与布局资源。

---

## 4. 测试（真实执行）

### 4.1 单元测试（新增覆盖）

`IdempotentActionTest`：
1. 超时后重试复用同一 key 与意图；
2. 请求进行中再次点击不改变任何状态；
3. 成功后的新操作使用新 key；
4. 明确拒绝后的新操作使用新 key；
5. 结果未确认时禁止开始新操作；
6. 放弃后清除，下一次使用新 key；
7. `TIMEOUT/NETWORK_ERROR/TEMPORARILY_UNAVAILABLE/INTERNAL_ERROR/INVALID_RESPONSE` 判定为不确定；
8. `VERSION_CONFLICT/INSUFFICIENT_STOCK/EXPIRED_BATCH/VALIDATION_ERROR/NOT_FOUND/IDEMPOTENCY_KEY_REUSED/RATE_LIMITED` 判定为明确失败。

`RecordSubmissionViewModelTest`：
1. 重试期间 key 与请求体可用；
2. `forget` 同时清除 key 与请求体；
3. 做饭与食记两个操作互不影响。

### 4.2 命令与结果

```powershell
cd code\civa-main
.\gradlew.bat :feature-ac:testDebugUnitTest          → BUILD SUCCESSFUL   (feature-ac: 48 tests, 0 failures)
.\gradlew.bat testDebugUnitTest                      → BUILD SUCCESSFUL   (全部 Android 单元测试: 146 tests, 0 failures)
.\gradlew.bat :app:assembleDebug -PdevStackOff       → BUILD SUCCESSFUL   (app-debug.apk 生成)
.\gradlew.bat lintDebug -PdevStackOff                → BUILD SUCCESSFUL   (0 errors；warnings 为既有类别)
.\gradlew.bat checkContracts                         → contracts consistent / No validation issues detected
```

基线（Batch 2A 后）Android 单元测试 135 个；本批新增 11 个，共 146，全部通过。

说明：`-PdevStackOff` 是项目自带的开关，用于跳过 `preDebugBuild` 的本地后端/Docker 检查（本机 Docker CLI 无权限），不影响编译、测试与 lint。**服务端测试未运行**：本批未修改任何服务端代码。

### 4.3 无法自动化的场景（需手工验证）

Android 侧无 Fragment/Robolectric 测试基础设施，以下场景只能手工验证：

1. **快速连点"保存"**：食记新增对话框保存按钮在提交中变为"正在提交…"并禁用 → 只产生一条记录。
2. **超时重试**：断网 10 秒以上（或飞行模式）后保存 → 出现"结果待确认" → 恢复网络点"重试" → 只产生一条记录（服务端日志/数据库只一条）。
3. **请求发出后旋转屏幕**：保存瞬间旋转 → 新界面弹出"结果待确认"；重试使用原请求；核对后只有一条记录。
4. **成功后重新操作**：连续记录两餐 → 两条独立记录（不同 key）。
5. **个人食记不动库存**：冰箱某批次 200 g，记录一餐后仍为 200 g；`inventory_movements` 无新增。
6. **确认做饭仍扣库存**：菜谱详情"做饭" → 库存按预览扣减一次；重复点击不再扣。
7. **编辑/删除食记**：库存数量与流水均不变。
8. **计划菜"吃了"**：库存扣一次 + 食记一条，且关联 `cookingEventId`。
9. **横竖屏 / 返回键**：食记页、菜谱详情页切换后不重复提交、不丢当前提示。

---

## 5. 尚未解决的风险

| 风险 | 等级 | 说明 |
| --- | --- | --- |
| 进程死亡后无法自动重试 | 中 | 原始请求体只存在内存；当前策略是提示用户核对，不静默重发。若后续要求自动恢复，需要把请求体序列化进 `PendingWrite`（工作量中等） |
| 旋转期间成功的请求会留下 pending 行 | 低 | 用户核对后关闭提示即清除；不会造成重复记录 |
| 计划菜"吃了"链路两步 | 低 | 先确认做饭（扣库存）、再写食记；若第二步失败，计划行仍显示未记录，用户再次点"吃了"可能产生第二次烹饪记录。这是既有设计，本批未改；建议后续用 `cookingEventId` 反查或计划项状态解决 |
| 手工记一餐不再提供 AI 热量估算 | 低 | 原"在家吃"专属；手动热量/份量/克数记录保留 |
| `RecordSubmissionViewModel` 为新增公共类型 | 低 | 已通过 `PublicSurfaceCompletenessTest`；未登记公共面（该测试仍通过，说明未触发约束） |
| 未覆盖多账号切换的 pending 行 | 低 | `PendingWriteStore` 按 `userId` 隔离，`listPending(userId)` 只取当前账号 |

---

## 6. 下一批

Batch 3（T-09～T-13，房主与家庭计划权限）需用户单独授权。本批不涉及数据库/契约，因此 Batch 3 的迁移与 IC 将单独评审。
