# Final QA-1 报告：安全边界、设备兼容与业务规则补测

日期：2026-11-27（Asia/Shanghai）
基线：`7f6c1f04a6710d876f3efaf5213007c0fde60280`
范围：Final QA-1；未进入 Phase C / Final QA-2。

## 1. 基线与结果摘要

- 开始 HEAD：`7f6c1f0`；工作区干净。
- 服务端 B1；Flyway `20261127130000`。
- 初始数据：OurHome 6 菜谱 / 12 库存批次 / 8 movements；DemoHome 0 菜谱 / 0 批次 / 0 movements / 1 既有 plan slot；总 recipes 8，user_recipes 17，plan_slots 5。
- Android 全量：**236 tests / 0 failures**（新增 7 个损坏 pending codec 测试）。
- Server：**105 tests / 0 failures**。
- lint/assemble/checkContracts：通过。
- 发现并修复 P0：pending fingerprint 未校验。
- 发现并修复 P1：C→TRACKED 名称替换无提示。
- 仍有多项 NOT RUN；Final QA-1 **未完全通过**。

## 2. 每项测试 PASS/FAIL/NOT RUN

| 项 | 结果 |
| --- | --- |
| 损坏 pending：payload 缺失 | **PASS（自动化）** |
| 损坏 pending：fingerprint 不匹配 | **PASS（自动化 + 代码修复）** |
| 损坏 pending：无效 key | **PASS（自动化）** |
| 损坏 pending：operation 不匹配 | **PASS（自动化）** |
| 损坏 pending：userId/householdId 不匹配 | **PASS（自动化）** |
| 损坏 pending：不支持版本 | **PASS（自动化）** |
| 损坏 pending：真机注入/UI 错误提示 | **NOT RUN** |
| 跨账号 pending 隔离 | **PASS（真机）** |
| 同账号家庭切换 | **NOT RUN**（无产品切换入口，未构造） |
| 手工 pending 与导入 pending 冲突 | **NOT RUN** |
| 旧异步回调污染 | **NOT RUN** |
| 空冰箱 TRACKED/混合导入、match、计划、无流水 | **PASS** |
| 空冰箱详情/编辑器重开 | **NOT RUN** |
| 360dp ImportReviewDialog | **PASS（基础可见/可操作）** |
| 平板横屏导入 | **NOT RUN** |
| 英文字体放大/深色/长列表 | **NOT RUN** |
| C→TRACKED 名称替换提示 | **代码已修复；真机未验证** |
| 做饭扣库存/重复确认/不足库存 | **NOT RUN**（服务端测试覆盖） |
| MealEntry 不扣库存 | **NOT RUN**（服务端测试覆盖） |
| 家庭权限（成员/房主） | **NOT RUN**（服务端测试覆盖） |
| 旧 trackStock=false 历史语义 | **NOT RUN** |

## 3. 损坏 Pending 六类结果

新增 `CreateAttemptStoreCodecTest`，直接覆盖 `CreateAttemptStore.decode`：

1. **payload 缺失** → null，不发送。
2. **fingerprint 不匹配** → 代码新增 `Gson(payload) == fingerprint` 校验，返回 null；这是本轮 P0 修复。
3. **无效 key** → UUID 解析失败，null。
4. **operation 不匹配** → 外层 `operationId` 不匹配时忽略；内层 `operation` 不匹配时 null。
5. **userId/householdId 不匹配/非法** → null。
6. **version != 1** → null。

安全行为（代码路径）：

- 损坏记录使 `recover` 返回 `LOCAL_PENDING_CORRUPT`；
- 编辑器设置 `recoveryBlocked`，保存禁用；
- 导入入口显示本地记录不可用，不发送；
- 不自动删除记录；
- 不自动换新 key；
- 不会用损坏记录覆盖已有 pending。

**未完成**：真机注入以上六类并核对 UI 提示；真机证据不足，因此不能声称 UI 行为 PASS。

## 4. 跨账号、家庭与 pending 冲突

### 已实测 PASS：跨账号隔离

- A（uilocal01/DemoHome）导入 `RG1CrossSource`，停服进入 UNKNOWN：
  - key `b9642b96-...`
  - userId `db23f805...`
  - householdId `d8d967df...`
  - source=IMPORT
- force-stop 后登录 B（verify_1209/OurHome）：
  - 打开公共菜谱详情没有重试对话框；
  - 本地 pending 仍存在，userId 仍为 A。
- 重登 A：
  - 打开个人菜谱详情出现“重试原导入”；
  - 使用原 key/payload 重试，创建 `7f9560e1-...`，pending=0。

### NOT RUN

- 同账号切换家庭：当前产品没有任意家庭切换入口；未构造成员退出/重新加入路径。
- 手工 pending 与导入 pending 同时存在：未实测是否互相阻塞/覆盖。
- 旧网络回调污染当前账号/家庭：未注入。

## 5. 空冰箱完整闭环

DemoHome 测试前：0 菜谱、0 批次、0 movements。

### TRACKED

UI 从 `RG1TrackedSource` 导入：
- recipe `687f47de...`
- 鸡蛋 3 COUNT、beef 200 G
- recipeOnly=[]

match：
- 只有两个 tracked line，均 MISSING；
- movements 0。

### 混合

UI 从 `RG1MixedSource` 导入：
- recipe `18abc9aa...`
- 2 tracked + 2 recipeOnly
- match 只返回 tracked 缺料；RECIPE_ONLY 不出现
- movements 0。

### 计划

- 通过 plan API 把缺料 TRACKED 菜谱加入 `2026-11-28 DINNER`；
- plan 创建成功；
- movements 仍 0；
- 测试后删除 plan slot 和 RG1 数据。

**未完成**：空冰箱详情/编辑器重新打开；计划 UI 添加（本轮用 API）。

## 6. 360dp 与设备兼容

- 360dp 1200×1920 物理屏、360dp 逻辑宽：
  - ImportReviewDialog 实际打开；
  - 原始来源、模式选择、数量、最终文本/错误说明、取消/导入按钮可见；
  - “请选择食材”在窄屏被截断为“请选择食..”，但可点击；
  - 底部按钮可见。
- 平板竖屏：导入、详情、编辑器实际可用。
- 平板横屏：NOT RUN。
- 英文界面、字体放大、深色模式、长食材列表：NOT RUN。
- 旋转后不重复创建：D0/D1 已覆盖部分；本轮未在导入 Dialog 中重新执行。

## 7. C→TRACKED 名称替换提醒

场景：来源“食盐 2 勺”，用户显式选择 beef。

代码修复：

- C 行选择 TRACKED 后，如果来源名与选中规范名明显不同，行内显示：
  “原始‘食盐’将替换为规范食材‘beef’，请确认这是你要的选择。”
- 不禁止选择，不做 contains 自动映射，不改变 payload。

**真机未验证**：没有在 360/平板上重新走查该提示；列为待验证 P1。

## 8. 核心业务抽样回归

- 空冰箱 TRACKED/混合导入、match、计划、无流水：PASS。
- TRACKED 参与 match、RECIPE_ONLY 不参与：PASS。
- 计划不扣库存：PASS（movements=0）。
- 做饭确认/重复确认/不足库存：NOT RUN（依赖服务端 105 测试，未重复 UI）。
- MealEntry 不扣库存：NOT RUN。
- 普通成员/房主权限：NOT RUN。
- 旧 trackStock=false 历史语义：NOT RUN。

未修改 InventoryCalculator、做饭扣减或权限逻辑。

## 9. 发现的缺陷与修复

### P0 — fingerprint 未校验

- 原 `CreateAttemptStore.decode` 只检查 fingerprint 非空，不比较 payload。
- 修复：`GSON.toJson(payload).equals(fingerprint)`，不匹配即损坏。
- 自动化测试覆盖。

### P1 — C→TRACKED 名称替换无提示

- 已加行内警告；无 UI 验证。

### P1 — 360dp “请选择食材”截断

- 360dp 实际截图显示“请选择食..”；仍可点击，但可读性不足。
- 属于布局 P1；本轮未修改 UI（只记录）。

### P2 — 计划添加通过 API

- 空冰箱计划不是通过计划 UI 完成；不能声称计划 UI PASS。

## 10. 数据清理与恢复

- DemoHome 恢复：0 菜谱、0 库存批次、0 movements、1 条原有 plan slot。
- OurHome：6 菜谱、12 库存批次、8 movements，未变。
- 删除 RG1/RG2 测试 recipes、ingredients、personal sources/snapshots；删除测试 plan slot。
- 本地 pending_writes=0。
- 家庭成员关系未变。
- Pinyin IME 恢复，ADBKeyboard 卸载。
- 总 recipes 8、user_recipes 17、plan_slots 5（基线值）。

## 11. 仍未解决的交付阻塞项

1. 损坏 pending 真机注入与 UI 提示未验证。
2. 同账号家庭切换、手工+导入 pending 冲突、旧回调未验证。
3. 平板横屏、英文、字体放大、深色、长列表未验证。
4. 做饭/MealEntry/权限/legacy false 未真机抽样。
5. C→TRACKED 名称替换提示未真机验证。
6. 360dp 模式选择文本截断（P1 可读性）。
7. 空冰箱详情/编辑器重开、计划 UI 未验证。

## 12. Final QA-1 是否通过

**未通过。** 已通过空冰箱导入/match/计划/无流水、TRACKED-only/混合/A-B→RECIPE_ONLY/C→TRACKED、UNKNOWN 进程死亡恢复、跨账号隔离、六类损坏 pending 的自动化安全校验；但仍有 P0/P1 真机项 NOT RUN，且 360dp 可读性有 P1 观察。

## 13. Phase C1/C2 建议（不实施）

### C1 家庭自定义食材

实施前必须处理：

- 家庭 scope：ingredient 需要 `household_id` 或家庭专属目录；禁止跨家庭名称/ID 泄漏。
- 唯一约束：家庭内规范化名称/别名唯一；不能与全局 canonical 名称冲突。
- 单位约束：自定义食材规范单位不可随意变更；已有 recipe_ingredients 引用。
- 权限：只有房主/授权成员可创建、编辑、停用；普通成员只能读取/选择。
- 迁移：需要数据库迁移和 OpenAPI scope 字段；当前 Contract 未定义。
- 搜索/别名：家庭内别名匹配、跨 scope 选择器。

### C2 自定义食材图片

- 可复用现有 HOUSEHOLD Media scope 的上传/读取接口，但需确认：
  - 图片归属家庭；
  - 成员移除后不可读；
  - 删除/替换的孤儿清理；
  - 尺寸/压缩限制。
- 纯视觉展示可延期；C2 必须在 C1 后。

### 最小可用范围建议

- C1：家庭自定义名称 + 规范单位 + 家庭内别名 + 选择/编辑；不做图片。
- C2：C1 稳定后复用 HOUSEHOLD Media，单图，不做复杂裁剪。
- 工作量（粗估）：C1 3–5 人日；C2 2–3 人日；不含最终 QA 回归。

## 14. 本轮修复 Commit

- **`7f6c1f0`**：Release Gate 1 的 C→TRACKED 名称替换提示 + 报告。
- 本轮 Final QA-1 待提交：fingerprint 校验、损坏 codec 测试、本报告。
