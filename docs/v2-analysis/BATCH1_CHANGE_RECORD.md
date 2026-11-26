# Batch 1 修改记录（T-01 / T-02）

日期：2026-11-27　分支：`refactor/core-ui-and-bd-modules`　基线：`be12e95`（tag `v2-baseline-20261127`）

---

## 0. 已确认的 V2 决策（本轮用户答复，供后续批次引用）

| ID | 决定 |
| --- | --- |
| PD-11 | 采用 `households.owner_user_id`（不恢复 OWNER 角色）。每家庭有且仅有一名有效房主；创建者默认房主；成员可新增；成员不得删除/减少；房主可增删减；服务端强制；须覆盖批量/替换/生成等间接删减路径。房主退出前必须转让；仅剩房主一人可解散（需明确确认）；不得出现无有效房主的家庭。迁移需保持现有数据完整，房主的旧家庭分配必须确定性或人工确认，不得随机。 |
| PD-01 | 成员可增加已有项份数（1→2 允许，2→1 禁止）；以服务端修改前后实际数据判定。 |
| PD-02/03 | 成员不得锁定/解锁、不得移动日期或餐次；房主可以；成员可在其他日期/餐次新增。 |
| PD-07 | 库存不足不得阻止"建菜谱/加想吃/排计划"；做饭前展示预计消耗与缺料；缺料时不得静默超扣/负库存/伪装为 0；首版要求先修正库存或调整用量。个人食记不得直接扣库存，不得把已记用餐视为已确认做饭。**优先最小安全修复：把"在家吃"改为只记录个人用餐。**"缺料但已做饭"的独立记录作为可选增强，需先评估成本，不得自行新增数据状态。 |
| PD-10 | 路径 A：公共菜谱转换为家庭副本后加入家庭计划；尽量保留名称/食材/用量/单位/份数/步骤；不改公共原件；已转换优先复用；不得重复点击产生大量副本；失败需明确反馈；26 条字典限制需给出兼容方案，不能简单跳过未知食材。 |
| PD-12 | 个人计划本期保留本地草稿；与家庭计划逻辑隔离；不得意外修改家庭数据；切账号不暴露他人数据；后续可扩展到服务端而不重写业务。 |
| 范围 | 目标 M，最低基线 S，不执行 L；先 P0，再库存可做优先、公共菜谱进家庭计划、菜谱数据保真；生成功能有余力再做；必须保留回归时间；M 影响稳定则回退到已验证的 S。 |

> 依据这些决定，T-06（食记解耦）与 T-09～T-12（房主权限）已解锁，可进入 Batch 2/3。

---

## 1. 已完成的任务

### T-01 家庭菜谱更新事务化（对应差距 G-06）

**问题**：`HouseholdRecipeService.update` 依次执行 `UPDATE recipes` → `DELETE recipe_ingredients` → 逐条 `INSERT`，全程无事务。任何一步失败（重复 `ingredientId`、未知 `/` 不存在食材导致 FK 失败等）都会留下"表头已改、食材已清空"的坏数据，且 `version` 已递增。

**修改**：用已有的 `TransactionTemplate` 包裹整个替换过程；`updated == 0` 的乐观锁判断与随后读取保留在同一事务内。

```java
return transactions.execute(status -> {
    int updated = jdbc.update("UPDATE recipes SET ...");
    if (updated == 0) throw new PersonalRecipeFault(exists(home, id) ? "VERSION_CONFLICT" : "NOT_FOUND");
    jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id=?", id);
    insertIngredients(id, body.getIngredients());
    return get(actor, home, id);
});
```

**行为变化**：失败时整体回滚——名称不变、旧食材仍在、`version` 不递增。

### T-02 首插并发冲突返回 409（对应差距 G-10）

**问题**：两个成员同时向同一空槽写入时，双方都读到"空"，随后 `INSERT INTO plan_slots` 触发 `(household_id, date, meal_type)` 唯一键冲突。Spring 将异常翻译为 `DuplicateKeyException`，但 `PlansErrors` 只映射 `PlanFault`，最终落到 `DataAccessException → 500`。客户端无法区分"并发冲突"与"服务故障"。

**修改**：抽出 `insertSlot(...)`，把该唯一键冲突翻译为 `PlanFault("VERSION_CONFLICT")`（`PlansErrors` 映射为 409）。三个首插入口统一使用：`replace`、`applyWrite`（commit 批量）、`lock`。

```java
private void insertSlot(UUID id, UUID home, LocalDate date, PlanMealType meal, boolean locked, UUID actor) {
    try {
        jdbc.update("INSERT INTO plan_slots(...) VALUES (?,?,?,?,?,?)", id, home, date, meal.getValue(), locked, actor);
    } catch (DuplicateKeyException concurrentCreation) {
        throw new PlanFault("VERSION_CONFLICT");
    }
}
```

**为什么映射范围是安全的**：该 INSERT 只可能违反 `plan_slots` 的两个唯一约束——业务键 `(household_id, date, meal_type)` 与 `(id, household_id)`（UUID 主键碰撞可忽略）。`plan_items` 的重复由 `validate()` 的去重集合提前拦截，不经过此路径。

---

## 2. 涉及文件

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `server/src/main/java/edu/xjtlu/civa/server/plan/PlanningService.java` | 修改 | +`DuplicateKeyException` 导入；新增 `insertSlot`；3 处首插改为调用它 |
| `server/src/main/java/edu/xjtlu/civa/server/recipe/HouseholdRecipeService.java` | 修改 | `update` 包入 `transactions.execute` |
| `server/src/test/java/edu/xjtlu/civa/server/plan/PlanHttpIntegrationTest.java` | 修改 | 新增 `concurrentFirstWriteOnTheSameSlotReportsConflict`（+105 行） |
| `server/src/test/java/edu/xjtlu/civa/server/recipe/HouseholdRecipeHttpIntegrationTest.java` | 修改 | 新增 `rejectedIngredientReplacementKeepsTheStoredRecipe`（+73 行） |

未改动：任何迁移、`contracts/openapi.json`、生成代码、Android 模块、`build.gradle`、依赖。

---

## 3. 测试（真实执行）

### 环境

```
JAVA_HOME=D:\jdk-17-microsoft
GRADLE_USER_HOME=D:\XJTLU\CSI301\code\civa\build\gradle-user-home
DB_URL=jdbc:postgresql://localhost:5432/civa_test
JAVA_TOOL_OPTIONS=-Djdk.attach.allowAttachSelf=true
```

> `JAVA_TOOL_OPTIONS` 是本机沙箱的环境要求：Mockito inline mock maker 需要自附加 JVM，沙箱禁止命名管道时会在 Spring Boot 的 `ResetMocksTestExecutionListener` 阶段抛 `MockitoInitializationException`。CI（Ubuntu）不需要该参数，与代码无关。

### 先写测试、确认在未修复代码上失败（TDD 证据）

```powershell
cd server
.\gradlew.bat test --tests "edu.xjtlu.civa.server.recipe.HouseholdRecipeHttpIntegrationTest.rejectedIngredientReplacementKeepsTheStoredRecipe" --tests "edu.xjtlu.civa.server.plan.PlanHttpIntegrationTest.concurrentFirstWriteOnTheSameSlotReportsConflict"
```

结果：**BUILD FAILED，2 tests completed, 2 failed**

```
PlanHttpIntegrationTest > concurrentFirstWriteOnTheSameSlotReportsConflict() FAILED
  a concurrent first write must be reported as a conflict, not a server error ==> expected: <409> but was: <500>

HouseholdRecipeHttpIntegrationTest > rejectedIngredientReplacementKeepsTheStoredRecipe() FAILED
  JSON path "$.name" expected:<原名称> but was:<改名不应保留>
```

第二条同时证明：被拒绝的更新已经改了名称（即 `UPDATE` 已生效、食材已被删除），确属非事务缺陷。

### 修复后

```powershell
.\gradlew.bat test --tests "...rejectedIngredientReplacementKeepsTheStoredRecipe" --tests "...concurrentFirstWriteOnTheSameSlotReportsConflict"
# BUILD SUCCESSFUL in 27s

.\gradlew.bat test --console=plain
# BUILD SUCCESSFUL in 51s
```

全套结果（解析 `build/test-results/test/*.xml`）：

```
suites=36  tests=71  failures=0  errors=0  skipped=0
```

基线为 69 个测试，新增 2 个，其余全部通过。

### 并发稳定性

```powershell
1..3 | ForEach-Object { .\gradlew.bat cleanTest test --tests "edu.xjtlu.civa.server.plan.PlanHttpIntegrationTest" }
```

结果：run 1 / 2 / 3 均 `BUILD SUCCESSFUL`（25s / 17s / 17s）。

测试设计上不依赖具体交错顺序：若请求在持有者提交后才读取，会走版本不匹配分支；若先读取并阻塞在唯一索引上，则走 `DuplicateKeyException` 分支——两条路径的期望结果都是 409，因此不会因调度差异误报。

### 未执行的检查及原因

| 检查 | 原因 |
| --- | --- |
| `:app:assembleDebug` / `testDebugUnitTest` / `lint` | 本次只改服务端，无 Android 文件变化 |
| `checkContracts` / `validateContract` | 未改 `contracts/openapi.json`，生成代码无变化 |
| Android / 平板手工回归 | 无客户端行为变化 |

---

## 4. Git diff 摘要

```
 .../xjtlu/civa/server/plan/PlanningService.java    |  28 ++++--
 .../civa/server/recipe/HouseholdRecipeService.java |  23 +++--
 .../civa/server/plan/PlanHttpIntegrationTest.java  | 105 ++++++++++++++++++++
 .../recipe/HouseholdRecipeHttpIntegrationTest.java |  73 ++++++++++++++
 4 files changed, 211 insertions(+), 18 deletions(-)
```

生产代码净变化：+14 / −14 行（两个方法结构调整），无新增类、无签名变化、无契约变化。

回滚方式：`git checkout v2-baseline-20261127 -- server/src/main/java/...`（或 `git reset --hard v2-baseline-20261127`，注意会丢弃后续批次）。

---

## 5. 剩余风险

| 风险 | 等级 | 说明与建议 |
| --- | --- | --- |
| 失败原因仍是 500 | 中 | 事务化只保证"数据不坏"，重复/未知食材仍返回 500（`RecipeWriteErrors` 未映射 `DataIntegrityViolationException`）。建议后续把 `DuplicateKeyException`/FK 失败映射为 400 `INVALID_RECIPE`，属独立小任务。 |
| `insertSlot` 的异常映射偏宽 | 低 | 仅该 INSERT 的两条唯一约束可达；已在上文说明。若未来 `plan_slots` 增加其它唯一约束，需收窄判断。 |
| `lock` 首插路径无专项测试 | 低 | 复用了同一 helper；现有测试覆盖"已有槽锁定/解锁"。可在权限批次补一条。 |
| 事务持有时间略有增加 | 低 | `update` 现在把 3 条语句 + 1 次读放在一个事务内；锁顺序为 `recipes` 行 → `recipe_ingredients` 子行，与 `create` 一致，无新增死锁面。 |
| 并发测试的时间窗（700ms） | 低 | 在极慢机器上可能退化为"晚读"路径，但期望结果仍为 409；已连跑 3 轮稳定。 |
| `transactions.execute` 的 `@Nullable` 返回 | 极低 | lambda 必返回非空 `Recipe`；若未来提前返回需注意。 |

**未解决（明确不在本批范围）**：`update` 无 `expectedVersion` 时的 NPE/500 防御；`snapshot` 双写策略（家庭菜谱创建时给作者写个人副本）未变。

---

## 6. 下一批推荐

Batch 2（T-03 / T-04 / T-07，可加 T-06）：

1. **T-03**：`confirmCooking` 校验分配完整性（覆盖 `trackStock=true` 需求）、校验批次配料/单位、拒绝过期批次、`expectedVersion` 缺失返回 400。
2. **T-04**：`plan-commits` / `cooking-events` / `meal-entries` 的幂等重放重新鉴权。
3. **T-07**：客户端做饭/食记使用稳定幂等键（`SavedStateHandle` + 提交锁 + "结果待确认"）。
4. **T-06**：食记"在家吃"改为只记录用餐、不扣库存（PD-07 已确认）。
