# Batch 3C-2 变更记录：Android 双阶段房主转移与家庭管理 UI

日期：2026-11-27（Asia/Shanghai）
基线：`2a15793`（Batch 3C-1）
范围：仅 3C-2；未进入 3C-3，未改服务端权限规则、迁移或 OpenAPI。

## 1. 开发前检查

| 检查 | 结果 |
| --- | --- |
| Git HEAD | `2a15793`，工作区干净 |
| 服务端 | 3A/3B 接口已部署在本地 `:8080`；`dissolve`、转移 5 个动作可用 |
| 生成客户端 | `Household.ownerUserId`、`OwnershipTransferRequest`、转移接口均已生成 |
| 现有页面 | `HouseholdFragment`（家庭设置）+ `HouseholdViewModel`；Profile 展示成员列表 |
| 现有能力 | 创建/加入、改名、邀请码、成员退出、`PendingWriteStore`、`IdempotentAction` 思想 |

## 2. 家庭管理交互

在现有「家庭设置」页内扩展，未改导航或设计系统。

- **房主展示**：`房主：X`；当前用户是房主时显示 `房主：X（你）`。
- **PENDING_CLAIM**：显示「该家庭尚未确认房主（PENDING_CLAIM），暂不支持转移；请联系管理员处理」，不提供自助认领。
- **发起转移**（仅房主、无 PENDING、存在其他有效成员）：
  1. 选择成员（排除自己与非活跃成员）；
  2. 后果说明：「只有对方接受后，房主身份才会转移。等待期间你仍保留全部房主权限。」；
  3. 确认后发送请求。
- **待处理状态**：
  - 房主：`已向 X 发起转移，等待对方接受。` +「取消请求」；
  - 目标：`X 邀请你成为新房主。` +「接受」「拒绝」；
  - 其他成员：不展示请求详情（服务端也只返回空列表）。
- **接受确认**：「接受后，你将成为该家庭的新房主，并获得家庭计划管理权限。」
- **退出与解散**：
  - 普通成员：原流程直接退出；
  - 多人房主：服务端 409 `OWNER_MUST_TRANSFER` → 提示先转移，并提供「去转移」；若已有 PENDING，提示等待或取消；
  - 单人房主：服务端 409 `OWNER_MUST_DISSOLVE` → 明确确认后调用 `dissolve`；
  - 解散成功清除当前家庭、返回个人页，不再展示该家庭管理操作。
- 接受/拒绝/取消/解散成功后刷新房主、成员、待处理请求；计划页在 `onResume` 重新读取角色。

## 3. 状态管理

`HouseholdViewModel` 新增：

- `household`：Household（含 owner）。
- `members`：`listMembers` 结果（用于房主名称、目标选择）。
- `transfer`：当前 PENDING 请求（`GET owner-transfer-requests?status=PENDING`）。
- `leaveBlocked`：`OWNER_MUST_TRANSFER` / `OWNER_MUST_DISSOLVE` 的一次性事件。
- 纯规则 `HouseholdTransferPolicy`：目标过滤（排除自己/非活跃）、是否可发起、请求的发起人/目标判定。

## 4. 幂等与恢复

- 5 个写操作全部使用 `Idempotency-Key`：
  - `requestOwnershipTransfer`：payload = targetUserId
  - `acceptOwnershipTransfer` / `rejectOwnershipTransfer` / `cancelOwnershipTransfer`：payload = requestId
  - `dissolveHousehold`：payload = `dissolve`
- 发送前通过 `PendingWriteStore.record` 持久化 key + 操作 + payload；确认成功/明确失败后 `confirm` 删除。
- 网络超时/不确定：保留 key，按钮可重试，重试复用同一 key 与 payload；不同操作会提示 `RETRY_SAME_OPERATION`，不会生成新 key。
- 页面重建/进程恢复：`recover()` 按当前用户列出 pending write，同 key 重放；服务端幂等返回原结果，不重复转移。
- 账户切换：`current()` 校验 user + household；`listPending(user)` 按账号隔离，不泄露上一用户请求。
- `dissolve` 走新增的 `AuthRepository.dissolveHousehold`：成功后撤销 session household，再由 ViewModel 关闭页面。
- 实机联调中发现并修复一个缺陷：解散成功后 session household 已为 null，原 `current()` 守卫会跳过成功回调，页面不关闭。已改为关闭型操作不依赖 household 仍存在，并实机复测通过。

## 5. 错误反馈

按服务端真实 code 映射到中英文文案，不显示原始错误码：

`NOT_HOUSEHOLD_OWNER`、`NOT_TRANSFER_TARGET`、`INVALID_TRANSFER_TARGET`、`TRANSFER_ALREADY_PENDING`、`TRANSFER_NOT_PENDING`、`TRANSFER_REQUEST_NOT_FOUND`、`OWNER_MUST_TRANSFER`、`OWNER_MUST_DISSOLVE`、`HOUSEHOLD_HAS_MEMBERS`、`VERSION_CONFLICT`、网络错误。

403/404/409 不显示成功；冲突后重新加载 household/members/transfer，不泄露无权请求内容；超时保留同一幂等键。

## 6. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **161 tests / 0 failures** |
| `.\gradlew.bat :feature-ef:testDebugUnitTest -PdevStackOff` | 通过（新增 3 项 `HouseholdTransferPolicyTest`） |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（契约未变） |
| `git diff --check` | 无空白错误 |

新增单测覆盖：目标排除自己/非活跃、发起条件（房主/无 pending/有目标）、请求发起人/目标判定。

## 7. 双账号实机联调

临时账号 A（Transfer Owner）与 B（Transfer Member），临时家庭；真实后端 `:8080`、MI PAD 4 PLUS。

1. A 打开家庭设置：显示 `房主：Transfer Owner（你）`、转移入口。
2. A 发起转移 → 选择 B → 确认后果说明 → 状态变为 `已向 Transfer Member 发起转移…`，A 仍为房主。
3. B 登录：显示 `Transfer Owner 邀请你成为新房主。` + 接受/拒绝；接受确认文案正确。
4. B 接受：房主变为 `Transfer Member（你）`，转移入口重新出现；A 再登录显示房主为 Transfer Member、无管理入口。
5. B（房主、多人）点退出：服务端 409 → 提示「请先将房主身份转移给其他家庭成员…」+「去转移」。
6. B 再发起转移给 A；A 登录后「拒绝」：请求消失，B 仍为房主。
7. B 再发起转移给 A；B「取消请求」并确认：请求消失，B 仍为房主。
8. A 退出家庭（普通成员）成功，个人页显示未加入家庭。
9. B 成为单人房主，点退出 → 服务端 409 → 解散确认 → 解散成功，返回个人页；数据库 household 已关闭、owner NULL，会话不再指向该家庭。
10. 联调后清理临时家庭/账号，households 4 / users 6 / memberships 5 / plan_slots 5 恢复原值；OurHome 房主仍为 `verify_1209`。

覆盖：发起、接受、拒绝、取消、房主转移后权限、多人房主退出提示、单人解散、非目标不可见/不可操作（UI 隐藏 + 服务端空列表/403）。

## 8. 未完成的真机验证

- 手机竖屏（≈360dp）与字体放大；当前设备为平板。
- 旋转、切 Tab、系统返回、进程被杀后的恢复（逻辑已实现，未逐项实机）。
- 真实断网/超时注入（幂等键复用逻辑已实现，未用网络故障工具验证）。
- 3C-3 的全面错误/生命周期回归。

## 9. 剩余风险

- 房主转移无推送；对方需进入家庭设置或计划页触发刷新。
- 窄屏下转移按钮行（接受/拒绝/取消）可能换行。
- `PENDING_CLAIM` 家庭只能查看说明，无法转移/解散，依赖人工认领。
- 设备当前登录的临时账号已在清理中删除，App 会话会失效，需要重新登录正式账号。

## 10. Batch 3C-3 必要检查

- 计划页与家庭页在转移后的统一刷新与错误提示（含 `PLAN_PERMISSION_DENIED`）。
- 手机/平板横竖屏、字体缩放、系统返回、页面切换、加载态。
- 断网/超时/重复点击/进程重建的完整回归。
- 家庭页与计划页的最终视觉/文案一致性检查。
- 清理测试残留并确认正式账号数据。
