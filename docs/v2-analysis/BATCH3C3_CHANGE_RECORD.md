# Batch 3C-3 变更记录：稳定性、权限同步与回归

日期：2026-11-27（Asia/Shanghai）
基线：`a5fa28e`（Batch 3C-2）
范围：仅 3C-3；未新增大型功能，未改数据库/服务端权限规则。

## 1. 开发前检查

| 检查 | 结果 |
| --- | --- |
| Git HEAD | `a5fa28e`，工作区干净 |
| 提交链 | 3A `3386f38`、3B `f96b719`、3C-1 `2a15793`、3C-2 `a5fa28e` 均存在 |
| 开发库 | `civa` 已应用 3A 迁移（11 条 Flyway） |
| 契约 | Android/服务端生成代码与 `contracts/openapi.json` 一致 |
| 运行版本 | 后端 `:8080` 与设备 APK 均为当前分支版本 |

## 2. P0 检查与修复

### P0-1 历史写操作被自动重放

**问题**：3C-2 的 `recover()` 会对转移/解散的 pending write 直接重发。仅因为 idempotency key 相同，不代表该操作在当前用户/家庭/状态下仍应执行：请求可能已改变房主、已被拒绝/取消，或用户已不再希望继续。

**修复**：`recover()` 不再自动发送。它恢复原 key/operation/payload，加载当前 household/成员/请求，并弹出「有未完成的房主操作」对话框：

- 「继续上次操作」：使用**原 key + 原 payload** 重发，服务端重新鉴权；
- 「放弃」：只删除本地 pending 记录，不回滚服务器；
- 取消/关闭对话框不会创建新操作；
- 账号或家庭不匹配的 pending write 直接丢弃。

未自动重放、未使用新 key。

### P0-2 同一 key 复用于不同请求内容

**问题**：`withIntent` 只比较 operation，未比较 payload；超时后用户改选其他目标仍会复用旧 key，服务端返回 `IDEMPOTENCY_KEY_REUSED`。

**修复**：新增 `HouseholdTransferPolicy.sameIntent(operation, payload, ...)`；operation 或 payload 任一不同即提示 `RETRY_SAME_OPERATION`，不发送、不生成新 key。有单测。

### P0-3 解散成功后页面不关闭

3C-2 已发现并修复（`finishTransfer` / `transferCallback` 不再要求 household 仍存在）；3C-3 复测确认。

## 3. P1 检查与修复

### P1-1 权限状态被旧响应覆盖

`PlanFragment` 和 `HouseholdViewModel` 的 household 加载没有请求代际；转移接受后，onResume 的新响应可能被更早的旧响应覆盖，短暂恢复旧房主 UI。

**修复**：

- `PlanFragment` 增加 `loadGeneration`，household → recipes → slots 全链路校验，写后刷新也递增代际；
- `HouseholdViewModel` 增加 `householdGeneration`，household / members / transfer 均校验代际。

### P1-2 手机 360dp 计划行布局

实测 720×1280/320dpi（≈360dp）时，三个按钮默认 88dp 最小宽度把菜品文字压成逐字竖排。

**修复**：计划行按钮改为紧凑按钮（`minWidth=0`、`minimumWidth=0`、水平 padding 6dp、去掉上下 inset）。360dp 下按钮约 36dp/个，菜品文字恢复为 188dp 宽的正常换行；平板与横屏不受影响。

### P1-3 `PLAN_LOCKED` / `PLAN_SLOT_LOCKED` 契约不一致

运行时/服务端测试/Android 使用 `PLAN_SLOT_LOCKED`，OpenAPI 却写 `PLAN_LOCKED`。

**修复**：仅将 `replacePlanSlot`、`commitPlan` 的 409 枚举改为 `PLAN_SLOT_LOCKED`（IC `IC-20261127-EF-PLAN-LOCK-CODE`），服务端与 Android 运行时代码未改；Android 同时兼容两种拼写，旧客户端不受影响。

### P1-4 错误反馈核对

逐项检查 `PLAN_PERMISSION_DENIED`、`NOT_HOUSEHOLD_OWNER`、`NOT_TRANSFER_TARGET`、`INVALID_TRANSFER_TARGET`、`TRANSFER_ALREADY_PENDING`、`TRANSFER_NOT_PENDING`、`TRANSFER_REQUEST_NOT_FOUND`、`OWNER_MUST_TRANSFER`、`OWNER_MUST_DISSOLVE`、`HOUSEHOLD_HAS_MEMBERS`、`VERSION_CONFLICT`、`PLAN_SLOT_LOCKED`、`PLAN_LOCKED`：

- 均有中英文映射；不显示原始 code；
- 403/404/409 后刷新 household/members/transfer 或 plan，不显示成功；
- 无未提交文本被丢弃的场景（计划操作即时提交、家庭页无草稿）；
- 错误恢复不会再次执行已完成的写操作（重放仅由用户确认的「继续」触发）。

## 4. P2 登记（未修改）

- App 会话仅存内存：进程被系统杀死/强制停止后需要重新登录（测试中观察到）。属于既有会话设计，不在 3C 范围。
- 房主转移无推送；需进入页面/触发刷新。
- `PENDING_CLAIM` 家庭只能查看说明，依赖人工认领。
- 成员列表超过 50 人时房主名称可能回退为短 ID。
- 360dp 下 1.3 倍字体时菜品文字换行到约 3 行，仍可读、按钮可点击；未进一步压缩。

## 5. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **162 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL |
| `server: .\gradlew.bat test` | **98 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

新增单测：`sameIntent` 同操作同 payload 才允许复用 key；不同 payload/不同操作/无 pending 均拒绝。

## 6. 权限同步实机验收

临时家庭 + A（房主）/B（成员）：

1. 设备登录 A，计划页显示「添加/管理/锁定」。
2. 通过 API 发起 A→B 转移，B 接受，房主变为 B。
3. 设备上 A 切到「今天」再回到家庭计划（触发 onResume），**未重新登录**；
4. 计划页只剩「添加」，无「管理/锁定」；A 直接调用减份 API 返回 403。
5. 清理临时家庭/账号后，households 4 / users 6 / memberships 5 / plan_slots 5 恢复原值。

## 7. 布局与生命周期验证

- 手机 360dp（720×1280 / 320dpi）：修复后计划行文字正常换行、三按钮不重叠、可点击；截图 `review/phone-plan3.png`。
- 字体 1.3 倍：按钮与文字不重叠，标签换行 3 行仍可读；`review/plan-font13.png`。
- 平板竖屏：正常；平板横屏：三按钮与标签正常；`review/landscape-plan.png`。
- 页面重建/旋转：ViewModel 保留 pending 状态；PlanFragment 有代际保护；未做提交中旋转的逐帧验证。

## 8. 未执行

- 真实网络故障注入（飞行模式/代理）验证恢复对话框与同 key 重试；当前通过代码路径与单测覆盖逻辑，未做故障注入。
- 进程被杀后 pending write 恢复：需要注入本地 PendingWriteStore，未执行。
- 提交中旋转/切 Tab/离开页面的逐个实机场景。
- 英文界面逐页人工检查（字符串已补齐）。
- 食记/做饭/库存的完整 UI 跨模块回归；由服务端 98 项测试覆盖（含 cooking、inventory、meal entries）。

## 9. 残留风险

- 恢复对话框的「放弃」只删本地记录，服务器可能已完成操作；页面刷新后会显示真实状态。
- 无推送，角色变化依赖 resume/刷新/403。
- 会话不持久化，进程死亡需重新登录（P2，建议下一阶段处理）。
- 设备当前登录的临时账号已在清理中删除，App 会话失效，需要重新登录正式账号。

## 10. Batch 3 关闭结论

P0 全部修复并有回归测试/实机验证；P1 全部在本批修复；P2 已登记且不阻塞交付。建议 **Batch 3 关闭**，进入后续产品功能开发前的会话持久化与 E2E 自动化建设。

## 11. 后续优先级建议

1. 会话持久化与静默刷新（当前进程死亡即登出）。
2. 房主转移通知/待办入口（无推送）。
3. `PENDING_CLAIM` 人工认领管理工具。
4. 计划份数 stepper、移动计划项等交互完善。
5. Android instrumentation/E2E 测试覆盖转移与计划权限。
