# Phase D1 变更记录：公共/个人菜谱导入 TRACKED/RECIPE_ONLY

日期：2026-11-27（Asia/Shanghai）
基线：`28d47e8aaa91fb765cf2a273bf7fb83ea011d332`
范围：仅 D1；未修改服务端、数据库、Flyway、OpenAPI、库存计算器。

## 1. 结果摘要

- 导入核对支持每行明确选择：
  - TRACKED；
  - RECIPE_ONLY；
  - C 类未选择时不允许提交。
- A/B 默认 TRACKED，可改为 RECIPE_ONLY；改为 RECIPE_ONLY 时保留原始名称/数量/单位/备注。
- C 默认未选择；必须选择 TRACKED 或 RECIPE_ONLY。
- 两类同时进入同一个 `CreateRecipeRequest`。
- 导入创建复用了 D0 的 `CreateAttempt` / `CreateAttemptStore` / `PendingWriteStore`：
  - 发送前持久化；
  - stable Idempotency-Key；
  - UNKNOWN 只允许原 key/payload 重试；
  - 不自动重放；
  - 账号/家庭身份绑定。
- 真实个人菜谱导入实测：仅 RECIPE_ONLY 导入成功，DB `recipe_ingredients=0`、3 条 `recipe_only_ingredients`。
- 仅 TRACKED / 混合导入的 UI 流程未完成实测；由 `ImportRequestTest`、`ImportRowStateTest` 覆盖请求构造和行处置。

## 2. 修改文件

**新增**
- `ImportRowState.java`
- `ImportRequest.java`
- `ImportRowStateTest.java`
- `ImportRequestTest.java`
- `PHASE_D1_CHANGE_RECORD.md`

**修改**
- `ImportReviewDialog.java`（A/B/C 两类处置、预览、校验）
- `RecipeCatalogDetailFragment.java`（导入请求构造、pending 检查、持久化/重试）
- `CreateAttempt.java`（Source: MANUAL/IMPORT）
- `CreateAttemptStore.java`（source 持久化、按来源恢复）
- 中英文 `ac_strings.xml`
- `CreateAttemptTest.java`（source/recovery）

`IngredientImportParser` 的 Batch 4B 严格解析未改。

## 3. A/B/C 处置映射

| 类型 | 默认 | 用户操作 | 最终数据 |
| --- | --- | --- | --- |
| A/B 完整解析 | TRACKED | 可改 RECIPE_ONLY | TRACKED: line 的规范 id/quantity/unit；RECIPE_ONLY: 原始 name/quantity/unit/note |
| C 未匹配/数量缺失/单位不匹配/近似 | 无选择 | 必须选择 TRACKED 或 RECIPE_ONLY | TRACKED: 选择字典 + 正数数量；RECIPE_ONLY: 自由 name/amountText/note |
| C→TRACKED | 无 | 选择字典、填数量 | 严格校验，`trackStock=true` |
| C→RECIPE_ONLY | 无 | 编辑自由文本 | 不要求 ingredientId/数值 |

### RECIPE_ONLY 文本映射

- `name`：优先 `source.name`，为空时用解析出的 `ingredientName`。
- `amountText`：
  - 结构化 `quantityPrefix + quantity + unit`，按空格拼接；
  - 不把 `sourceText` 无差别拼入；
  - 没有结构化用量时为 null，不伪造“适量”。
- `note`：`source.note`，空则为 null。
- 字段限制：name ≤120 code point，amountText ≤200，note ≤500。
- UI 显示“最终保存：…”，用户可查看最终文本；来源行保留展示。
- 重复 TRACKED ingredientId 阻止保存。

## 4. 请求构造

`ImportRequest.build`：

- 校验 name/steps/yield/duration；
- 两类都空 → null；
- 只有 TRACKED / 只有 RECIPE_ONLY / 混合 → 允许；
- TRACKED 校验 ingredientId、unit、正数数量、重复 ID；
- RECIPE_ONLY 校验 name/amount/note 长度；
- 返回一个 `CreateRecipeRequest`，两类列表同时设置；
- 标签使用家庭菜谱标签。

没有修改 `InventoryCalculator`、库存匹配或做饭扣减。

## 5. 导入幂等与恢复

- 导入使用 `CreateAttempt.Source.IMPORT`。
- 与手工创建共用：
  - `CreateAttemptStore`；
  - `PendingWriteStore`；
  - `CREATE_HOUSEHOLD_RECIPE` operation；
  - 同一 key/payload 绑定；
  - `needsReview` UNKNOWN 标记。
- 首次导入：
  1. `createStore.record` 持久化完整 payload/fingerprint/user/home/source；
  2. 成功后发送 `createRecipe`；
  3. 成功清理 pending；
  4. 明确未执行清理 pending；
  5. 不确定 `markUnknown`，保留原请求。
- 发送前先检查当前 user/home 是否存在任意 create pending；存在则提示并阻止新导入，避免覆盖手工/导入 pending。
- UNKNOWN 只显示“重试原导入”，使用 `attempt.key` + `attempt.payload`，不修改 payload。
- 进程死亡后：
  - `resumeImportPending` 在家庭菜谱详情恢复 `IMPORT` pending；
  - 不自动重放；
  - 显示重试对话框。
- 账号/home 不匹配时禁止重试。
- 损坏 pending 通过 `LOCAL_PENDING_CORRUPT` 阻止导入；没有删除或假装未创建。

## 6. 真实 UI 验证

### 个人菜谱 → 仅 RECIPE_ONLY

使用个人菜谱 `aaaa`（3 条自由文本、无单位/数量）：
1. 个人菜谱详情 → 加入家庭。
2. 导入核对显示 3 行，默认“请选择处理方式”。
3. 每行选择 RECIPE_ONLY；显示“最终保存：…”。
4. 填写份数 2、时间 20（源菜谱缺省），点击导入。
5. 服务端创建家庭菜谱 `8c78c881-c8d3-4752-aadf-546b9eed7ee9`：
   - `recipe_ingredients=0`；
   - `recipe_only_ingredients` 3 条；
   - 无 ingredientId、无数值数量。
6. 已清理测试菜谱。

**未完成**：从个人/公共菜谱实际导入 TRACKED-only、A/B→RECIPE_ONLY、混合 A/B/C；由单元测试覆盖行模型和请求构造。

### 空库存

未实际清空家庭库存执行导入。当前代码不依赖库存批次：
- TRACKED 需要字典，不需要冰箱库存；
- RECIPE_ONLY 完全不需要库存/字典；
- 未改库存匹配。

## 7. 详情/历史兼容

沿用 D0 详情页：
- 仅 RECIPE_ONLY：只显示“仅说明辅料”；
- 混合：TRACKED + RECIPE_ONLY 分区；
- `recipeOnlyIngredients=[]`：不显示空白分区；
- legacy `trackStock=false`：仍在 TRACKED 展示；
- 公共/个人详情不显示家庭辅料分区；
- 未修改库存匹配/做饭扣减。

本轮实际验证：
- 个人 `aaaa` 导入后的家庭菜谱详情显示 3 条 RECIPE_ONLY（无空白“食材”）。
- 详情/编辑器组件由 D0 代码复用；混合详情未在本轮重新 UI 验证。

## 8. 测试与构建

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest -PdevStackOff` | 通过；feature-ac 126 |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **229 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

新增测试：
- `ImportRowStateTest`：A/B 默认 TRACKED、A/B→RECIPE_ONLY 原文、C 默认无选择、C→TRACKED 严格、C→RECIPE_ONLY、original amount、note、长度、重复。
- `ImportRequestTest`：TRACKED-only、RECIPE_ONLY-only、混合、空、非法数量、重复、空 amount。
- `CreateAttemptTest`：source 恢复/UNKNOWN。
- `PendingWriteStoreTest`：D0 持久化/标记/失败。

## 9. 未执行测试

- 真实 UI 的 TRACKED-only、A/B→RECIPE_ONLY、混合导入。
- 公共菜谱导入实际 UI（仅个人菜谱实测）。
- 空冰箱导入实测。
- 导入 UNKNOWN 的真实停服/进程死亡恢复实测。
- 导入 pending 与手工 pending 同时存在时的 UI 冲突场景。
- 损坏 pending 真机注入。
- 360dp/字体放大/深色/英文导入核对 UI。
- 导入后完整重新读取编辑器/详情（仅 DB/详情部分）。
- 库存回归真实做饭（未改算法）。

## 10. 剩余风险

- Import UNKNOWN 恢复只在家庭菜谱详情触发；若用户停留在公共/个人详情，需先进入家庭详情。
- 导入 pending 与手工 pending 共用 operation；通过“存在任意 pending 即阻止导入”避免覆盖，但没有独立来源选择界面。
- 明确失败后导入核对内容不自动恢复；用户需重新发起导入。
- 未实测导入 pending 的进程死亡恢复；代码使用 D0 同一存储，但仍应补测。
- 未实测 TRACKED/mixed UI，仅单元覆盖。
- 空 amountText 的 RECIPE_ONLY 正常；不伪造；详情 note 使用普通括号。
- 旧 `CreateAttempt`/pending source 缺省为 MANUAL；兼容已有 D0 行。
- 未修改库存算法。

## 11. Phase D 是否满足关闭条件

**条件满足/建议条件关闭**：

- 导入核对支持两类处置；
- 仅 RECIPE_ONLY 真实导入落库；
- 请求安全复用 D0；
- 详情兼容；
- 但 TRACKED-only/混合真实 UI、导入 UNKNOWN 进程死亡、360dp/字体/深色/英文仍未执行，不能宣称完整 D1 矩阵通过。

## 12. Phase C 前置条件与建议范围

1. 完成 D1 的 TRACKED/mixed/空库存真实 UI。
2. 完成导入 UNKNOWN + 进程死亡恢复实测。
3. 明确 pending 来源（MANUAL/IMPORT）在 UI 的提示与恢复入口。
4. 360dp/字体/深色/英文导入核对回归。
5. 详情历史/legacy/空 recipe-only 回归。
6. Phase C 自定义食材需独立设计，不要复用 `RECIPE_ONLY` 伪装库存食材。

## 13. 数据清理

- 已删除本轮导入创建的家庭菜谱 `8c78c881-c8d3-4752-aadf-546b9eed7ee9`。
- 未创建 D1ImportSource（API 400 失败，无数据）。
- 本地 pending_writes 计数 0。
- OurHome 恢复原 6 道菜谱。
- inventory movements 保持 8；未产生扣减/食记。
- 测试设备 ADBKeyboard 仍需在最终验证后恢复 Pinyin 并卸载。
