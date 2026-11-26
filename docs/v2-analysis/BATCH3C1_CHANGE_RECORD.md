# Batch 3C-1 变更记录：Android 家庭计划权限与追加操作

日期：2026-11-27（Asia/Shanghai）
基线：`f96b719`（Batch 3B）
范围：仅 3C-1；未实施房主转移 UI（3C-2）与完整错误/生命周期回归（3C-3）。

## 1. 开发前检查

| 检查 | 结果 |
| --- | --- |
| Git HEAD | `f96b719`，工作区干净 |
| 开发库 | `civa` 已应用 Batch 3A 迁移（11 条 Flyway，`owner_user_id` 等存在） |
| 服务端 | 98 tests / 0 failures（Batch 3B 基线） |
| 契约 | `checkContracts` 通过，生成的 Android 客户端包含 `Household.ownerUserId` |
| 现有实现 | `PlanFragment` 单文件 14 天 × 三餐列表；每行「选菜（整槽替换/清空）+ 锁定」；无角色判断 |

## 2. 权限状态获取与刷新

- `load()` 先调用 `GET /households/{id}`，保存 `Household.ownerUserId`；与 `SessionStore.currentUserId()` 比较得出 `isOwner()`。
- 不在本地保存永久角色；每次进入/刷新/`onResume` 重新读取；服务端仍是最终判定。
- 403 `PLAN_PERMISSION_DENIED` / `NOT_HOUSEHOLD_OWNER` 后重新加载家庭与计划，界面立即降级。
- 房主转移后返回计划页（`onResume`）会重新读取 owner；无需重新登录。

## 3. 成员与房主的操作

**普通成员**
- 只显示「添加」；无「管理」「锁定」入口。
- 添加 = 读取当前槽 items + version，保留全部旧菜品，新增 1 份，或对已存在菜品 +1 份，再提交完整列表。
- 不提供减份、删除、替换、清空、锁定的任何入口。

**房主**
- 显示「添加」「管理」「锁定/解锁」。
- 管理对话框按菜品提供 `−`、`+`、`替换`、`删除`，以及「清空」二次确认；每次操作都以当前槽 version 提交完整列表。
- 替换的候选菜谱排除已在该餐次的菜品，避免重复 recipe。
- 锁定餐次：所有角色禁用「添加/管理」；房主通过「解锁」恢复编辑（与服务端 409 规则一致）。

## 4. 追加与增份实现

- 新增纯逻辑 `PlanItemEditor`：
  - `append` 复制当前列表，已存在则 `servings + 1`，否则追加 `1` 份；
  - `adjust` / `remove` / `replace` 供房主管理；
  - 全部返回新列表，不修改已加载的 `PlanSlot`。
- 提交仍使用 `replacePlanSlot` 的完整 items + `expectedVersion`；服务端 PlanDiff 只看到新增/增份，因此成员不会触发 403，也不会覆盖旧内容。
- 版本冲突：409 后提示「计划已被家人修改」并重新加载，避免覆盖他人修改。
- 重复提交：`sending` 标志在请求完成前拦截再次点击。

## 5. 错误反馈

客户端按 `code` 映射，不直接显示错误码：

| code | 提示 |
| --- | --- |
| `PLAN_PERMISSION_DENIED` / `NOT_HOUSEHOLD_OWNER` | 仅家庭房主可以执行此操作，并刷新家庭/计划 |
| `PLAN_SLOT_LOCKED` / `PLAN_LOCKED` | 该餐次已锁定，先解锁 |
| `VERSION_CONFLICT` | 计划已被家人修改，已刷新 |
| `NOT_FOUND` / 其他 | 计划加载/保存失败提示 |

新增中英文文案（`ac_plan_add/manage/owner_only/clear_confirm/...`）。

## 6. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest -PdevStackOff` | 60 tests（含新增 7 项 `PlanItemEditorTest`），0 failures |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **158 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（契约无变化） |
| `git diff --check` | 无空白错误 |

`PlanItemEditorTest`：追加保留旧菜、追加已存在菜增份、加减份、删除、替换、替换不产生重复、编辑不修改原槽。

## 7. 双账号实机联调

设备：MI PAD 4 PLUS（1200×1920 / 320dpi），后端 `:8080`（当前 3B 代码）。

- 成员 `civa_member_34b37c` 加入 OurHome：
  - 计划页仅显示「添加」，无「管理/锁定」；
  - 在 11-27 午餐（原 `FamilyTest2 ×1`）追加 `TomatoEgg` → 行显示 `FamilyTest2 ×1、TomatoEgg ×1`，原菜保留；
  - 再次选择 `FamilyTest2` → 变为 `×2`，`TomatoEgg` 保留。
- 房主 `verify_1209`：
  - 同一行显示「添加/管理/锁定」；
  - 管理对话框删除 `TomatoEgg`、将 `FamilyTest2` 减回 `×1`；
  - 锁定 → 行显示 🔒 且按钮变「解锁」，解锁恢复；
  - 11-27 早餐添加 `TomatoEgg` 后经「清空」二次确认恢复「未安排」。
- 联调后恢复数据：`FamilyTest2 ×1`、无早餐槽；测试成员与相关行已清理；households 4 / users 6 / memberships 5 与联调前一致。
- 截图：`review/member-plan.png`、`review/owner-plan.png`。

## 8. 未执行 / 限制

- 未在手机尺寸（≈360dp）与字体放大下实测；当前设备为平板。3 个按钮在窄屏可能使菜品文字换行。
- 未做旋转、切 Tab、系统返回、断网/超时的专门回归（3C-3）。
- 未实施 3C-2 的转移/接受/拒绝/取消/解散 UI；家庭页仍无房主标识。
- 管理对话框采用“每个动作立即提交”，没有多字段草稿；冲突时关闭对话框并刷新，不存在未提交文本丢失。
- 旧 Android 客户端在成员执行整槽替换时仍会收到 403；本批已修复计划页，其他旧入口不在本批范围。

## 9. 剩余风险

- 房主转移发生在本页打开期间时，需刷新/返回/收到 403 才会更新（已有 `onResume` 与错误刷新，无推送）。
- 锁定槽的成员「添加」按钮为禁用状态，仅以 🔒 说明；未额外弹提示。
- `PLAN_LOCKED` 与 `PLAN_SLOT_LOCKED` 两种 code 均已兼容，但契约仍只写 `PLAN_LOCKED`（历史不一致）。
- 后端测试环境使用 `CIVA_MEDIA_DIR=server/build/media` 才能解密既有邀请码；这是本地环境配置，不是代码变更。
