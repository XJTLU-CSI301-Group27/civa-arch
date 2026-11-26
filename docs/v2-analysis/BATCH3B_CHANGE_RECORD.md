# Batch 3B 变更记录：家庭计划权限（服务端强制）

日期：2026-11-27（Asia/Shanghai）
基线：`3386f38`（Batch 3A）
范围：3A 开发库迁移落地 + 3B 服务端计划权限；未改动 Android UI（3C）。

## 1. Batch 3A 开发环境验收

| 检查 | 结果 |
| --- | --- |
| Git HEAD | `3386f38`，工作区干净 |
| 数据库身份 | `civa`（Docker `infra-db-1`，PostgreSQL 16.15，172.18.0.2:5432，12 MB）；`civa_test` 为测试库；无生产库 |
| 迁移前版本 | `20261126213000`，10 条 Flyway 记录全部 success，3A 未应用 |
| 备份 | `review/civa_pre_3b.dump`，607,072 bytes，SHA256 `4C91EF7C8D8299338B3439BE5B625AD80C86E4FF948394299C4A0563E7EAA6C3` |
| 备份可恢复性 | 恢复到临时库 `civa_restore_check` 成功；users 6 / households 4 / memberships 5 / plan_items 9 / cooking_events 3 / inventory_batches 12 与源库一致；验证后删除临时库 |
| 迁移执行 | `v20261127120000` 成功；启动时校验 11 个迁移 |
| 结构 | `owner_user_id/owner_source/owner_assigned_at/owner_confirmed_at`、`ownership_transfer_requests`、`ownership_transfer_one_pending`、`households_owner_when_open`、`households_owner_source` 均存在 |
| 数据量 | 迁移前后逐表计数完全一致（users 6、households 4、memberships 5、invitations 4、ingredients 36、recipes 7、recipe_ingredients 7、inventory_batches 12、inventory_movements 7、plan_slots 5、plan_items 9、cooking_events 3、meal_entries 7、media 1、idempotency_requests 59、user_recipes 14、recipe_favorites 4） |
| 房主回填 | AAA→`aaa`、DemoHome→`uilocal01`、OurHome→`verify_1209`，全部 `MIGRATION_SIGNATURE`；无 owner 的开放家庭 0；owner 非活跃成员 0 |
| 启动 | 迁移后的库上 Web 模式启动成功（8081，Tomcat，Flyway 校验通过） |
| 家庭功能 | 登录、`/me`、家庭查询、成员列表、创建家庭（owner=`CREATED`）、解散均返回预期；验证用临时用户/家庭已清理，计数恢复原值 |

说明：先以 `--spring.main.web-application-type=none` 执行迁移，Flyway 成功后在创建 `authController` 时因 request-scope bean 失败；这是禁用 Web 上下文的预期结果，不代表迁移问题。随后 Web 模式启动验证通过。

## 2. 权限规则（服务端）

| 操作 | 成员 | 房主 |
| --- | --- | --- |
| 新增空餐次 / 新菜品 | 允许 | 允许 |
| 增加份数 | 允许 | 允许 |
| 减少份数 / 删除 / 替换 / 清空 | 403 `PLAN_PERMISSION_DENIED` | 允许 |
| 跨日期/餐次移动 | 通过“源槽删除”判定为 403 | 允许 |
| 锁定 / 解锁（含经 lock 新建槽） | 403 | 允许 |

- 计划项身份为 `(slot, recipeId)`；`plan_items` 主键 `(slot_id, recipe_id)`，请求校验拒绝同槽重复 recipe，无法通过拆分/合并或菜名绕过。
- 判定只看**持久化前后状态**：某 recipe 在 after 中缺失或总量变小 → 需要房主；新增 recipe 或总量变大 → 成员可用。客户端请求类型/权限标识完全不参与。
- 批量 `commitPlan` 对整个 slots 集合求值；任一槽需要房主则整批 403，事务回滚，无部分写入。
- 权限读取 `households.owner_user_id` 时对家庭行加 `FOR SHARE`，与转移/解散的 `FOR UPDATE` 互斥；转移完成后旧房主立即按新身份判定。
- `PENDING_CLAIM` 家庭 owner 为 NULL：成员仍可新增/增份，所有房主专属操作 403。

## 3. 接口覆盖审查

服务端写 `plan_slots/plan_items` 的 SQL 仅存在于 `PlanningService`（replace、lock、commit/applyWrite、insertSlot、writeItems）。三个入口全部接入同一 `PlanPermissions` 判定；无生成/其他隐藏写入路径。

## 4. 并发与幂等

- 槽行 `FOR UPDATE` + `expectedVersion`：两人同时增份，一个 200、一个 409，不丢失更新。
- 家庭行 `FOR SHARE`：与转移/解散串行化；转移提交后旧房主的新写请求 403。
- 批次中途失败（403/409）整批回滚。
- `commitPlan` 幂等重放：返回存储结果，不重新执行写；重放仅要求当前成员资格（结果查询权限），不做房主判定；退出家庭后重放 404，不泄露数据。
- 历史成功请求由旧房主重放：仍返回其已有结果，不产生第二次写入。

## 5. 契约

- `replacePlanSlot` / `setPlanSlotLock` / `commitPlan` 的 403 增加 `PLAN_PERMISSION_DENIED`。
- 其余请求/响应 schema 未变。
- IC：`contracts/changes/IC-20261127-EF-PLAN-PERMISSIONS.md`。
- 既有不一致（未在本批修改）：契约 409 写作 `PLAN_LOCKED`，运行时/测试使用 `PLAN_SLOT_LOCKED`；3C 以运行时代码为准。

## 6. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `server: .\gradlew.bat test` | **98 tests / 0 failures / 0 errors** |
| `.\gradlew.bat checkContracts` | `contracts consistent` |
| `.\gradlew.bat validateContract` | 无问题 |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `git diff --check` | 无空白错误 |

新增 `PlanPermissionHttpIntegrationTest`（11 项）：成员新增空槽/菜品/增份；减份/删除/替换/清空 403 且数据不变；锁定/解锁 403；批量提交绕过 403 且整批回滚；同名菜替换 403、并列新增允许；并发增份 200/409；转移后旧房主 403、新房主允许；`PENDING_CLAIM` 仅允许新增/增份；非成员 404；版本/锁定错误优先；幂等重放不二次写入、退出后 404。

既有 `PlanHttpIntegrationTest`、`PlanAuthorHttpIntegrationTest` 保持通过（房主操作）。

## 7. 对 Android 的影响（3C 必做）

- 成员在计划页执行“选菜（整槽替换）”、清空、删除、减份、锁定会收到 403 `PLAN_PERMISSION_DENIED`；3C 需隐藏/禁用这些入口并提示“仅房主可操作”。
- 成员的“加菜”必须改为追加/增份语义，不能复用整槽替换。
- 锁定槽替换运行时代码为 `PLAN_SLOT_LOCKED`（409）。
- 转移/解散错误码与接口见 Batch 3A 记录。

## 8. 剩余风险

- `PENDING_CLAIM` 家庭无法减少/锁定计划，需人工认领后才能管理。
- `commitPlan` 对锁定槽保持“跳过”语义（既有行为），成员提交锁定槽不会产生修改。
- 旧 Android 客户端仍展示成员可用的减份/替换入口，操作会失败；3C 修复前属已知兼容性变化。
- 开发库已迁移并留有备份 `review/civa_pre_3b.dump`；测试库已应用 3A/3B。
