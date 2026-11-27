# Phase D0 变更记录：创建请求持久化恢复与菜谱说明展示

日期：2026-11-27（Asia/Shanghai）
基线：`d9a56e0d9e23ab477b3f3228eb3d42c7aec72b87`
范围：仅 D0；未修改服务端、数据库、Flyway、OpenAPI。

## 1. 实现摘要

- 复用 `PendingWriteStore` / `pending_writes` 持久化创建请求，新增最小 `CreateAttemptStore` 适配：
  - operation type；
  - Idempotency-Key；
  - 完整 `CreateRecipeRequest` payload；
  - payload fingerprint；
  - userId / householdId；
  - createdAt；
  - `needsReview` 表示结果不确定。
- 首次发送前先持久化；持久化成功才发送网络请求。
- 进程死亡后恢复为 UNKNOWN，不自动重发。
- UNKNOWN 只能重试原 key + 原 payload；修改表单不会改变旧请求。
- 明确成功/明确未执行后清理 pending；不确定时标记 `needsReview=1`。
- 家庭菜谱详情页增加 RECIPE_ONLY 分区，显示 name/amountText/note。
- 只有 RECIPE_ONLY 的菜谱不再显示空白食材区域。

## 2. 修改文件

**新增**
- `CreateAttemptStore.java`
- `PHASE_D0_CHANGE_RECORD.md`

**修改**
- `CreateAttempt.java`
- `HouseholdRecipeEditorFragment.java`
- `IngredientEditorViewModel.java`（沿用）
- `PendingWriteStore.java`
- `LocalDataDao.java`
- `RecipeCatalogDetailFragment.java`
- `HouseholdRecipes.java`
- `ac_fragment_catalog_detail.xml`
- 中英文 `ac_strings.xml`
- `CreateAttemptTest.java`
- `HouseholdRecipesTest.java`
- `PendingWriteStoreTest.java`

## 3. Pending Create Attempt 持久化方式

复用表 `pending_writes`：

| 字段 | 创建请求用法 |
| --- | --- |
| `key` | Idempotency-Key |
| `userId` | 创建者 |
| `householdId` | 目标家庭 |
| `operationId` | `CREATE_HOUSEHOLD_RECIPE` |
| `canonicalPayload` | `{version, operation, fingerprint, payload}` JSON |
| `createdAt` | 创建时间 |
| `needsReview` | `false`=发送中；`true`=结果不确定 |
| media 字段 | 未使用 |

流程：

1. `beginCreate` 先调用 `PendingWriteStore.record`。
2. 持久化失败：不发送网络请求，显示本地存储错误，attempt 标记 FAILED_NOT_EXECUTED。
3. 持久化成功：调用 `createRecipe(home, key, payload)`。
4. 明确成功：删除 pending。
5. 明确未执行：删除 pending，允许新操作。
6. 结果不确定：`markForReview`，保留原记录。
7. 进程死亡后：任何遗留 `CREATE_HOUSEHOLD_RECIPE` 记录一律恢复为 UNKNOWN。
8. 不自动重发；需要用户点击“重试原请求”。

身份与隔离：

- `listPending(userId)` 按账号隔离。
- `CreateAttempt.matches(userId, householdId)` 再次校验。
- 不同家庭的 pending create 不恢复到当前编辑器。
- 不存储 token/password。

损坏处理：

- 记录无法解析、版本不对、key/user/home 非法时返回 `LOCAL_PENDING_CORRUPT`。
- 编辑器显示“待确认创建记录损坏，暂时不能创建”，保存禁用，不静默新建。
- 持久化列表读取失败也阻止新创建，避免在无可靠恢复能力时假装可恢复。

## 4. 进程死亡恢复的实际步骤

测试菜谱：`D0Probe`（仅 RECIPE_ONLY：糖 一勺）。

1. 通过真实 Android UI 填写 `D0Probe`。
2. 停止 B1 后端。
3. 点击保存。
4. 编辑器进入 UNKNOWN：
   - 显示“上次创建结果不确定…”；
   - 保存禁用；
   - 显示“重试原请求”。
5. 从设备本地数据库确认：
   - 1 行 `CREATE_HOUSEHOLD_RECIPE`；
   - `needsReview=1`；
   - payload 含 D0Probe、糖/一勺、steps、fingerprint。
6. **真正杀死应用进程**：`am force-stop edu.xjtlu.civa`，不是旋转。
7. 重新启动应用并登录。
8. 打开新建家庭菜谱：
   - 未自动创建；
   - 恢复“创建结果不确定”；
   - 保存禁用；
   - “重试原请求”可用。
9. 重启后端。
10. 点击“重试原请求”。
11. 数据库只出现 1 条 `D0Probe`，recipe_only 为糖/一勺。
12. pending_writes 中该记录被删除（本地库计数 0）。

结论：进程死亡恢复、保守 UNKNOWN、原 key/payload 重试、成功后清理均实际通过。

## 5. UNKNOWN 安全重试

- 重试按钮只使用 `CreateAttempt.payload` 和 `CreateAttempt.key`。
- 用户修改当前表单不会修改 attempt payload。
- UNKNOWN 时点击保存不会提交新 payload，只提示结果不确定。
- 无自动重试。
- 无“按名称核对成功”的逻辑；没有可靠的按幂等键查询接口，因此保留该限制。
- 不把“用户放弃”当作服务端未创建。

## 6. 草稿与请求快照隔离

- Pending Create Attempt 存在 `CreateAttempt`/pending_writes，payload 不可变。
- Current Editor Draft 存在 `IngredientEditorState`，用户编辑只改 draft。
- UNKNOWN 时 draft 可继续修改，但不会成为新请求；保存禁用，只能重试原请求。
- 原请求成功后编辑器关闭，当前 draft 不会自动提交；这是已知的用户未提交修改丢失点。
- 原请求成功/失败清理 pending 后，用户可重新打开编辑器再编辑；未实现复杂合并/自动恢复 draft。
- 进程死亡后 pending 能恢复；draft 本身不持久化，因此进程死亡后恢复的是原请求，不是未提交草稿。

## 7. 详情页 RECIPE_ONLY 展示

`RecipeCatalogDetailFragment` + `HouseholdRecipes`：

- TRACKED：逐个显示 `名称 数量 单位`；包含历史 `trackStock=false` 行。
- RECIPE_ONLY：独立“仅说明辅料”分区：
  - `name + amountText`；
  - amountText 空时只显示 name，不伪造“适量”；
  - note 存在时显示 `name amount (note)`。
- `recipeOnlyIngredients=[]`：分区隐藏。
- 只有 RECIPE_ONLY：库存食材标题/容器隐藏，仅说明辅料显示。
- 两类都空：显示“暂无数据”，不产生空白区域。
- 公共/个人菜谱不显示该分区。
- 未修改 InventoryCalculator、库存匹配或做饭扣减。

设备验证：

- 仅 RECIPE_ONLY `D0Probe` 详情：
  - “仅说明辅料”；
  - `糖 一勺`；
  - 无空白“食材”区域。
- 混合 `D0MixedUTF8`（通过 UTF-8 API 创建）详情：
  - “食材”：`鸡蛋 2 个`；
  - “仅说明辅料”：`食盐 适量`、`香菜 (根据个人口味)`。
- 历史普通菜谱（OurHome 原 6 道）未受影响；未逐一打开全部历史菜谱。

## 8. UI 回归

- 平板竖屏 1200×1920：编辑器、UNKNOWN banner、重试按钮、详情分区可用。
- 平板横屏：旋转后行状态和 UNKNOWN banner 保持；详情未逐项横屏走查。
- 360dp：A1 编辑器布局已验证；D0 UNKNOWN banner/搜索 Dialog 未在 360dp 逐项走查。
- 字体放大、深色、英文：未逐项走查。
- 保存中快速重复点击、账号切换、版本冲突：未设备实测（代码有 guard/identity 检查）。
- 进程死亡：已实测，见第 4 节。

测试输入使用临时 ADBKeyboard 测试工具；测试后已恢复 Pinyin IME 并卸载该工具。未加入应用调试后门。

## 9. 测试与构建

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest :core-data:testDebugUnitTest -PdevStackOff` | 通过 |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **220 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures**（本轮无服务端代码改动） |
| `git diff --check` | 无空白错误 |

新增/完善覆盖：

- `PendingWriteStoreTest`：记录、账户隔离、markForReview、本地写入失败报告。
- `CreateAttemptTest`：key/payload 绑定、UNKNOWN 只允许原请求重试、明确失败后新操作、身份隔离、恢复为 UNKNOWN。
- `HouseholdRecipesTest`：recipe-only name/amount/note、空 amount 不补“适量”。
- `IngredientEditorStateTest`：现有 pending/draft 语义不受影响。

## 10. 未执行测试

- 360dp UNKNOWN banner、搜索 Dialog、详情分区逐项检查。
- 字体放大、深色、英文界面。
- 账号切换/登出后的 pending 恢复拒绝（代码有 user/home 校验，未实测）。
- 保存中快速重复点击、更新版本冲突、真实超时。
- pending 记录损坏的真机注入（代码有 guard，未注入）。
- draft 跨进程恢复（当前未持久化 draft）。
- 历史 trackStock=false 菜谱详情的真机显示（代码保留全部 ingredients，未逐条走查）。
- 大量行/长详情滚动性能。

## 11. 剩余风险

- Draft 不持久化；进程死亡恢复的是原请求，不是用户未提交修改。
- UNKNOWN 原请求成功后编辑器关闭，draft 不会自动保存到新菜谱。
- 没有按幂等键查询结果的接口；无法只读确认“是否已创建”，只能重试原请求。
- 损坏 pending 会让新建被阻塞；没有清理/导出入口。
- `CreateAttemptStore` 使用 `PendingWriteStore` 的 `needsReview` 表示 UNKNOWN；未新增表字段/迁移，phase 不是显式列。
- 详情页 recipe-only 分区的 note 使用普通括号，不是资源化本地化格式。
- 未修改库存匹配/做饭逻辑；详情展示不影响算法。

## 12. Phase D1 是否具备条件

**P0 持久化/进程死亡恢复与详情展示已完成，具备进入 D1 的基础；但 D1 前应补齐：**

1. 详情页回归（历史菜谱、legacy false、空 recipe-only）。
2. 360dp/字体/深色/英文检查。
3. draft 与 pending 的产品交互决定（是否保留/恢复未提交修改）。
4. 损坏 pending 的清理/支持入口。
5. 账号切换与 pending 隔离的设备测试。
6. 导入流程复用 D0 详情/编辑器边界，不在 D1 修改库存算法。

## 13. 数据清理

- 已删除 D0Probe、D0Mixed、D0MixedUTF8 及对应 recipe_ingredients / user_recipes snapshots。
- 本地 pending_writes 计数 0。
- OurHome 恢复原 6 道菜谱。
- inventory movements 保持 8；未产生做饭扣减/食记记录。
- 测试设备已恢复 Pinyin IME，ADBKeyboard 已卸载。
