# Batch 2A 修改记录（T-03 / T-04）

日期：2026-11-27　分支：`refactor/core-ui-and-bd-modules`　基线：`17d2742`（Batch 1）
范围：**仅服务端**。无数据库迁移、无 OpenAPI 变更、无 Android 代码变更。

---

## 1. T-03 确认做饭的数据完整性

### 1.1 修改内容

`CookingEventService.confirm` 原先只校验字段非空，然后把客户端提交的 `allocations` 直接扣减。存在四类问题：

| 问题 | 修改前行为 |
| --- | --- |
| `allocations=[]` | 允许 → 写出"做了饭但一滴不扣" |
| 少分配/多分配 | 允许 → 扣减量 ≠ 配方需求 |
| 批次与配料不匹配 | `deduct` 只按 `batch_id` 查，不校验 `ingredient_id`/`unit` → 可用 B 配料批次冲抵 A 配料 |
| 过期批次 | 预览过滤过期，确认**不校验** → 可扣到过期批次 |
| `expectedVersion=null` | 自动拆箱 NPE → 500 |

本次新增/强化的校验（全部在同一幂等事务内，任何失败整体回滚）：

1. **分配完整性**（`requireCompleteAllocations`）：对每个 `track_stock=true` 的配料，要求分配合计 **精确等于** `quantity × servings ÷ yield`（与预览同一 `CEILING(3)` 规则，经 `InventoryMatchingService.requiredFor` 复用 `InventoryRules.required`，不复制算法）。少分、多分、漏项一律 400 `INVALID_RANGE`。
2. **未追踪配料不得扣减**：`track_stock=false` 的配料出现在 allocations 中 → 400 `INVALID_INGREDIENT`。
3. **批次与配料/单位匹配**：`deduct` 现在读取 `ingredient_id`、`unit`，与分配行不一致 → 400 `INVALID_INGREDIENT`。
4. **过期批次**：`expires_on < 家庭时区的今天` → 409 `EXPIRED_BATCH`。边界与预览一致：**到期当天仍可用**。
5. **版本**：批次 `expectedVersion` 不一致 → 409 `VERSION_CONFLICT`（保持原有优先级，避免改变既有测试语义）。
6. **缺版本**：分配行缺 `expectedVersion` → 400 `INVALID_RANGE`（不再 NPE）。
7. **空分配**：仅当配方**没有任何** `track_stock=true` 配料（或全部需求为 0）时允许；否则 400。
8. **零需求行**：`track_stock=true` 但需求为 0 的配料不要求分配，也不允许分配（避免误扣）。
9. **家庭时区**：`lockHousehold` 现在返回 `zone_id`，"今天"按家庭时区计算，与库存/预览口径一致。
10. **原子性**：`cooking_events` 插入在扣减之前，任一批次失败 → 事务回滚 → 事件、movement、幂等占位全部消失；测试断言了数量不变。

错误映射补充：`RecordsErrors` 增加 `EXPIRED_BATCH → 409`（该错误码在 OpenAPI 中**本就声明为 409**，此前从未抛出）。

### 1.2 明确未做的改动（遵守已确认边界）

- 未引入"缺料也允许记录做饭"的新状态（PD-07 明确要求先评估成本、不得自行新增状态）。缺料仍以 400/409 拒绝，用户需先修正库存或调整用量。
- 未阻止创建菜谱、想吃、排餐（这些路径本来就不查库存）。
- 未改动 `INSUFFICIENT_STOCK`、`VERSION_CONFLICT` 的既有语义。

---

## 2. T-04 幂等重放的授权复核

### 2.1 复核结论（与 Phase 3 的假设不同，先如实说明）

Phase 3 记录的风险是"被移除成员可用旧幂等键重放并拿到业务响应"。本次逐行复核后**该结论不成立**：

- `PlanningService.commit` 与 `CookingEventService.confirm` 都在调用 `IdempotencyExecutor` **之前**执行 `requireMember(actor, home)`；
- 幂等键按 `(actor_id, key)` 存储，跨账号无法复用；
- 摘要包含规范化路径（含 `householdId`），跨家庭路径会得到 `IDEMPOTENCY_KEY_REUSED` 409，而不是别人的数据。

因此**"移除成员重放"在当前代码里已被前置检查挡住**，属于纵深防御缺口而不是可利用漏洞。

### 2.2 仍应修改的理由

`authorizeReplay` 为空意味着**重放路径不重新执行任何授权**。Batch 3 会在计划写入的 `work` 内部加入房主删减校验；一旦授权逻辑进入 `work`，重放就会跳过它。现在补上，避免未来引入真实漏洞，并让该不变量可被测试覆盖。

### 2.3 修改内容

| 端点 | 修改 |
| --- | --- |
| `POST /households/{id}/plan-commits` | `authorizeReplay` 由 `ignored -> { }` 改为 `requireMember(actor, home)` |
| `POST /households/{id}/cooking-events` | 同上 |
| `POST /me/meal-entries` | 新增 `requireStoredOwner`：校验存储响应的 `userId` 等于当前 actor；不符 → 404 |

`/me/meal-entries` 不要求家庭资格：个人食记按设计不因退出家庭而失效（`docs/DATA_CONTRACT.md` 与 V2 §3.5 一致）。这里只防止"把别人的存储响应重放给当前账号"。

### 2.4 未改变的行为

- 正常重放仍返回原始成功响应（同一幂等键、同一请求体）。
- 同一键、不同请求体仍是 409 `IDEMPOTENCY_KEY_REUSED`。
- 被移除成员仍返回 **404 NOT_FOUND**（与既有 `requireMember` 一致），未改为 403。契约里声明了 403 `HOUSEHOLD_ACCESS_REVOKED`，但实现一直是 404；这是**既有的契约漂移**，本批不扩大改动面。

---

## 3. 涉及文件

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `server/.../record/CookingEventService.java` | 修改 | 完整性校验、批次匹配、过期、时区、重放授权 |
| `server/.../record/MealEntryService.java` | 修改 | 重放时校验存储响应归属 |
| `server/.../record/RecordsErrors.java` | 修改 | `EXPIRED_BATCH` → 409 |
| `server/.../inventory/InventoryMatchingService.java` | 修改 | 新增 `requiredFor`（复用 `InventoryRules`，单一算法源） |
| `server/.../plan/PlanningService.java` | 修改 | 重放时复核成员资格 |
| `server/.../record/CookingEventHttpIntegrationTest.java` | 修改 | 新增 `unsafeCookingAllocationsAreRejectedWithoutSideEffects`（+141 行） |
| `server/.../record/IdempotentReplayAuthorizationHttpIntegrationTest.java` | 新增 | 4 个重放授权测试 |

未改动：`contracts/openapi.json`、生成代码、Flyway 迁移、Android 模块、Gradle 配置、依赖。

---

## 4. 测试（真实执行）

### 环境

```
JAVA_HOME=D:\jdk-17-microsoft
GRADLE_USER_HOME=D:\XJTLU\CSI301\code\civa\build\gradle-user-home
DB_URL=jdbc:postgresql://localhost:5432/civa_test
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true
```

### 执行的命令与结果

```powershell
cd server
.\gradlew.bat test --tests "edu.xjtlu.civa.server.record.*" --console=plain
# BUILD SUCCESSFUL in 33s

.\gradlew.bat test --console=plain
# BUILD SUCCESSFUL in 58s
# 解析 test-results: suites=37  tests=76  failures=0  errors=0  skipped=0

cd ..
.\gradlew.bat checkContracts --console=plain
# validateContract: "No validation issues detected."
# checkContracts: "contracts consistent"
# BUILD SUCCESSFUL in 5s
```

基线（Batch 1）为 71 个测试；本批 +5（1 个做饭校验 + 4 个重放授权）。

### 用例覆盖

`unsafeCookingAllocationsAreRejectedWithoutSideEffects`：
1. 空 allocations → 400；
2. 只分配一半 → 400；
3. 批次属于另一配料 → 400；
4. 过期批次 → 409；
5. 缺 `expectedVersion` → 400；
6. 上述全部拒绝后：`cooking_events=0`、`inventory_movements=0`、四个批次数量不变；
7. 合法分配 → 201，两批次分别扣到 100 / 8，movement=2；
8. 零需求配料 + 空分配 → 201，不产生 movement。

`IdempotentReplayAuthorizationHttpIntegrationTest`：
1. 做饭正常重放；移除成员后重放 → 404，响应不含事件 id 与菜名，事件/流水数量不变；
2. 计划 commit 正常重放；移除成员后重放 → 404，槽数量不变；
3. 跨家庭：B 用 A 的键访问 A 的家庭 → 404 且不含 A 的菜名；B 在自己家用同一键是**全新操作**（成功），重复同意图 → 200，改意图 → 409 `IDEMPOTENCY_KEY_REUSED`；
4. 个人食记：同意图重放返回同一条；另一账号用同键得到自己的记录（不串号）；篡改存储响应的 `userId` 后重放 → 404。

### 测试自身的两次修正（如实记录）

- 首次运行时 `CookingEventHttpIntegrationTest` 新用例失败：我把"份数 2"误当成 `recipeVersion` 传入，导致命中 `VERSION_CONFLICT`（409）。修正为 `recipeVersion=1` 后通过。这不是产品缺陷。
- 为避免误报，`EXPIRED_BATCH` 用例在修正参数后才真正走到过期分支（否则会被版本检查先拦截）。

---

## 5. API Contract 影响

| 项 | 结论 |
| --- | --- |
| `contracts/openapi.json` | **未修改**（`git status -- contracts` 为空） |
| 生成代码 | `generateServerApi` 为 UP-TO-DATE，无变化 |
| `checkContracts` / `validateContract` | 通过 |
| 新增 409 `EXPIRED_BATCH` | 契约**已声明**该错误码与 409 状态，本批只是首次真正抛出 |
| 新增 400 拒绝 | 状态码 400 已在 `confirmCooking` 声明；`ApiError.code` 为自由字符串，schema 兼容。但契约描述的 400 示例码为 `VALIDATION_ERROR`，实现历来用 `INVALID_RANGE`/`INVALID_INGREDIENT`，属**既有漂移**，未在本批修正 |
| 成功响应结构 | 未变 |

> 若团队要求严格的错误码一致性，应另开一个 IC 把 record 域的 400 码统一为 `VALIDATION_ERROR`，属于独立小任务。

---

## 6. 对 Android 客户端的影响

| 场景 | 影响 |
| --- | --- |
| 正常做饭（详情页 / 食记"计划菜→吃了"） | 无变化：客户端提交的正是 `previewCooking` 返回的完整 allocations |
| 缺料时 | 仍是先被预览拦截；即使直接提交，现在会得到 400（不完整）或 409（不足/过期），不会产生部分扣减 |
| 新错误码 `EXPIRED_BATCH` | 客户端把错误统一显示为通用失败提示（`ac_catalog_error` / `ac_detail_cook_shortage`），不崩溃；`DtoChecks` 对 `code` 无枚举约束 |
| 空 allocations | 只有"无库存追踪配料"的菜谱会发送空列表，仍允许；其他情况客户端本来就不会发空 |
| 重放 | 正常重试仍返回原结果；仅当存储响应不属于当前账号时返回 404（正常流程不会发生） |
| 响应体 | 无字段变化，无需重新生成客户端 |

**未执行**：Android 构建与真机回归（本次无 Android 代码变化，未运行，不声称通过）。

---

## 7. 尚未解决的风险

| 风险 | 等级 | 说明 |
| --- | --- | --- |
| 400 错误码与契约描述不一致 | 低 | `INVALID_RANGE`/`INVALID_INGREDIENT` 未在契约的 400 示例中列出；状态码与 schema 兼容，建议后续 IC 统一 |
| 移除成员返回 404 而非 403 | 低 | 与契约声明的 `HOUSEHOLD_ACCESS_REVOKED` 不一致，但为全仓既有行为，改动会影响多个模块与测试 |
| 家庭时区非法 → 500 | 低 | `ZoneId.of` 无兜底；与 `InventoryService` 现有行为一致（创建家庭不接收自定义时区） |
| 超范围数量 → 500 | 低 | `InventoryRules.required` 越界抛 `IllegalArgumentException`；预览路径同样存在 |
| 重放授权在 Batch 3 前无真实攻击面 | — | 属纵深防御；Batch 3 引入房主校验后才有实际意义，届时需补"角色降级后重放"用例 |
| `requiredFor` 为 `public static` | 极低 | 服务端内部工具方法，不进入 OpenAPI，不影响客户端公共面 |

---

## 8. 下一批

**Batch 2B**（需用户另行授权）：

- **T-07**：客户端稳定幂等键（同一次操作复用、新操作换键）、重复提交防护、"结果待确认"状态；
- **T-06**："在家吃"只记录个人用餐，不再直接扣库存（PD-07 已确认的最小安全方案）。
