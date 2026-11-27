# Final QA-1B 报告：Remaining Critical Regression & Release Decision

日期：2026-11-27（Asia/Shanghai）
基线：`c9359104a5a04f6a07d3011a1c627b8c45205192`
范围：Final QA-1B；未进入 Phase C / Final QA-2。

## 1. 当前基线

- 开始 HEAD：`c935910`
- 服务端 B1；Flyway `20261127130000`
- 初始数据：
  - OurHome：6 菜谱 / 12 库存批次 / 8 movements
  - DemoHome：0 菜谱 / 0 批次 / 0 movements / 1 条既有 plan slot
  - 总 recipes 8 / user_recipes 17 / plan_slots 5 / cooking_events 5 / meal_entries 7
- 工作区开始时干净。

## 2. 本轮修改

- `CreateAttemptStore.decode` 新增：
  - 内层 operation 必须为 `CREATE_HOUSEHOLD_RECIPE`；
  - fingerprint 必须等于 `Gson.toJson(payload)`。
- 新增 `CreateAttemptStoreCodecTest`，覆盖六类损坏 pending。
- `ac_import_choose` 中文从“请选择食材”缩短为“选择食材”，减少 360dp 截断。
- 本报告。

## 3. P0 — Pending 六类损坏记录

**自动化 PASS：**

| 类型 | 结果 |
| --- | --- |
| payload 缺失 | PASS（null，不恢复） |
| fingerprint 不匹配 | PASS（null） |
| 无效 Idempotency-Key | PASS（null） |
| operation 不匹配 | PASS（null/忽略） |
| userId/householdId 非法 | PASS（null） |
| version != 1 | PASS（null） |

代码行为：
- 不发送；
- 不自动删除；
- 不自动换 key；
- 不覆盖其它 pending；
- 调用方得到 `LOCAL_PENDING_CORRUPT`/本地不可用提示。

**P0 — 真机 UI 注入：NOT RUN。**
未在设备上注入损坏记录核对错误文案、记录保留、无请求；仍不能把 UI 路径标 PASS。

## 4. P0 — 手工 / 导入 Pending 冲突

**NOT RUN。**

代码审查结论（非实测）：
- 导入前 `ensureNoPendingCreate` 检查任意 create pending；
- 手工编辑器恢复任意 pending 并禁用保存；
- 同一 operation/key 不会覆盖；新操作被阻止；
- 但没有执行：
  - 手工 UNKNOWN → 尝试导入；
  - 导入 UNKNOWN → 尝试手工创建；
  - 原 pending 恢复和重复菜谱检查。

## 5. P0 — 跨账号 / 家庭

**跨账号 PASS（前轮真机）：**
- A pending 在 B 登录时不可见；
- B 打开公共详情没有重试；
- pending 的 userId/home 未被改写；
- A 重登后恢复原 key/payload，重试只创建一条，pending 清理。

**同账号切换家庭 / 退出再加入 / 手工+导入冲突 / 旧异步回调：NOT RUN/BLOCKED。**
当前没有产品级任意家庭切换入口；未构造退出/重新加入路径。

## 6. P0 — 做饭、食记、计划

### NOT RUN

未执行最小真实做饭闭环：
- 添加足量鸡蛋库存；
- cooking preview/confirm；
- 仅鸡蛋扣减、食盐无 movement；
- 重复确认不二次扣减；
- 不足库存规则；
- CookingEvent 与 InventoryMovement 一致。

未执行 MealEntry 创建/修改/删除不扣库存。

**计划：NOT RUN（UI）。** 前轮空冰箱计划通过 API 添加成功且 movements=0，但未通过计划 UI。

服务端 105 项测试全通过，但这些不能替代本轮要求的真实业务 UI/API 抽样。

## 7. P0 — 家庭权限

**NOT RUN。**

未使用房主 + 普通成员账号执行：
- 普通成员添加菜品/增加份数；
- 普通成员删除/减份/锁定被拒绝；
- 房主管理；
- 账号切换后权限不沿用缓存。

未修改权限逻辑。

## 8. P1 — UI 兼容

| 项 | 结果 |
| --- | --- |
| 360dp ImportReviewDialog | **PASS（基础）** |
| 360dp 模式/选择文字截断 | 已修中文“选择食材”，**未重新真机验证** |
| 平板竖屏 | PASS（前轮） |
| 平板横屏 | NOT RUN |
| 英文 | NOT RUN |
| 字体放大 | NOT RUN |
| 深色 | NOT RUN |
| 长食材列表滚动 | NOT RUN |
| C→TRACKED 名称替换提示 | 代码已加，**未真机验证** |

360dp 实测 screenshot 证据：`review/rg2-360.png`。当时看到：
- 原始来源、份数/时间、模式选择、取消/导入按钮可见；
- “请选择食..”截断；
- 最终文本/错误区可读。

## 9. 测试与构建

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **236 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

## 10. 实际缺陷

### P0（已修复）

- Pending fingerprint 未校验 → 已修复并由 codec 测试覆盖。

### P1（已修复/未验证）

- C→TRACKED 名称替换提示缺失 → 已加代码，未真机验证。
- 360dp 中文选择提示截断 → 中文缩短为“选择食材”，未重新真机截图。

### P1（未修复/未验证）

- 做饭/食记/计划 UI/权限/损坏 pending UI/横屏/英文/字体/深色/长列表。

### P2

- 计划 UI 未验证（前轮只 API）。
- 360dp 部分控件仍有视觉紧凑风险。

## 11. 数据清理与恢复

- DemoHome：0 菜谱、0 批次、0 movements、1 条原有 plan slot。
- OurHome：6 菜谱、12 批次、8 movements，未变。
- 总 recipes 8、user_recipes 17、plan_slots 5。
- 删除 RG1/RG2 测试菜谱、sources、snapshots 和测试 plan slot。
- 本地 `pending_writes=0`。
- 家庭成员关系未变。
- Pinyin IME 恢复，ADBKeyboard 卸载；屏幕恢复 1200×1920/320dpi。
- 未产生测试做饭/食记记录。

## 12. 已知 P0 缺陷与阻塞

- 未发现新的已确认 P0 代码缺陷；已有 fingerprint P0 已修复。
- **但**以下 P0 验证未执行：
  - 手工/导入 pending 冲突；
  - 损坏 pending 真机 UI；
  - 做饭库存/重复确认/不足；
  - MealEntry 不扣库存；
  - 家庭权限服务端校验。

因此不能基于现有证据宣布发布安全边界完整。

## 13. Final QA-1 结论

**FAIL / NO-GO。**

理由：
- 数据安全的核心 codec 有自动化 PASS，跨账号和空冰箱导入有真机 PASS；
- 但 P0 的手工/导入 pending 冲突、损坏 pending UI、做饭、食记、权限未执行；
- P1 UI 矩阵（横屏/英文/字体/深色/长列表）未执行；
- 不能将 NOT RUN 当作 PASS，也不能用 P2/低价值缺口解释为通过。

## 14. Final QA-2 / Phase C

- **Final QA-2：不能开始**，需先完成上述 P0 验证。
- **Phase C1/C2：不建议在当前状态下实施。**
- C1 前置：家庭 scope、别名/单位唯一约束、权限、迁移/Contract。
- C2 前置：C1 稳定、HOUSEHOLD Media 归属/权限/清理、图片限制。
- C1 粗估 3–5 人日；C2 2–3 人日；不含 QA。

## 15. 尚余必须交付工作

1. 手工/导入 pending 冲突真机测试。
2. 损坏 pending 至少一条 UI 路径。
3. 同账号家庭切换/退出再加入/旧回调。
4. 做饭库存最小闭环与重复确认、不足库存。
5. MealEntry 不扣库存真机/API。
6. 计划 UI 添加缺料菜谱。
7. 房主/普通成员权限。
8. 360dp 重新验证、横屏、英文、字体放大、深色、长列表。
9. C→TRACKED 名称替换提示真机验证。
10. 最终 QA-2 全量质量门禁。
