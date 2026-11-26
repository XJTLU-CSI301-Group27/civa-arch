# Batch 3 实施方案（修订版）：房主身份、双阶段转移与家庭计划权限

日期：2026-11-27（第二版）　基线：`c616a98`（上一版计划）
状态：**仅审查与规划，未实施**。实施需批准 3A；3B/3C 另行批准。

---

## 0. 本版修订要点

1. 房主转移由“单方面指定”改为 **双阶段：房主发起 → 目标成员接受 → 原子生效**（PD-6）。
2. 新增 `ownership_transfer_requests` 表与 5 个服务端动作（发起/查询/接受/拒绝/取消）。
3. **实测 PostgreSQL 约束行为**后，放弃“简单 NOT VALID CHECK”，改用“含 PENDING_CLAIM 的 OR 约束”，并作为**已校验约束**添加。
4. 重新核对历史数据：3 个未关闭家庭全部可由“创建时刻签名”确定创建者，无待认领家庭。
5. 更新 API、服务端文件、客户端范围、测试矩阵、工作量与交付影响。
6. 明确“必须完成 / 可延期”。

---

## 1. Part A（Batch 2C）最终验收结论

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 匹配家庭菜谱时「做饭」进入详情 | ✅ | 临时把 11-26 计划菜改为 `TomatoEgg` → 点「做饭」→ 详情 `ac_detail_name=TomatoEgg`、`ac_detail_cook=确认做饭` |
| 完整做饭确认流程 | ✅ | 详情「确认做饭」→ 份数对话框 → 确认成功 |
| 库存只扣一次 | ✅ | 烹饪事件 2→3、库存流水 2→3、批次 200g→0（单条 COOK）；随后 CORRECT 恢复 200g |
| 手机尺寸按钮布局 | ✅ | 720×1280 / 320dpi（≈360dp）：标签右缘 296、做饭 304–480、记录用餐 488–664、内容右缘 688，无重叠 |
| 既有食记功能 | ✅（只读检查） | 月汇总 350 kcal / 1 条未估算、日汇总、当日记录、做饭历史均正常 |

残留：1 条测试烹饪事件及其 COOK+CORRECT 流水保留；批次已恢复；设备草稿与临时文件已还原。**无需修复 Batch 2C。**

---

## 2. 当前权限架构（代码事实）

### 2.1 数据

| 表 | 关键列 | 现状 |
| --- | --- | --- |
| `households` | `id, name, zone_id, version, created_at, updated_at, closed_at` | **无创建者/房主字段** |
| `memberships` | `id, household_id, user_id, role, active, version, created_at` | 全部 `MEMBER`（`V20261125210000` 主动降级） |
| `invitations` | 永久邀请码 | 任意 active 成员可读/可创建 |
| `plan_slots / plan_items` | `household_id` 归属 | 写操作仅 `requireMember` |

### 2.2 流程

- 创建：household 与创建者成员在同一事务插入 → `membership.created_at == household.created_at`（`CURRENT_TIMESTAMP` 为事务开始时间）。
- 加入：校验永久邀请码 + 锁 household + 插入 `MEMBER`。
- 退出：仅本人；最后一人退出自动 `closed_at`；**创建者无特殊地位**。
- 改名：任意 active 成员（PD-4 保持不变）。
- 邀请码：全员可读/可创建（PD-5 保持不变）。
- 移除他人：一律 403（PD-3 本期不开放）。

### 2.3 计划写接口

`replacePlanSlot`（整槽替换/清空）、`lock`、`commit`（客户端未用），均只校验成员资格；`replace` 先删后插，因此任何成员都能删减/替换。**3B 修复。**

### 2.4 客户端

`SessionStore` 无角色状态；`PlanFragment` 只有「选菜（替换/清空）」+「锁定」；家庭页无房主标识、无转移/解散。**3C 适配。**

---

## 3. 已确认决策（PD-1…PD-7）

| ID | 决策 |
| --- | --- |
| PD-1 | 回填优先级：①唯一匹配“创建时刻签名”的活跃成员 → 创建者候选；②无法确定但只有一名活跃成员 → 该成员；③多名活跃且无法确认 → `PENDING_CLAIM`；④已关闭家庭无需房主。`created_at` 相等只是识别证据，需核对生成机制与异常。不得随机分配、不得删数据。 |
| PD-2 | 无法确定 → `PENDING_CLAIM`：保留家庭/成员；**不提供普通成员自助认领**；非房主业务尽量可用；提供后续人工确认机制；**不得使用会让待认领家庭正常更新失败的约束**。 |
| PD-3 | 本期不开放房主移除成员；保留自助退出并适配房主退出规则。 |
| PD-4 | 改名保持“所有活跃成员可改”。 |
| PD-5 | 邀请码保持“所有活跃成员可查看/创建”。 |
| PD-6 | **双阶段转移**：房主发起 → 目标接受 → 服务端原子更新；PENDING 状态；仅目标可接受/拒绝；房主可取消；每家庭最多一条 PENDING；接受时重新校验家庭/房主/目标；处理后不得再次改变房主；房主在转移完成前不得退出多人家庭；单人家庭可经明确确认解散；首版不做推送/邮件/到期提醒。 |
| PD-7 | 新增错误码：`PLAN_PERMISSION_DENIED`、`OWNER_MUST_TRANSFER`、`OWNER_MUST_DISSOLVE`、`NOT_HOUSEHOLD_OWNER`；双阶段转移另加请求状态/并发错误码（见 §6.3）。 |

---

## 4. 目标模型

### 4.1 家庭身份

`households` 新增：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `owner_user_id` | UUID, FK users, nullable | 未关闭家庭必须有；已关闭可为空 |
| `owner_source` | VARCHAR(24) | `CREATED` / `MIGRATION_SIGNATURE` / `MIGRATION_SOLE` / `PENDING_CLAIM` / `TRANSFER` / `CLAIM` / `CLOSED` |
| `owner_assigned_at` | TIMESTAMPTZ | 最近一次 owner 变更时间 |
| `owner_confirmed_at` | TIMESTAMPTZ, nullable | 创建/转移/认领时写入；迁移推定值可为 NULL（前端可显示“历史推定”） |

### 4.2 转移请求

`ownership_transfer_requests`：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | UUID PK | |
| `household_id` | UUID FK households | |
| `requested_by` | UUID FK users | 发起时必须是房主 |
| `target_user_id` | UUID FK users | 目标成员，且 ≠ requested_by |
| `status` | VARCHAR(16) | `PENDING` / `ACCEPTED` / `REJECTED` / `CANCELLED` |
| `version` | BIGINT | 行版本（可选乐观锁） |
| `created_at / updated_at` | TIMESTAMPTZ | |
| `resolved_at` | TIMESTAMPTZ | 非 PENDING 必填 |
| `resolved_by` | UUID FK users nullable | 接受/拒绝/取消的操作者；目标退出自动取消时为该用户 |

约束：

```sql
CREATE UNIQUE INDEX ownership_transfer_one_pending
  ON ownership_transfer_requests(household_id) WHERE status = 'PENDING';
CHECK ((status = 'PENDING' AND resolved_at IS NULL)
    OR (status <> 'PENDING' AND resolved_at IS NOT NULL));
CHECK (requested_by <> target_user_id);
```

### 4.3 状态机

```
(无) --房主发起--> PENDING --目标接受--> ACCEPTED  (+ households.owner_user_id=target)
                        \--目标拒绝--> REJECTED
                        \--房主取消--> CANCELLED
                        \--目标退出家庭(自动)--> CANCELLED
```

- 每个家庭最多一条 PENDING（部分唯一索引）。
- 任何已处理状态不可再变；接受操作只对 PENDING 生效。
- 接受后立即更新 `households.owner_user_id`，旧房主下一次请求即失去权限（服务端每次读 DB）。

### 4.4 计划操作矩阵（3B 实施）

| 操作 | 成员 | 房主 | 判定 |
| --- | --- | --- | --- |
| 新增菜品 | ✅ | ✅ | `added` |
| 增加份数 | ✅ | ✅ | `increased` |
| 减少份数 / 删除 / 清空 / 替换 / 跨槽移动 | ❌ 403 | ✅ | `decreased/removed/replaced/moved` |
| 锁定 / 解锁 | ❌ 403 | ✅ | 锁接口 |
| 批量提交 | 仅含新增/增份 | ✅ | 逐槽 diff，任一违规整批 403 |

---

## 5. 数据库迁移设计

### 5.1 关键实测：PostgreSQL `NOT VALID` 的真实行为

在 `civa_test` 的临时表上实测（非业务表）：

| 实验 | 结果 |
| --- | --- |
| `CHECK (closed_at IS NOT NULL OR owner IS NOT NULL) NOT VALID`，插入非法新行 | 拒绝（23514） |
| 同上，**更新一条 owner 为空的待认领行（即使只改 name）** | **拒绝（23514）** |
| 改用 `CHECK (closed_at IS NOT NULL OR owner IS NOT NULL OR owner_source='PENDING_CLAIM')` 作为**普通（已校验）**约束 | 待认领行可正常更新；认领后清空 owner 被拒；open 且无 owner 且非 pending 的新行被拒；closed 无 owner 允许 |

结论：
- **不能**使用“简单 NOT VALID CHECK”——它会让 `PENDING_CLAIM` 家庭的改名等正常更新失败，违反 PD-2。
- 采用 OR 形式的约束，并且因为回填后所有现存行都满足，可**直接作为已校验约束**添加（无需 NOT VALID）。若回填有遗漏，迁移会失败并暴露问题，比静默放行更安全。

### 5.2 历史数据判定（只读探测当前开发库）

| 家庭 | 活跃成员 | 创建时刻签名成员数 | 判定 |
| --- | --- | --- | --- |
| `8698ee4d`（AAA） | 1 | 1 | `MIGRATION_SIGNATURE` |
| `d8d967df`（DemoHome） | 1 | 1 | `MIGRATION_SIGNATURE` |
| `46187714`（OurHome） | 2 | 1 | `MIGRATION_SIGNATURE` |
| 已关闭家庭 | — | — | `CLOSED`（无需 owner） |

其他：同一家庭 `created_at` 排序歧义 0；未关闭但无活跃成员 0；已关闭仍有活跃成员 0。

生成机制核对：`households.created_at` 与 `memberships.created_at` 均使用 `CURRENT_TIMESTAMP`（事务开始时间），创建家庭时两者在同一事务内插入，故**必然相等**；加入者在新事务中插入，时间必然更晚。该签名对“应用创建的家庭”可靠，但对导入/手工数据不保证——因此仅作为“创建者候选”，并按 PD-1 的优先级降级。

### 5.3 回填规则（SQL 草案）

```sql
ALTER TABLE households ADD COLUMN owner_user_id UUID REFERENCES users(id);
ALTER TABLE households ADD COLUMN owner_source VARCHAR(24);
ALTER TABLE households ADD COLUMN owner_assigned_at TIMESTAMPTZ;
ALTER TABLE households ADD COLUMN owner_confirmed_at TIMESTAMPTZ;

WITH candidates AS (
  SELECT h.id AS household_id,
         (SELECT m.user_id FROM memberships m
           WHERE m.household_id = h.id AND m.active AND m.created_at = h.created_at
           ORDER BY m.id LIMIT 1) AS signature_user,
         (SELECT count(*) FROM memberships m
           WHERE m.household_id = h.id AND m.active AND m.created_at = h.created_at) AS signature_count,
         (SELECT count(*) FROM memberships m WHERE m.household_id = h.id AND m.active) AS active_count,
         (SELECT m.user_id FROM memberships m
           WHERE m.household_id = h.id AND m.active ORDER BY m.created_at, m.id LIMIT 1) AS earliest_user
  FROM households h WHERE h.closed_at IS NULL
)
UPDATE households h SET
  owner_user_id = CASE WHEN c.signature_count = 1 THEN c.signature_user
                        WHEN c.active_count = 1 THEN c.earliest_user END,
  owner_source = CASE WHEN c.signature_count = 1 THEN 'MIGRATION_SIGNATURE'
                      WHEN c.active_count = 1 THEN 'MIGRATION_SOLE'
                      ELSE 'PENDING_CLAIM' END,
  owner_assigned_at = CURRENT_TIMESTAMP,
  owner_confirmed_at = NULL
FROM candidates c WHERE c.household_id = h.id;

UPDATE households SET owner_source = 'CLOSED', owner_assigned_at = NULL, owner_confirmed_at = NULL
WHERE closed_at IS NOT NULL;

ALTER TABLE households ADD CONSTRAINT households_owner_when_open CHECK (
  closed_at IS NOT NULL OR owner_user_id IS NOT NULL OR owner_source = 'PENDING_CLAIM');
ALTER TABLE households ADD CONSTRAINT households_owner_source CHECK (
  owner_source IS NULL OR owner_source IN
  ('CREATED','MIGRATION_SIGNATURE','MIGRATION_SOLE','PENDING_CLAIM','TRANSFER','CLAIM','CLOSED'));
CREATE INDEX households_owner_idx ON households(owner_user_id);
```

> `signature_count = 1` 表示“唯一匹配创建时刻的活跃成员”，对应 PD-1 ①；`active_count = 1` 对应 ②；否则 ③。

### 5.4 转移请求表

```sql
CREATE TABLE ownership_transfer_requests (
  id UUID PRIMARY KEY,
  household_id UUID NOT NULL REFERENCES households(id),
  requested_by UUID NOT NULL REFERENCES users(id),
  target_user_id UUID NOT NULL REFERENCES users(id),
  status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','ACCEPTED','REJECTED','CANCELLED')),
  version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolved_at TIMESTAMPTZ,
  resolved_by UUID REFERENCES users(id),
  CHECK (requested_by <> target_user_id),
  CHECK ((status = 'PENDING' AND resolved_at IS NULL)
      OR (status <> 'PENDING' AND resolved_at IS NOT NULL))
);
CREATE UNIQUE INDEX ownership_transfer_one_pending
  ON ownership_transfer_requests(household_id) WHERE status = 'PENDING';
CREATE INDEX ownership_transfer_household_idx
  ON ownership_transfer_requests(household_id, created_at DESC);
```

### 5.5 待认领（PENDING_CLAIM）的处理

- 迁移只标记，不授权；`owner_user_id` 保持 NULL。
- 该类家庭的**非房主功能照常**（改名、邀请码、菜谱、库存、计划新增/增份、做饭、食记）。
- 只有“房主专属操作”（减份/删除/锁定/转移/解散/房主退出判定）被拒绝（403 `NOT_HOUSEHOLD_OWNER` 或 409）。
- **不提供客户端自助认领**（PD-2）。首版通过受控的人工流程：`docs/ops/CLAIM_HOUSEHOLD_OWNER.md` 给出审计 SQL（指定 owner、写 source='CLAIM'、记录操作者与时间），由团队在核实身份后执行。是否要服务端管理端点，留待后续版本。
- 当前库无 `PENDING_CLAIM` 家庭，该路径仅作兜底。

### 5.6 一致性与回滚

- 全部为加列/加表，不删除历史数据。
- 迁移前后校验：
  - `SELECT count(*) FROM households WHERE closed_at IS NULL AND owner_user_id IS NULL AND owner_source <> 'PENDING_CLAIM';` → 0
  - `SELECT count(*) FROM households h WHERE h.owner_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.household_id=h.id AND m.user_id=h.owner_user_id AND m.active);` → 0
  - `SELECT count(*) FROM households WHERE closed_at IS NOT NULL AND owner_user_id IS NOT NULL;` → 0
  - `SELECT household_id FROM ownership_transfer_requests WHERE status='PENDING' GROUP BY 1 HAVING count(*)>1;` → 空
- 回滚（文档化、手动）：`DROP TABLE ownership_transfer_requests; ALTER TABLE households DROP CONSTRAINT ...; DROP INDEX ...; ALTER TABLE households DROP COLUMN ...;`

---

## 6. API 设计与影响矩阵

### 6.1 家庭与房主

| 变更 | 说明 |
| --- | --- |
| `Household.ownerUserId: string?` | 新增响应字段；已关闭家庭可为 null |
| `Household.ownerSource: string?` | 新增响应字段，用于“历史推定/待认领/已关闭”提示 |
| `PUT`? → **`POST /households/{h}/owner-transfer-requests`** | body `{targetUserId}`，`Idempotency-Key`，房主发起 → 201 `TransferRequest` |
| `GET /households/{h}/owner-transfer-requests?status=PENDING&page&size` | 当前成员可查看（透明）；默认返回最近请求 |
| `POST /households/{h}/owner-transfer-requests/{id}/accept` | 仅目标成员，`Idempotency-Key` → 200 `Household`（新 owner） |
| `POST /households/{h}/owner-transfer-requests/{id}/reject` | 仅目标成员，`Idempotency-Key` → 200 `TransferRequest` |
| `POST /households/{h}/owner-transfer-requests/{id}/cancel` | 仅当前房主，`Idempotency-Key` → 200 `TransferRequest` |
| `DELETE /households/{h}/members/{userId}` | 语义扩展（见 6.2），路径/参数不变 |
| `POST /households/{h}/dissolve` | 新增；仅房主且唯一活跃成员；`Idempotency-Key` → 204 |

`TransferRequest` schema：`id, householdId, requestedBy, targetUserId, status, createdAt, updatedAt, resolvedAt?, resolvedBy?, version`。

### 6.2 退出 / 解散规则

| 场景 | 结果 |
| --- | --- |
| 普通成员退出 | 204（现状） |
| 房主 + 其他活跃成员 | 409 `OWNER_MUST_TRANSFER`（提示先发起转移） |
| 房主 + 唯一活跃成员（未显式解散） | 409 `OWNER_MUST_DISSOLVE` |
| 房主 + 唯一活跃成员 + `POST dissolve` | 204：`closed_at`、`owner_user_id=NULL`、`owner_source='CLOSED'`、版本+1；成员 inactive；历史保留 |
| 目标成员退出且有待处理请求 | 退出事务内将该请求置 `CANCELLED`（`resolved_by`=退出者） |

### 6.3 错误码（新增）

| 错误码 | HTTP | 用途 |
| --- | --- | --- |
| `NOT_HOUSEHOLD_OWNER` | 403 | 非房主执行房主专属操作（转移发起/取消/解散/3B 的删减） |
| `NOT_TRANSFER_TARGET` | 403 | 非目标成员接受/拒绝 |
| `INVALID_TRANSFER_TARGET` | 400 | 目标非本家庭活跃成员或等于房主 |
| `TRANSFER_ALREADY_PENDING` | 409 | 已有待处理请求（含唯一索引冲突） |
| `TRANSFER_NOT_PENDING` | 409 | 请求已被处理/取消/拒绝，重复接受或竞态失败 |
| `TRANSFER_REQUEST_NOT_FOUND` | 404 | 请求不存在或不属于该家庭 |
| `OWNER_MUST_TRANSFER` | 409 | 房主退出多人家庭 |
| `OWNER_MUST_DISSOLVE` | 409 | 单人房主未走解散流程 |
| `PLAN_PERMISSION_DENIED` | 403 | 3B：成员尝试删减/替换/移动/锁定 |
| `HOUSEHOLD_ALREADY_CLOSED` | 409 | 对已关闭家庭操作（可选复用 NOT_FOUND） |

所有错误沿用 `{code,message,traceId,fieldErrors}`；`issue` 状态的请求不返回 2xx。

### 6.4 幂等与版本

- 发起/接受/拒绝/取消/解散均要求 `Idempotency-Key`；重放返回存储结果（Batch 2A 的 replay 会重新校验成员资格）。
- 接受/拒绝/取消不接受客户端自报状态；服务端按状态机 + 行锁 + 条件更新。
- 家庭版本 `version` 在 owner 变更时 +1；改名等既有逻辑不变。

---

## 7. 服务端修改清单（3A）

| 文件 | 改动 |
| --- | --- |
| `V2026xxxx__household_owner_and_transfers.sql`（新增） | §5.3 + §5.4 全部 DDL/回填/约束/索引 |
| `HouseholdWriteService.createHousehold` | 插入时写 `owner_user_id=actor, owner_source='CREATED', owner_confirmed_at=now()` |
| `HouseholdReadService.getHousehold` | 查询并返回 `owner_user_id, owner_source` |
| `HouseholdController` | `Household` 映射新字段；新增 5 个转移动作 + dissolve |
| `HouseholdOwnershipService`（新增） | `request/accept/reject/cancel/dissolve`，全部事务 + 行锁 + 条件更新 |
| `HouseholdMembershipService.remove` | 房主退出分支；目标退出时取消其待处理请求 |
| `HouseholdErrors` | 新错误码映射 |
| `contracts/openapi.json` | Household 字段、5+1 端点、TransferRequest schema、错误码、幂等头 |
| `contracts/changes/IC-*.md` | 变更记录（字段 + 端点 + 错误码） |
| 生成代码 | 客户端/服务端重新生成 |
| 测试 | §10 全部 |

**不改**：改名（PD-4）、邀请码（PD-5）、成员移除（PD-3）、计划权限（3B）、Android UI（3C）。

---

## 8. Android 范围（3C，供依赖评估）

| 区域 | 改动 |
| --- | --- |
| `api-client` | 重新生成 |
| 角色状态 | 单一来源：`getHousehold().ownerUserId` vs `currentUserId`；随 `household.version` 失效 |
| 家庭页 | 房主徽标；发起转移（选择成员）；待处理请求卡片（目标：接受/拒绝；房主：取消）；解散（单人房主 + 二次确认）；房主退出被 409 时引导 |
| 计划页 | 成员：隐藏锁定/删除/清空/替换；「选菜」改为追加；份数只能 +；房主：完整操作 + 份数 −；403/409 文案区分 |
| `ProfileFragment` | 身份显示（房主/成员） |
| `BdHomeFragment` | 加菜保持追加；403 提示并刷新 |
| 文案 | 中英新增 |

---

## 9. 并发与一致性设计（重点回答 Part 3）

| 问题 | 方案 |
| --- | --- |
| 是否需要新表 | **需要** `ownership_transfer_requests`（状态、目标、审计不可塞进 households） |
| 每家庭最多一条 PENDING | 部分唯一索引 `(household_id) WHERE status='PENDING'`；发起时捕获唯一冲突 → 409 |
| owner 必须是有效成员 | 创建/转移/认领均在同一事务内校验目标 active membership；迁移校验 SQL；不允许写非成员 |
| 旧家庭安全回填 | §5.3 确定性规则；本库 3/3 命中签名；无签名不猜 |
| PENDING_CLAIM 处理 | 不授权、不删数据、非房主功能可用；受控人工认领（§5.5） |
| 接受与取消并发 | 两者都先锁 household 行；再条件更新 `WHERE status='PENDING'`；后者影响 0 行 → 409 `TRANSFER_NOT_PENDING` |
| 两次接受 | 同上，第二次 409；不会重复改 owner |
| 发起者/接收者退出 | 房主在转移完成前不得退出多人家庭；目标退出时事务内取消其 PENDING 请求 |
| 转移后权限立即生效 | 服务端每次请求读 `households.owner_user_id`；无缓存 |
| 事务失败一致性 | 接受在单事务内完成“请求状态 + owner + version”；失败整体回滚；迁移先回填后加约束，失败即中止 |

---

## 10. 测试矩阵

### 10.1 迁移与身份（3A）

1. 创建家庭 → 创建者为 owner，`source=CREATED`。
2. 回填：单人家庭 → `MIGRATION_SIGNATURE` 或 `MIGRATION_SOLE`（按数据）。
3. 回填：多人且唯一签名成员 → 该成员（本库 OurHome）。
4. 回填：多人且无签名 → `PENDING_CLAIM`、owner NULL，且**该家庭改名成功**（验证 OR 约束不阻断更新）。
5. 已关闭家庭 → owner NULL、`CLOSED`。
6. 约束：open + owner NULL + 非 PENDING 的新行被拒；closed 无 owner 允许。
7. 迁移校验查询全部为 0/空。

### 10.2 转移流程

8. 普通成员发起 → 403 `NOT_HOUSEHOLD_OWNER`。
9. 房主向非成员/自己发起 → 400 `INVALID_TRANSFER_TARGET`。
10. 房主向有效成员发起 → 201 PENDING；重复发起 → 409 `TRANSFER_ALREADY_PENDING`。
11. 非目标接受/拒绝 → 403 `NOT_TRANSFER_TARGET`。
12. 目标拒绝 → 200 REJECTED，owner 不变。
13. 房主取消 → 200 CANCELLED，owner 不变。
14. 目标接受 → 200，owner=目标、source=TRANSFER、version+1；旧房主立即 403（3B 校验或 `/me` 状态）。
15. 重复接受 → 409 `TRANSFER_NOT_PENDING`，owner 不变。
16. 并发接受+取消（两线程）→ 恰好一个成功，另一个 409；无“双房主/无房主”。
17. 目标退出 → 其 PENDING 请求自动 CANCELLED。
18. 房主发起后尝试退出多人家庭 → 409 `OWNER_MUST_TRANSFER`。
19. 单人房主退出 → 409 `OWNER_MUST_DISSOLVE`；dissolve → 204，closed、owner NULL、邀请码失效、数据保留。
20. 非房主 dissolve → 403；多人 dissolve → 409。
21. 幂等：同一 key 重放 accept/dissolve → 存储结果，不重复变更。

### 10.3 回归（3A）

22. 改名：任意成员仍可（含 PENDING_CLAIM 家庭）。
23. 邀请码：任意成员仍可读/创建。
24. 自助退出（普通成员）仍 204。
25. 历史数据条数不变（家庭/成员/菜谱/计划/库存/做饭/食记）。

### 10.4 3B / 3C（后续批次）

3B：新增/增份允许；减份/删除/清空/替换/移动/锁定 403；批量混合 403 整批回滚；锁槽 409；并发 409；绕过 UI 直接请求 403。
3C：入口可见性、403/409 文案、转移后降级、横竖屏/手机布局、既有流程回归。

---

## 11. 工作量与交付影响

| 批次 | 内容 | 估算 |
| --- | --- | --- |
| 3A | 迁移 + 回填 + owner 字段 + 转移表/5 动作 + 退出/解散 + 契约 + 测试 | **4–5 人日**（单阶段方案为 1.5，双阶段明显增加） |
| 3B | diff 引擎 + 3 个写接口 + 403 + 契约 + 测试 | 2–3 人日 |
| 3C | 生成客户端 + 角色状态 + 计划页 + 家庭页（转移/解散）+ 文案 + 手工矩阵 | 3–4 人日 |
| 合计 | | **9–12 人日** |

剩余时间：距 12/07 约 10 天。若只有 1 名主开发全职，3A+3B+3C 全做风险高。

**必须完成（否则规则不成立）**
- 3A 全部（迁移、owner、转移、退出/解散、契约、测试）
- 3B 全部（服务端强制；否则权限只是 UI）
- 3C 最小集：角色状态、计划页入口门控、转移发起/接受/拒绝/取消、解散确认、403/409 文案

**可延期（不影响规则成立）**
- 计划页份数 +/− 的精细 UI（可先用“添加”与锁定/删减门控；份数可在后续补）
- 历史 owner 变更审计表（现有字段 + 转移请求表已能追溯）
- 转移请求过期/提醒、通知、邮件
- 房主徽标视觉精修、计划移动 UI（服务端 3B 已覆盖判定）
- 若时间不足，建议推迟 Batch 5（公共菜谱进家庭计划）或 1/14 天生成，优先保证金规则闭环。

**对原有计划的影响**：Batch 3 由 1 天变为 3 个批次约 2 周；需要从“候选池/生成”等 P1 功能中挪出 2–3 天，或压缩手工回归。

---

## 12. 需要你进一步决定的问题

1. **待处理转移请求的可见范围**：所有活跃成员可见（建议），还是仅房主与目标？
2. **目标退出时自动取消请求**：是否接受“自动置 CANCELLED、resolved_by=退出者”（建议是）？
3. **PENDING_CLAIM 的认领方式**：3A 仅提供受控人工 SQL 流程（建议），还是本期就要服务端管理端点？
4. **推定房主是否标记“未确认”**：迁移得到的 owner 设置 `owner_confirmed_at=NULL`，客户端显示“历史推定房主”，但权限完整（建议）；是否需要强制确认后才能删减计划？
5. **接受/拒绝/取消是否统一要求 Idempotency-Key**（建议是）；是否接受新增 6 个转移相关错误码。
6. **房主发起转移后能否改名/邀请**：建议允许（PD-4/5 不受影响）。
7. **错误码命名**：`TRANSFER_ALREADY_PENDING` / `TRANSFER_NOT_PENDING` / `TRANSFER_REQUEST_NOT_FOUND` / `NOT_TRANSFER_TARGET` / `INVALID_TRANSFER_TARGET` 是否可用。

---

## 13. 3A 验收标准（编码完成时）

- 迁移脚本在现有库执行成功，输出 3 个家庭的 owner 与来源，全部为 `MIGRATION_SIGNATURE`；校验查询全 0/空。
- 创建家庭即 owner；`getHousehold` 返回 `ownerUserId/ownerSource`。
- 转移 5 个动作 + 退出/解散规则全部通过 §10.1/10.2 测试（含并发）。
- 契约生成一致（`checkContracts`）、服务端全量测试通过。
- 未删除任何家庭/成员/历史数据。
- 不改计划权限（3B）与 Android UI（3C）。
