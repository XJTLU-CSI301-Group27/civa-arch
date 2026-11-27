# Release Gate 1 报告：最终关键场景补测与风险确认

日期：2026-11-27（Asia/Shanghai）
基线：`a7ff1b817f76f32a4964a88a7621cead1ae98ab5`（D1-Fix 验证提交）
业务代码：`32d53f8997ebaf6b16089b827206206e445c5bf3`
范围：Release Gate 1；未进入 Phase C / Final QA。

## 1. 环境与基线

- HEAD：`a7ff1b8`
- 工作区：本轮开始干净；结束时提交本报告与名称替换提示。
- 服务端：Phase B1，`/api/v1/auth/session` 返回 401（运行中）。
- Flyway：`20261127130000` 成功。
- Android：229 tests / 0 failures。
- Server：105 tests / 0 failures。
- 原有家庭：OurHome 6 道菜谱、12 库存批次、8 inventory movements。
- 专用空家庭：DemoHome（uilocal01）0 菜谱、0 库存批次、0 movements，1 条既有 plan slot。

## 2. 实际执行的 E2E

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| 空冰箱：仅 TRACKED 导入 | **PASS** | 家庭 `687f47de...`，2 tracked，recipeOnly=[]；match 仅 2 tracked 缺料；movements=0 |
| 空冰箱：混合导入 | **PASS** | 家庭 `18abc9aa...`，2 tracked + 2 recipeOnly；match 只返回 tracked 缺料；movements=0 |
| 空冰箱：加入计划 | **PASS（API）** | plan slot `f4e8...` 创建成功；movements 仍 0；测试后删除 |
| 独立仅 TRACKED UI 导入 | **PASS** | `196a339d...`，鸡蛋 3 COUNT、beef 200 G、recipeOnly=[] |
| 混合 UI 导入 | **PASS** | `9e3c769a...`，2 tracked + 2 recipeOnly |
| A/B→RECIPE_ONLY UI | **PASS** | `da89f9c2...`，0 tracked，2 recipeOnly（3 COUNT / 200 G 原文） |
| C→TRACKED UI | **PASS（显式映射）** | `9cb207d8...`，显式选择 beef、数量 2、G、trackStock=true，recipeOnly=[] |
| 导入 UNKNOWN → 停服 → force-stop → 恢复 | **PASS** | pending source=IMPORT，key 不变，重试只创建一条，pending 清理 |
| 跨账号 pending 隔离 | **PASS** | A pending `b964...` 在 B 登录时不可见；A 恢复后重试 `7f95...`，pending=0 |
| 损坏 pending | **NOT RUN** | 未注入 payload/key/version 损坏 |
| 360dp ImportReviewDialog | **NOT RUN** | 只检查了 360dp 家庭详情 |
| 横屏/字体/英文/深色/长列表 | **NOT RUN** | 未执行 |

## 3. 空冰箱完整闭环

家庭：DemoHome `d8d967df-edc7-4559-aa2e-4518cf3b30f1`
测试前：recipes=0，batches=0，movements=0，inventory 为空。

### 仅 TRACKED

UI 从个人菜谱 `RG1TrackedSource` 导入：
- 鸡蛋 3 COUNT
- beef 200 G

DB：
- recipe `687f47de-ed5d-4f7f-a91b-5c8307549b9d`
- `recipe_ingredients=2`
- `recipe_only_ingredients=[]`

matchRecipes（1 份）：
- 鸡蛋 required 1.5、available 0、MISSING
- beef required 100、available 0、MISSING
- coverage=0
- 无 RECIPE_ONLY line

### 混合

UI 从 `RG1MixedSource` 导入：
- 鸡蛋 3 COUNT、beef 200 G → TRACKED
- 食盐 适量、酱油 2 勺 → RECIPE_ONLY

DB：
- recipe `18abc9aa-3cb1-43e8-9aeb-6270d51843a6`
- 2 tracked + 2 recipeOnly

matchRecipes：
- 只返回鸡蛋/beef 两条 MISSING；
- 食盐/酱油未出现在库存缺料 lines。

### 计划与流水

- 通过 plan API 将缺料 TRACKED 菜谱加入 2026-11-28 DINNER，成功。
- `inventory_movements` 仍为 0。
- `inventory_batches` 仍为 0。
- 测试后删除 plan slot 和所有 RG1 菜谱/sources。

## 4. Pending 跨身份隔离

实际流程：

1. 账号 A（uilocal01 / DemoHome）导入 `RG1CrossSource`，停服 → UNKNOWN。
2. pending：
   - key `b9642b96-9265-4315-9bf5-8fe39b9e9bf7`
   - userId `db23f805...`
   - householdId `d8d967df...`
   - source=IMPORT
3. force-stop 后登录账号 B（verify_1209 / OurHome）。
4. B 打开公共菜谱详情：没有重试对话框；pending 仍在本地 DB，userId 仍为 A。
5. 重新登录 A，打开个人菜谱详情：出现“重试原导入”。
6. 重试原 key/payload，DB 创建 `7f9560e1-...`，pending=0。

结论：跨账号不可见、原账号可恢复，实测 PASS。

未执行：
- 同一账号切换家庭；
- 手工 pending 与导入 pending 同时存在；
- 损坏 pending 注入。

## 5. 异常 Pending

**NOT RUN。** 未注入：
- payload 缺失；
- fingerprint 不匹配；
- 无效 key；
- operation 不匹配；
- userId/householdId 不匹配；
- 不支持版本。

代码现状（未作为实测通过）：
- `CreateAttemptStore.decode` 对非法/不匹配记录返回 null；
- `recover` 对 CREATE 操作记录损坏返回 `LOCAL_PENDING_CORRUPT`；
- 编辑器 `recoveryBlocked` 禁用新建；
- 不自动删除、不自动换 key。
缺少真机损坏注入验证，因此列为未执行 P0。

## 6. UI 兼容

- 360dp：只检查家庭菜谱详情（食材、做法、营养可滚动）；**未打开 ImportReviewDialog**。
- 平板竖屏 1200×1920：导入 Dialog、类型选择、双类型详情已实际使用。
- 平板横屏：未执行导入核对。
- 系统字体放大、英文界面、深色模式、长列表滚动：未执行。
- 没有发现新的横向溢出/按钮不可达（在已测竖屏范围内）；360dp/英文等仍未证明。

## 7. 食材名称替换提醒

发现 P1 风险：

- 来源 C 行“食盐 2 勺”在 C→TRACKED 时，本轮测试显式选择了 **beef**；
- 原 UI 只显示“原始：食盐 2 勺”与选中 beef，没有明确“名称已替换”提示。

最小修复（本轮代码）：
- 在 `ImportReviewDialog` C→TRACKED 且选中规范名与原始名明显不同时，行内显示：
  “原始‘食盐’将替换为规范食材‘beef’，请确认这是你要的选择。”
- 不禁止选择、不自动 contains 匹配、不修改数据。

**该提示未完成真机 UI 验证**；代码已加入。

## 8. 库存规则回归

实际：
- TRACKED 参与 match，空库存显示 MISSING。
- RECIPE_ONLY 不进入 match lines，不产生缺料。
- 导入/查看/计划未产生 InventoryMovement。
- 计划添加成功，movements=0。

未执行：
- 确认做饭扣库存；
- MealEntry 不扣库存；
- 旧 trackStock=false 的历史语义真机；
- 房主/普通成员权限回归。

未修改 InventoryCalculator 或做饭扣减算法。

## 9. 测试与构建

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **229 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

## 10. 发现的问题与分级

- **P0 NOT RUN**：损坏 pending 六类注入。
- **P0 NOT RUN**：同一账号切换家庭、手工+导入 pending 同时存在。
- **P1 NOT RUN**：360dp 导入 Dialog、英文、字体放大、深色、横屏、长列表。
- **P1 已最小修复但未真机验证**：C→TRACKED 名称替换提示。
- **P1**：空冰箱计划添加通过 API，不是计划 UI 操作。
- **P2**：C→TRACKED 测试把食盐显式映射到 beef；路径通过，但没有验证用户把食盐映射到字典中的食盐。
- **P2**：DemoHome 有 1 条既有 plan slot；无异常。

无发现需要修改服务端/数据库/库存算法的问题。

## 11. 数据清理与恢复

- 删除 RG1TrackedSource、RG1MixedSource、RG1CrossSource 家庭 recipes/ingredients 与个人 sources/snapshots。
- 删除测试 plan slot `f4e8...` 及其 plan_items。
- DemoHome 恢复：recipes=0，batches=0，movements=0，plan_slots=1（原有）。
- OurHome：6 道菜谱，12 批次，8 movements，未变。
- 本地 pending_writes=0。
- 家庭成员关系未变。
- 测试设备 Pinyin IME 恢复，ADBKeyboard 卸载。

## 12. Phase D 是否能够正式关闭

**不能宣称完整关闭。**

已通过：
- 空冰箱 TRACKED/混合导入、match、计划、无流水；
- TRACKED-only、混合、A/B→RECIPE_ONLY、C→TRACKED；
- 导入 UNKNOWN 进程死亡恢复；
- 跨账号 pending 隔离；
- B1 个人快照核查；
- 详情/编辑器双类型展示。

未完成：
- 损坏 pending 真机注入；
- 同一账号切换家庭/手工 pending 冲突；
- 360dp/英文/字体/深色/横屏/长列表；
- 做饭扣库存、MealEntry、legacy false、权限回归；
- 名称替换提示真机确认。

因此 Phase D 为**有条件关闭**，完整 Release Gate 1 未全部 PASS。

## 13. Phase C 是否具备开始条件

**不具备。**

至少需要：
1. 完成损坏 pending 六类注入与隔离测试。
2. 完成 360dp/英文/字体/深色/横屏导入核对。
3. 完成做饭/MealEntry/legacy/权限回归。
4. 完成手工 pending 与导入 pending 冲突。
5. 验证名称替换提示。

Phase C 自定义食材不得复用 RECIPE_ONLY 伪装库存食材。
