# Batch 3A 变更记录：房主身份、双阶段转移与解散规则

日期：2026-11-27（Asia/Shanghai）
分支：`refactor/core-ui-and-bd-modules`
范围：仅 Batch 3A。计划权限（3B）与 Android 家庭/计划 UI（3C）未改动。

## 1. 已确认决策

| ID | 落地方式 |
| --- | --- |
| D1 | 待处理请求仅房主与目标可见；其他成员查询返回空页（不泄露详情） |
| D2 | 目标成员退出时在同一事务内把其 PENDING 请求置 `CANCELLED`，记录 `resolved_by`/`resolved_at` |
| D3 | 不提供公开认领接口；人工流程见 `docs/ops/CLAIM_HOUSEHOLD_OWNER.md` |
| D4 | 迁移推定房主 `owner_confirmed_at = NULL`，保留 `MIGRATION_SIGNATURE/SOLE` 来源；权限完整 |
| D5 | 发起/接受/拒绝/取消/解散均强制 `Idempotency-Key`；重放重新校验当前权限 |
| D6 | 转移等待期间原房主保留全部房主权限（改名、邀请、取消、禁止退出） |
| D7 | 新增错误码与 HTTP 状态码，契约与生成代码同步 |

## 2. 数据库

新增迁移 `V20261127120000__household_owner_and_transfers.sql`（前向、只加列/加表，不删历史数据）：

- `households.owner_user_id / owner_source / owner_assigned_at / owner_confirmed_at`
- 回填优先级：唯一创建时刻签名 → `MIGRATION_SIGNATURE`；否则唯一活跃成员 → `MIGRATION_SOLE`；
  否则 `PENDING_CLAIM`（owner NULL）；已关闭 → `CLOSED`
- `CHECK (closed_at IS NOT NULL OR owner_user_id IS NOT NULL OR owner_source='PENDING_CLAIM')`
  （已校验约束；实测普通 `NOT VALID` 会拒绝待认领行的任何更新，故不采用）
- `ownership_transfer_requests` + 部分唯一索引 `(household_id) WHERE status='PENDING'`

### 开发库演练（事务内执行后回滚，未修改数据）

- 3 个未关闭家庭全部 `MIGRATION_SIGNATURE`：`AAA→aaa`、`DemoHome→uilocal01`、`OurHome→verify_1209`
- 待认领/无 owner：0；owner 非活跃成员：0；已关闭家庭保留
- 约束拒绝无 owner 的开放家庭（23514）；同一家庭第二条 PENDING 被拒（23505）
- 回滚后数据不变

### 迁移测试

`HouseholdOwnerMigrationTest` 在临时 schema 中先用 Flyway 迁移到 3A 之前的版本，插入四类历史家庭，
再执行 3A 迁移并断言：签名/单人/歧义/已关闭的归属、待认领家庭可改名、认领后清空 owner 被拒、
非法新行被拒、已关闭无 owner 允许、每家庭最多一条 PENDING。

## 3. API

| 变更 | 说明 |
| --- | --- |
| `Household.ownerUserId/ownerSource` | 可空新增字段，旧客户端忽略即可 |
| `POST /households/{id}/owner-transfer-requests` | 房主发起，201 PENDING，不改变 owner |
| `GET /households/{id}/owner-transfer-requests` | 房主/目标可见；其他成员空页 |
| `POST .../{requestId}/accept` | 目标接受；单事务更新请求 + owner + version |
| `POST .../{requestId}/reject` | 目标拒绝 |
| `POST .../{requestId}/cancel` | 房主取消 |
| `POST /households/{id}/dissolve` | 单人房主解散，204，关闭并清空 owner |
| `DELETE /households/{id}/members/{userId}` | 房主退出返回 409 `OWNER_MUST_TRANSFER` / `OWNER_MUST_DISSOLVE` |

错误码：`NOT_HOUSEHOLD_OWNER`(403)、`NOT_TRANSFER_TARGET`(403)、`INVALID_TRANSFER_TARGET`(400)、
`TRANSFER_ALREADY_PENDING`(409)、`TRANSFER_NOT_PENDING`(409)、`TRANSFER_REQUEST_NOT_FOUND`(404)、
`OWNER_MUST_TRANSFER`(409)、`OWNER_MUST_DISSOLVE`(409)、`HOUSEHOLD_HAS_MEMBERS`(409)。

契约登记：`contracts/changes/IC-20261127-EF-OWNER-TRANSFER.md`；索引已重新生成。

## 4. 服务端实现

| 文件 | 改动 |
| --- | --- |
| `HouseholdOwnershipService`（新增） | 发起/查询/接受/拒绝/取消/解散；行锁 + 条件更新 + 幂等 |
| `HouseholdWriteService` | 创建家庭写入 owner |
| `HouseholdReadService` | 查询返回 owner |
| `HouseholdMembershipService` | 房主退出规则；目标退出取消请求 |
| `HouseholdController` | 新端点与响应映射 |
| `HouseholdErrors` | 新错误码映射 |
| `HouseholdConfiguration` | 新服务 Bean |

并发：接受与取消都先锁 `households` 行，再做 `WHERE status='PENDING'` 条件更新；接受时重新校验
当前 owner、目标成员资格与家庭状态；幂等重放不重复转移。目标退出自动取消请求与退出同事务。

## 5. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat validateContract` | 无问题 |
| `.\gradlew.bat checkContracts` | contracts consistent |
| `server: .\gradlew.bat test` | **87 tests / 0 failures / 0 errors** |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **151 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | 通过（无错误） |
| `.\gradlew.bat assembleDebug -PdevStackOff` | 通过 |
| `git diff --check` | 无空白错误 |

新增/更新的关键覆盖：创建即 owner、回填四类数据、仅房主发起、仅目标接受/拒绝、重复发起冲突、
接受后旧房主立即失权、重复接受不二次转移、接受与取消并发唯一胜者、目标退出取消请求、
房主退出 409、单人房主解散、跨家庭访问、待认领家庭可正常改名、转移期间改名/邀请可用。

既有测试中“创建者可自由退出/最后一人关闭家庭”的断言按新规则改写为“房主必须先转移或解散”，
并保留/加强了并发与权限断言；未删除测试。

## 6. 兼容性

- 新增响应字段可空、新增端点增量；旧客户端不受影响。
- **已知行为变化**：多人家庭的房主直接退出由 204 变为 409（`OWNER_MUST_TRANSFER`）；
  单人房主退出变为 409（`OWNER_MUST_DISSOLVE`），需调用解散。Batch 3C 需处理这两个错误码。
- 开发库尚未落盘迁移；后端下次启动时 Flyway 会应用。已在事务内演练并回滚。

## 7. 剩余风险 / 未做

- `PENDING_CLAIM` 家庭无房主，房主专属操作 403；依赖人工流程认领。
- 无通知/邮件/请求过期（按决策不做）。
- 计划删减权限仍开放给成员，3B 修复。
- 迁移推定的 owner 未强制二次确认（`owner_confirmed_at=NULL`），仅来源可见。
- 幂等重放对“目标已退出”的接受请求返回 404（当前无权访问），符合 D2.6。

## 8. Batch 3B 前置条件

- `households.owner_user_id` 已可读；3B 的 `PlanPermissions` 直接以它判定房主。
- 需要新增 `PLAN_PERMISSION_DENIED`(403) 并接入 replace/lock/commit 的 diff 判定。
- 需要把“成员只能新增/增份”的 UI 门控留给 3C；服务端先行强制。
