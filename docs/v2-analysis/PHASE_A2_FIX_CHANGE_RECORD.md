# Phase A2-Fix 变更记录：创建 E2E 与幂等状态

日期：2026-11-27（Asia/Shanghai）
基线：`a26ca14`
范围：仅 A2-Fix；未修改服务端、数据库、Flyway、OpenAPI。

## 1. 结果摘要

- 三组空白 UI 创建 E2E 已用真实 Android UI 完成并落库（A2 代码；CreateAttempt 重构后又以 A2FixProbe/A2FixProbe3 回归创建路径）：
  - A2TrackedProbe（仅 TRACKED）
  - A2RecipeOnlyProbe（仅 RECIPE_ONLY）
  - A2MixedProbe（混合）
- 混合菜谱完成“只改名”“只改 TRACKED 数量”“删除全部辅料发送 []”验证。
- 幂等请求状态由 `CreateAttempt` 实现：
  - key + payload 固定绑定；
  - 结果不确定只允许重试原请求；
  - 明确失败后才允许新 key/新 payload；
  - 账号/家庭身份绑定；
  - 旋转后状态保留。
- 真实故障测试：
  - 停掉后端 → 保存进入 UNKNOWN；
  - 保存按钮禁用、显示“重试原请求”；
  - 旋转后 UNKNOWN 状态仍保留；
  - 重启后端 → 重试原请求 → 只创建一条 A2FixProbe3。
- Android 单元测试 **217 / 0 failures**；Lint/assemble/checkContracts 通过；服务端 105 项回归通过（服务端无本轮改动）。

## 2. 修改文件

**新增**
- `CreateAttempt.java`
- `CreateAttemptTest.java`
- 本记录

**修改**
- `HouseholdRecipeEditorFragment.java`
- `IngredientEditorViewModel.java`
- 编辑器布局（attempt 状态/重试按钮）
- 中英文 `ac_strings.xml`

A1/A2 的 state、picker、search、recipe-only 字段语义未重写。

## 3. 真实 UI 创建步骤与结果

测试工具：设备仅 Pinyin IME，`adb input text` 不稳定；本轮临时使用 **ADBKeyboard** 作为测试输入法，通过 `ADB_INPUT_TEXT/B64` 向真实 Android 输入控件输入。未向正式编辑器加入调试后门。

### 场景 A：A2TrackedProbe

UI 步骤：
1. 菜谱 → 家庭 → 新建家庭菜谱。
2. 输入名称 `A2TrackedProbe`。
3. 添加库存食材。
4. 点名称字段 → 搜索 `鸡蛋` → 选择 `鸡蛋`。
5. 数量 `3`；单位显示 `个`（COUNT）。
6. 份数 `2`，时间 `15`，步骤 `teststep`。
7. 保存。

结果：
- DB `recipes`：`c154b1cf-6e78-458b-8ed1-66d6010fcd4a`。
- `recipe_ingredients`：`鸡蛋 / 3.000 / COUNT / track_stock=true`。
- yield=2，duration=15，steps=`["teststep"]`。
- 重新打开详情：显示 `鸡蛋 3 个`。
- 重新打开编辑器：名称、数量、单位、步骤、时间一致。
- 已清理。

### 场景 B：A2RecipeOnlyProbe

UI 步骤：
1. 新建菜谱，名称 `A2RecipeOnlyProbe`。
2. 添加两条仅说明辅料：
   - `食盐` / `适量`
   - `胡椒粉` / `少许`
3. 输入步骤，保存。

结果：
- DB `recipes`：`576a12c5-7f88-4e29-875c-310eb0547d09`。
- `recipe_ingredients` 行数 **0**。
- `recipe_only_ingredients`：
  `[{"name":"食盐","amountText":"适量"},{"name":"胡椒粉","amountText":"少许"}]`。
- 重新打开编辑器：两条辅料正确显示。
- `matchRecipes` 返回 `lines=[]`、coverage=1；不产生 tracked 缺料。
- 未产生做饭/库存消耗流水（movement 计数保持 8）。
- **详情页未显示 recipe-only**：详情“食材”区没有这两项；列为 **Phase D 必修缺口**。

### 场景 C：A2MixedProbe

UI 创建：
- 名称 `A2MixedProbe`；
- 鸡蛋 3 COUNT（TRACKED）；
- 食盐 适量、酱油 2 勺（RECIPE_ONLY）。

初始落库：
- 混合菜谱 `e09a3881-26a5-49c9-9721-e1731f0586bd`；
- recipe_ingredients 1 行：`鸡蛋 3.000 COUNT`；
- recipe_only：食盐/适量、酱油/2 勺。

更新验证：
1. 只改名称 `A2MixedProbe2` → DB：name 更新，version 2；两类食材不变。
2. 只改 TRACKED 数量 3→4 → DB：quantity=4；recipe_only 两条不变；version 3。
3. 删除两条 recipe-only → 保存 → DB：`recipe_only_ingredients=[]`；quantity=4 保留；version 4。
4. 重新打开页面：数据与 DB 一致。
- 已清理。

### A2-Fix 创建路径回归

- A2FixProbe（仅 recipe-only）在最终 `CreateAttempt` 代码上正常创建并落库。
- A2FixProbe3 用于故障测试，最终只创建一条。
- 清理后 OurHome 恢复原 6 道菜谱；A2 测试 snapshot 数量 0。

## 4. 幂等状态设计

新增 `CreateAttempt`：

```text
Phase:
  SENDING
  CONFIRMED
  FAILED_NOT_EXECUTED
  UNKNOWN

fields:
  key             (Idempotency-Key)
  payload         (CreateRecipeRequest snapshot)
  fingerprint
  userId
  householdId
```

规则：

| 当前状态 | 允许动作 |
| --- | --- |
| 无 attempt | 生成新 key + payload，发送 |
| SENDING | 禁止重复提交 |
| CONFIRMED | 禁止新提交 |
| FAILED_NOT_EXECUTED | 允许用户修改表单，下一次生成新 key 和新 payload |
| UNKNOWN | 保存禁用；只能“重试原请求”，复用相同 key + 相同 payload |

- 同一 key 永不绑定第二个 payload。
- UNKNOWN 时修改表单不会提交；重试按钮使用保存的原始 payload。
- 明确失败（`VALIDATION_ERROR`、`NOT_FOUND`、`FORBIDDEN`、`HOUSEHOLD_REQUIRED`、`INVALID_INGREDIENT`）才进入 FAILED_NOT_EXECUTED。
- 网络错误、超时、5xx、`INVALID_RESPONSE`、`IDEMPOTENCY_CONFLICT` 等进入 UNKNOWN。
- `userId/householdId` 与 attempt 绑定；身份不一致禁止重试。
- 旋转后 attempt 在 ViewModel；新 Fragment 的 LiveData 观察器恢复 UNKNOWN banner/禁用保存。
- 不自动重放历史创建；不在 UNKNOWN 时自动生成新 key。

### 未使用 PendingWriteStore

`PendingWriteStore` 已存在于 inventory/records 基础设施，但本轮编辑器采用 ViewModel `CreateAttempt` + 服务端现有 `Idempotency-Key`，未接入本地持久化。因此进程被杀后 UNKNOWN attempt 会丢失；见剩余风险。

## 5. 网络与生命周期实测

### 停服 + UNKNOWN + 旋转 + 重试

1. 新建 `A2FixProbe3` 表单。
2. 停止后端 `civa-server`。
3. 点击保存。
4. 结果：`attempt_status=上次创建结果不确定…`，保存按钮 `enabled=false`，出现“重试原请求”。
5. 旋转屏幕（user_rotation=1）再恢复：UNKNOWN banner、重试按钮仍存在，保存仍禁用；名称/辅料行保留。
6. 重启后端。
7. 点击“重试原请求”。
8. DB 仅 1 条 `A2FixProbe3`；recipe_ingredients 0；recipe_only 为糖/一勺。
9. 未出现重复创建。

**未执行**：
- 保存中快速双击（代码有 VM saving guard，但未设备实测）。
- 真实请求超时（只验证连接失败）。
- 账号切换/登出后重试（代码 identity guard，未实测）。
- 版本冲突、更新失败。
- 进程被杀后的 attempt 恢复。
- 旧回调覆盖新页面（LiveData 设计覆盖，未专门注入）。

## 6. UI 设备验证

- Tablet 竖屏 1200×1920：
  - 搜索选择器打开、搜索 `鸡蛋`、选中；
  - 单位“个”；
  - 转换确认；
  - attempt banner/重试按钮；
  - 表单滚动保存。
- 360dp：A1 已验证编辑器布局；A2-Fix 搜索/attempt banner 未在 360dp 逐项走查。
- 平板横屏：旋转后行状态/UNKNOWN banner 保留；保存按钮在滚动区域。
- 字体放大、深色、英文：未逐项走查。
- 长列表：未压测。
- 详情页 recipe-only 缺失：已记录 Phase D 缺口。

## 7. 测试与构建结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest -PdevStackOff` | 通过；feature-ac 115 |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **217 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures**（B1；本轮无服务端代码改动） |
| `git diff --check` | 无空白错误 |

新增 `CreateAttemptTest` 覆盖：
- key + payload 绑定；
- UNKNOWN 只允许原请求重试，不允许新创建；
- 明确失败后允许新操作；
- user/household 身份隔离。

## 8. 未执行测试

- 360dp 下搜索 Dialog、attempt banner、旋转布局逐项检查。
- 深色、英文、字体放大。
- 快速重复点击、真实超时、断网恢复的完整组合。
- 登出/切换账号后的 editor 重试。
- 更新版本冲突。
- 进程死亡恢复 UNKNOWN attempt。
- 大量行滚动回收。
- 详情页 recipe-only 展示（明确 Phase D）。

## 9. 剩余风险

- `CreateAttempt` 仅在 ViewModel；进程被杀会丢失 UNKNOWN 状态。未接入 `PendingWriteStore`。
- `ApiError` 不含 HTTP status；只能按 code 分类，未知 4xx 可能被保守归为 UNKNOWN，需要用户重试或离开。
- UNKNOWN 时没有“核对已有结果”的只读查询接口；只有原请求重试。
- UNKNOWN 时用户修改表单不会提交；重试成功后修改内容丢失（需重新打开编辑器）。
- 没有“放弃并创建新菜谱”的安全入口；离开后服务端可能已创建。
- 详情页不显示 recipe-only，Phase D 必须补齐。
- 搜索选择器无当前高亮/最近使用；长列表未压测。
- 保存期间调用 `SubmitButton.stop(save)` 仍引用旧 View；旋转场景由 LiveData 恢复，未崩溃实测。

## 10. Phase A 是否满足关闭条件

**P0 创建与核心幂等路径已通过；P1 故障/布局矩阵未完整执行，因此建议“条件关闭”。**

- 三组空白 UI 创建：通过。
- A2-Fix 幂等 UNKNOWN/旋转/重试：通过。
- 但 P1 的快速重复点击、真实超时、账号切换、进程死亡、360dp 选择器/深色/英文等仍未执行；不能宣称完整 Phase A 质量门禁通过。

## 11. Phase D 必要前置

1. 详情页显示 recipe-only（本轮明确缺口）。
2. 账号/家庭切换时清理或隔离 editor attempt。
3. UNKNOWN 的安全核对/结束入口（若需要产品决策）。
4. PendingWriteStore 是否用于编辑器创建草稿。
5. 360dp/字体/深色/英文搜索选择器回归。

## 12. 数据清理与恢复

- 已删除 A2TrackedProbe、A2RecipeOnlyProbe、A2MixedProbe2、A2FixProbe、A2FixProbe2、A2FixProbe3 及其 recipe_ingredients 和 user_recipes snapshots。
- OurHome 恢复原 6 道菜谱：Family repro、FamilyTest2、Tomato Egg、TomatoEgg、was、联动测试菜。
- 未产生 cooking_events / inventory 扣减；库存 movements 计数保持 8。
- 测试后服务器已重启为 B1 jar。
