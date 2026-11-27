# Phase D1-Fix 变更记录：双类型导入 E2E、导入恢复与个人快照核查

日期：2026-11-27（Asia/Shanghai）
基线：`b3aae70af69ecac9083ec239fd42068b304bf21c`
范围：仅 D1-Fix；未修改服务端、数据库、Flyway、OpenAPI、库存算法。

## 1. 结果摘要

- 真实 UI 混合导入通过：
  - 鸡蛋 3 COUNT、beef 200 G 默认 TRACKED；
  - 食盐 适量、酱油 2 勺选择 RECIPE_ONLY；
  - DB 同时保存 2 条 tracked + 2 条 recipe-only。
- 真实 UI 仅 RECIPE_ONLY 导入此前已通过；本轮再次核对。
- 真实 UI 导入 UNKNOWN → 停止后端 → `am force-stop` 真正进程死亡 → 重开 → 恢复 source=IMPORT pending → 手动重试 → 只创建一条，pending 清理。
- B1 snapshot 核查：`HouseholdRecipeService.snapshot` 确实把 RECIPE_ONLY 写入个人菜谱自由文本 `ingredients`，`HouseholdRecipeOnlyHttpIntegrationTest` 覆盖；D1 报告中“个人菜谱完全无法保存 RECIPE_ONLY 说明”的表述不准确，已纠正。
- 最小修复：导入 pending 的恢复/检测不再只在家庭菜谱详情触发；公共/个人详情入口也能发现 IMPORT pending 并进入原请求重试。

## 2. 修改文件

**修改**
- `RecipeCatalogDetailFragment.java`
  - 所有详情（公共/个人/家庭）加载后检查 source=IMPORT pending；
  - 导入入口遇到 IMPORT pending 时直接显示“重试原导入”对话框，而不是只给普通提示；
  - 手工 pending 仍显示“先处理待确认创建”的阻塞提示。

**新增**
- `PHASE_D1_FIX_CHANGE_RECORD.md`

未新增 Contract/数据库迁移，未改服务端。

## 3. 仅 TRACKED 导入

**未完成单独的“仅鸡蛋+牛肉”真实 UI 导入。**

已通过混合导入验证 A/B 默认 TRACKED：

- 鸡蛋 3 COUNT → `recipe_ingredients`
- beef 200 G → `recipe_ingredients`
- `trackStock=true`
- 单位来自字典
- recipe-only 不包含这两个 A/B 行

`ImportRequestTest` 覆盖仅 TRACKED 请求；但严格的“仅 TRACKED 来源 UI”仍未单独执行。

## 4. 混合导入实测

来源：临时个人菜谱 `D1FixSource`（测试后删除）。

UI：
- 鸡蛋 3 COUNT：默认 TRACKED
- beef 200 G：默认 TRACKED
- 食盐 适量 / 备注“调味”：默认未选择，改为 RECIPE_ONLY
- 酱油 2 勺：默认未选择，改为 RECIPE_ONLY

保存结果：

- 家庭菜谱 `9e3c769a-2b15-4cef-8e9e-7e9fc572e6f0`
- `recipe_ingredients=2`：
  - `beef 200.000 G trackStock=true`
  - `鸡蛋 3.000 COUNT trackStock=true`
- `recipe_only_ingredients=2`：
  - `食盐 / 适量 / note=调味`
  - `酱油 / 2 勺 / note=null`
- 详情显示：
  - `鸡蛋 3 个`
  - `beef 200 克`
  - “仅说明辅料”：`食盐 适量 (调味)`、`酱油 2 勺`
- 重新打开编辑器：鸡蛋 3、beef 200、食盐/酱油仍在。
- 测试后已删除家庭菜谱和个人 source/snapshots。

**未实测**：
- A/B 主动改为 RECIPE_ONLY。
- C 改为 TRACKED。
- `1.5 kg→1500 G` / `1 L→1000 ML` UI 导入（parser 单测已覆盖）。
- `2 勺` 不被换算：混合导入已实际看到 `2 勺` 原样保存。
- 无单位/无用量 RECIPE_ONLY：此前个人 `aaaa` 导入实际验证 amountText=null。

## 5. 导入 UNKNOWN 进程死亡恢复

实际流程：

1. 从个人菜谱 `D1FixSource` 详情发起导入。
2. 核对 2 TRACKED + 2 RECIPE_ONLY。
3. 停止 B1 后端。
4. 点击导入。
5. 出现“导入结果不确定”，仅“重试原导入”。
6. 本地 pending：
   - key `23dce605-80e3-416d-a21c-deee2218ee1a`
   - operation `CREATE_HOUSEHOLD_RECIPE`
   - `needsReview=1`
   - `source=IMPORT`
   - payload 2 tracked + 2 recipe-only。
7. `am force-stop edu.xjtlu.civa`（真正进程死亡，不是旋转）。
8. 重开 App、登录。
9. 打开个人菜谱详情：
   - `resumeImportPending` 自动发现 pending；
   - 显示“重试原导入”；
   - 不自动重发。
10. 重启后端。
11. 点击重试。
12. pending key 仍为 `23dce605-...`，payload/fingerprint 未变。
13. DB 只新增一条家庭菜谱（重试结果 `e02ca526-...`）；原 UNKNOWN 未产生第二条。
14. 本地 pending 计数变为 0。

**未实测**：
- 其他账号/家庭隔离的真实设备切换。
- 同时存在手工 pending 的设备冲突。
- 损坏 pending 真机注入。

代码仍使用 D0：
- `CreateAttemptStore`；
- `PendingWriteStore`；
- 原 key/payload；
- 账号/home 校验；
- 不自动重放。

## 6. 个人菜谱快照核查

### B1 事实

`HouseholdRecipeService.snapshot` 存在：

- 对 TRACKED：写个人菜谱 `ingredients` 的 name/quantity/unit；
- 对 RECIPE_ONLY：写 name、quantity=amountText、unit=null、note、sourceText=amountText；
- 因此个人菜谱自由文本结构可以表达“食盐：适量”。

现有测试：
- `HouseholdRecipeOnlyHttpIntegrationTest.recipeOnlyIngredientsRoundTripAndSnapshot`
- 断言 snapshot 包含食盐/适量、酱油/2 勺、香菜。

本轮实际家庭混合导入后，个人 snapshot 实际为：

```
鸡蛋 COUNT 3
beef G 200
食盐 quantity=适量 sourceText=适量 note=调味
酱油 quantity=2 勺 sourceText=2 勺
```

结论：

- B1 snapshot 已完整支持 RECIPE_ONLY 说明（使用自由文本结构）。
- D1 报告中的“个人菜谱没有 recipeOnlyIngredients 字段，所以跨模型快照不支持 RECIPE_ONLY”是措辞错误；本轮纠正。
- 不需要为个人菜谱新增 recipeOnlyIngredients Contract 字段或数据库迁移。

## 7. 空库存导入

**未执行真实空库存测试。**

未使用空库存家庭实际导入 TRACKED-only/混合并查看 match 缺料、计划添加、无流水。代码不读取库存批次，但按要求不能以代码审查替代实测；列为未执行。

## 8. 恢复入口优化

最小修复：

- `resumeImportPending()` 现在在公共/个人/家庭详情加载后都会检查当前 user/home 的 `source=IMPORT` pending。
- 打开原个人/公共菜谱详情即可看到“重试原导入”，不必先猜去家庭菜谱详情。
- 导入入口遇到 pending：
  - 若 source=IMPORT → 直接显示原请求重试对话框；
  - 若 source=MANUAL → 显示“有待确认创建”阻塞提示，必须先处理手工 pending。

未开发任务中心/后台服务。

## 9. 测试与构建

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **229 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

## 10. 未执行测试

- 独立仅 TRACKED 来源 UI 导入。
- A/B→RECIPE_ONLY UI。
- C→TRACKED UI。
- 1.5kg/1L UI 换算。
- 空库存家庭实际导入、match 缺料、计划添加、无流水。
- 账号/家庭切换、手工 pending 冲突、损坏 pending 真机。
- 360dp、字体放大、深色、英文、横屏导入核对。
- 导入后完整编辑器/详情重新读取（混合已部分验证）。

## 11. 剩余风险

- 独立仅 TRACKED UI 与 C→TRACKED/A/B→RECIPE_ONLY 未实测。
- 空库存未实测。
- 导入 pending 检测在任何详情页都可能弹窗；对无关浏览可能有打扰，但不会自动重发。
- 个人 snapshot 已由 B1 支持；D1 文档已纠正，不需要新字段。
- 手工 pending 与导入 pending 共用 operation；通过 source/阻塞提示区分，但没有独立清理入口。
- 未改库存算法。

## 12. Phase D 是否满足关闭条件

**主要 P0 已验证，建议条件关闭；完整 D1 验收矩阵仍未全部通过。**

已通过：

- 混合双类型真实 UI 导入；
- 仅 RECIPE_ONLY 真实导入；
- 导入 UNKNOWN 进程死亡恢复与原 key/payload 重试；
- B1 个人快照核查；
- 详情/编辑器双类型展示。

未完成：

- 独立仅 TRACKED UI；
- A/B→RECIPE_ONLY、C→TRACKED UI；
- 空库存；
- 360dp/字体/深色/英文；
- 账号/家庭/损坏 pending 真机。

## 13. Phase C 前置条件

1. 完成独立仅 TRACKED 与 C→TRACKED/A/B→RECIPE_ONLY UI。
2. 完成空库存导入/计划/无流水。
3. 完成 360dp/字体/深色/英文。
4. 账号/家庭/损坏 pending 真机恢复。
5. 若个人菜谱仍需独立 recipeOnly 字段，先走 Contract 变更审批；当前 B1 自由文本 snapshot 已可表达说明。
6. Phase C 自定义食材独立设计，不得复用 RECIPE_ONLY 伪装库存食材。

## 14. 数据清理

- 删除所有 D1FixSource 家庭菜谱（2）与个人 source/snapshots（4）。
- 删除 D1TrackedSource 个人 source（未导入）。
- 本地 pending_writes 计数 0。
- OurHome 恢复原 6 道菜谱；inventory movements 保持 8。
- 未产生做饭扣减/食记。
- 测试设备 Pinyin IME 已恢复，ADBKeyboard 已卸载。
