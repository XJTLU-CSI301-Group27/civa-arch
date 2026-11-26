# Batch 3 实施方案：家庭房主身份与家庭计划权限

日期：2026-11-27　基线：`f75b766`（Batch 2C 收尾）
状态：**仅审查与规划，未实施**。实施需拆为 3A / 3B / 3C 分别批准。

---

## 0. 本轮 Part A 结论（Batch 2C 最终验收）

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| 计划菜匹配家庭菜谱时「做饭」进入详情 | ✅ | 临时将 11-26 计划菜改为 `TomatoEgg`（有同名家庭菜谱）→ 点「做饭」→ 详情显示 `ac_detail_name=TomatoEgg`、`ac_detail_cook=确认做饭` |
| 完整做饭确认流程 | ✅ | 详情页「确认做饭」→ 份数对话框 → 确认，服务端返回成功 |
| 库存只扣减一次 | ✅ | 烹饪事件 2→3（+1），库存流水 2→3（+1），批次 200g→0（单条 COOK）；随后用 CORRECT 恢复至 200g |
| 手机尺寸按钮布局 | ✅ | `wm size 720x1280`、`wm density 320`（≈360dp）：标签右缘 296、做饭 304-480、记录用餐 488-664、内容右缘 688，无重叠/截断 |
| 既有食记功能 | ✅（只读检查） | 月汇总 350 kcal / 1 条未估算、日汇总、当日记录、做饭历史均正常渲染 |

测试残留（已记录，未清理）：
- 保留 1 条测试烹饪事件（TomatoEgg，今日）及其 COOK + CORRECT 两条库存流水；批次数量已恢复为 200g。
- 设备个人计划草稿已还原；临时 ASCII 草稿与 `/data/local/tmp` 文件已清理。

**Part A 未发现 Batch 2C 缺陷，无需修复。**

---

## 1. 当前权限架构（代码事实）

### 1.1 数据模型

| 表 | 关键列 | 现状 |
| --- | --- | --- |
| `households` | `id, name, zone_id, version, created_at, updated_at, closed_at` | **没有创建者/房主字段** |
| `memberships` | `id, household_id, user_id, role, active, version, created_at` | 迁移 `V20261125210000` 把全部 `OWNER` 改为 `MEMBER`；代码只写 `'MEMBER'` |
| `invitations` | 永久邀请码（hash + sealed_code） | 任意 active 成员可读/可创建（兼容入口） |
| `recipes` / `inventory_*` / `plan_*` / `cooking_events` / `meal_entries` | 以 `household_id` 归属 | 计划写操作只校验 active membership |

约束：`memberships_one_active_household`（一人一个活跃家庭）、`memberships_household_idx`；`Role` 枚举仍存在但 `OWNER` 仅历史兼容、无权限。

### 1.2 家庭流程

| 流程 | 实现 | 特征 |
| --- | --- | --- |
| 创建 | `HouseholdWriteService.createHousehold`：同一幂等事务内插入 household + 成员 `'MEMBER'` + 永久邀请码 | 创建者与 household 同事务写入，`membership.created_at == household.created_at` |
| 加入 | `joinHousehold`：校验永久邀请码、锁定 household、插入 `'MEMBER'` | 需要未关闭家庭；一人一家 |
| 退出 | `HouseholdMembershipService.remove`：仅允许 `actor == target`；锁 household → 锁 membership → 置 inactive；最后一人退出自动 `closed_at` | **任何人都可退出（含创建者）**；无“房主必须转移” |
| 移除他人 | 同上：`actor != target` → 403 `FORBIDDEN` | 现行产品规则“任何成员都不能移除他人” |
| 解散 | 无独立接口，靠“最后一人退出”隐式关闭 | 关闭后邀请码失效、不可复活 |
| 改名 | `HouseholdSettingsService.rename`：任意 active 成员，`expectedVersion` 乐观锁 | 与房主无关 |

### 1.3 计划写接口（全部为家庭维度）

| 接口 | 语义 | 当前授权 |
| --- | --- | --- |
| `PUT /households/{h}/plan-slots/{date}/{meal}` | **整槽替换**（含清空 `items=[]`） | 仅 `requireMember` |
| `PUT /households/{h}/plan-slots/{date}/{meal}/lock` | 锁定/解锁 | 仅 `requireMember` |
| `POST /households/{h}/plan-commits` | 批量提交（≤42 槽，未被客户端调用） | 仅 `requireMember`，幂等 |
| `GET /households/{h}/plan-slots` | 只读 | 仅 `requireMember` |

**关键事实**：客户端“加菜”“选菜”“清空”全部走 `replacePlanSlot`；服务端 `replace` 先 `DELETE FROM plan_items` 再全量插入。因此**任何成员都能删除/减少/替换他人内容**；`commit` 同样如此。锁定不是权限边界（成员可解锁后覆盖）。

### 1.4 客户端权限状态

- `SessionStore` 只有 `userId / householdId / token`；**没有任何角色或房主状态**。
- `PlanFragment`：每行只有「选菜」（替换，含“清空”）+「锁定/解锁」；没有份数增减；无权限判断。
- `BdHomeFragment` 家庭模式「加菜」：读取现有 items 后整体 replace（语义上是追加，但服务端无法区分）。
- `ProfileFragment` / `HouseholdFragment`：显示家庭名、成员、邀请码；退出为自助；无房主标识、无转移/解散。

### 1.5 现有并发与幂等基础（可复用）

- `plan_slots.version` + `expectedVersion` → 409 `VERSION_CONFLICT`；`replace/commit` 在事务内 `FOR UPDATE`。
- 家庭写操作统一先锁 `households` 行（创建/加入/退出/改名/做饭）。
- `IdempotencyExecutor`：plan-commits、cooking、meal entries 均有重放；Batch 2A 已让重放重新校验成员资格。
- `IdempotencyExecutor` 的 replay 回调目前只做 `requireMember`，**不做角色判定**。

---

## 2. 目标权限模型

### 2.1 身份

- `households.owner_user_id`（UUID，FK `users`，可空仅限已关闭家庭）。
- 辅助列：`owner_source`（`CREATED` / `CREATED_AT_SIGNATURE` / `SOLE_MEMBER` / `MIGRATION_EARLIEST` / `TRANSFER` / `CLAIM`）、`owner_assigned_at`、`owner_confirmed_at`。
- 不恢复 `memberships.role='OWNER'`；`Role` 保持历史兼容。

### 2.2 计划操作矩阵

| 操作 | 普通成员 | 房主 | 判定方式 |
| --- | --- | --- | --- |
| 新增菜品（新 recipeId） | ✅ | ✅ | `added` 非空 |
| 增加已有项份数 | ✅ | ✅ | `increased` 非空 |
| 减少已有项份数 | ❌ 403 | ✅ | `decreased` 非空 |
| 删除菜品 | ❌ 403 | ✅ | `removed` 非空 |
| 替换（删 A 加 B，同槽） | ❌ 403 | ✅ | `removed ∩ added` 同槽 |
| 清空（`items=[]`） | ❌ 403 | ✅ | 全部 removed |
| 跨日期/餐次移动 | ❌ 403 | ✅ | 同一 `recipeId` 在槽 A 消失、槽 B 出现 |
| 锁定/解锁 | ❌ 403 | ✅ | 锁接口单独校验 |
| 批量提交/生成 | 仅当整批只含 added/increased | ✅ | 对每个 slot 做同一 diff，任一禁止即整批 403 |

实现要点：**服务端比较写入前后的真实内容**（`PlanDiff`），不信任客户端自报操作类型；`replace` 与 `commit` 共用同一判定函数。锁槽既有的“锁定拒绝替换”保持不变（房主需先解锁）。

### 2.3 家庭身份规则

1. 每个**未关闭**家庭必须有且仅有一名 owner，且必须是 active 成员。
2. 创建者成为 owner。
3. 房主退出（多成员）→ 409 `OWNER_MUST_TRANSFER`；必须先调用转移。
4. 单人家庭房主退出 → 409 `OWNER_MUST_DISSOLVE`；通过独立“解散”接口关闭家庭（明确确认）。
5. 转移：仅当前房主，目标必须是本家庭 active 成员，`expectedVersion` 乐观锁，事务内完成。
6. 解散：仅房主且为唯一 active 成员；置 `closed_at`、`owner_user_id=NULL`，保留全部历史数据。
7. 成员移除他人：**保持现状禁止**（待产品确认是否改为房主可移除，见 §10）。

---

## 3. 数据库迁移方案（最高风险）

### 3.1 真实数据评估（只读探测当前开发库 `civa`）

| 指标 | 值 |
| --- | --- |
| 未关闭家庭 | 3（2 个单人、1 个两人） |
| 已关闭家庭 | 1 |
| 活跃成员 / 非活跃成员 | 4 / 1 |
| `membership.created_at == household.created_at` 的家庭 | **3 / 3**（创建者签名） |
| 同一家庭成员 `created_at` 完全相同（排序歧义） | 0 |
| 未关闭但无活跃成员 | 0 |
| 已关闭但仍有活跃成员 | 0 |
| 最早活跃成员曾创建过家庭菜谱 | 1 / 3（弱证据，仅辅助） |

结论：当前库**全部未关闭家庭都能用“创建时刻签名”确定创建者**，无需猜测；但方案必须对“创建者已退出/签名缺失”的一般情况安全。

### 3.2 迁移规则（确定性，按优先级）

1. 若恰有一名 active 成员满足 `membership.created_at = household.created_at` → 该成员为 owner，`owner_source='CREATED_AT_SIGNATURE'`。
2. 否则若恰有一名 active 成员 → 该成员为 owner，`owner_source='SOLE_MEMBER'`。
3. 否则（多人且无签名）→ **不擅自指定**：`owner_user_id=NULL`、`owner_source='PENDING_CLAIM'`，由后续“认领/人工确认”流程处理（当前库无此情况）。
4. 已关闭家庭 → `owner_user_id=NULL`（不再有管理需求），`owner_source='CLOSED'`。

约束设计：

- `ALTER TABLE households ADD CONSTRAINT households_owner_when_open CHECK (closed_at IS NOT NULL OR owner_user_id IS NOT NULL) NOT VALID;`
  - `NOT VALID` 允许历史 PENDING_CLAIM 行暂存，但**新写入/更新必须满足**；认领后自动满足。
- 服务层不变量 + 迁移后校验查询：
  - `SELECT count(*) FROM households h WHERE h.closed_at IS NULL AND h.owner_user_id IS NULL;`（预期仅 PENDING_CLAIM）
  - `SELECT count(*) FROM households h WHERE h.owner_user_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.household_id=h.id AND m.user_id=h.owner_user_id AND m.active);`（预期 0）
  - `SELECT count(*) FROM households h WHERE h.closed_at IS NOT NULL AND h.owner_user_id IS NOT NULL;`（按规则 4 预期 0）
- 索引：`CREATE INDEX households_owner_idx ON households(owner_user_id);`

### 3.3 迁移 SQL 草案（3A 实施时使用）

```sql
ALTER TABLE households ADD COLUMN owner_user_id UUID REFERENCES users(id);
ALTER TABLE households ADD COLUMN owner_source VARCHAR(24);
ALTER TABLE households ADD COLUMN owner_assigned_at TIMESTAMPTZ;
ALTER TABLE households ADD COLUMN owner_confirmed_at TIMESTAMPTZ;

WITH signature AS (
  SELECT DISTINCT ON (m.household_id) m.household_id, m.user_id
  FROM memberships m JOIN households h ON h.id = m.household_id
  WHERE m.active AND h.closed_at IS NULL AND m.created_at = h.created_at
  ORDER BY m.household_id, m.created_at, m.id
), sole AS (
  SELECT m.household_id, min(m.user_id) AS user_id
  FROM memberships m JOIN households h ON h.id = m.household_id
  WHERE m.active AND h.closed_at IS NULL
  GROUP BY m.household_id HAVING count(*) = 1
)
UPDATE households h SET
  owner_user_id = COALESCE(sig.user_id, sole.user_id),
  owner_source = CASE WHEN sig.user_id IS NOT NULL THEN 'CREATED_AT_SIGNATURE'
                      WHEN sole.user_id IS NOT NULL THEN 'SOLE_MEMBER'
                      ELSE 'PENDING_CLAIM' END,
  owner_assigned_at = CURRENT_TIMESTAMP,
  owner_confirmed_at = CASE WHEN sig.user_id IS NOT NULL THEN CURRENT_TIMESTAMP ELSE NULL END
FROM (SELECT h2.id, sig2.user_id, sole2.user_id
      FROM households h2
      LEFT JOIN signature sig2 ON sig2.household_id = h2.id
      LEFT JOIN sole sole2 ON sole2.household_id = h2.id
      WHERE h2.closed_at IS NULL) src
LEFT JOIN signature sig ON sig.household_id = h.id
LEFT JOIN sole ON sole.household_id = h.id
WHERE h.id = src.id;

UPDATE households SET owner_source = 'CLOSED' WHERE closed_at IS NOT NULL;

CREATE INDEX households_owner_idx ON households(owner_user_id);
ALTER TABLE households ADD CONSTRAINT households_owner_when_open
  CHECK (closed_at IS NOT NULL OR owner_user_id IS NOT NULL) NOT VALID;
```

> 实施时需用真实数据先跑 `SELECT` 预演，确认每个家庭得到的 owner 与预期一致；迁移脚本只做加法，不删除任何家庭/成员/历史数据。

### 3.4 边界与并发

| 场景 | 方案 |
| --- | --- |
| 空家庭（0 active） | 迁移前查得 0；若出现，`PENDING_CLAIM`，不关闭、不删除；由人工处理 |
| 单人家庭 | `SOLE_MEMBER`，owner=该成员 |
| 多人且创建者仍在 | `CREATED_AT_SIGNATURE`（本库 3/3） |
| 创建者已退出 | 无签名 → `PENDING_CLAIM`（不擅自指定）；历史 inactive 成员保留 |
| 转移原子性 | 事务内：锁 household → 校验 actor=owner 且版本一致 → 校验目标 active 成员 → `UPDATE households SET owner_user_id=?, owner_source='TRANSFER', owner_confirmed_at=now(), version=version+1 WHERE id=? AND version=?` |
| 转移 vs 退出并发 | 两者都先锁 household；转移后旧房主退出需再转移/解散；旧房主先退出成功则转移时 actor 已非成员 → 404/403 |
| 转移 vs 加入并发 | 加入也锁 household；目标成员校验在锁内完成 |
| 房主被移除 | 现状不允许移除他人，故不存在；若未来开放，需禁止移除 owner |
| 解散并发 | 解散锁 household + 校验唯一 active 成员；与加入串行化（加入锁同一行） |
| owner 指向非成员 | 服务层校验 + 迁移校验查询；不允许通过转移写入非成员 |

### 3.5 回滚

- Flyway 前向迁移；回滚脚本（文档化，不自动执行）：`ALTER TABLE households DROP CONSTRAINT households_owner_when_open; DROP INDEX households_owner_idx; ALTER TABLE households DROP COLUMN owner_confirmed_at, DROP COLUMN owner_assigned_at, DROP COLUMN owner_source, DROP COLUMN owner_user_id;`
- 因只增列，代码回滚到 3A 之前不会破坏数据。

---

## 4. API 影响矩阵

| 变更 | 类型 | 影响 |
| --- | --- | --- |
| `Household.ownerUserId: string?` | 响应新增字段 | 生成客户端/服务端模型重编；旧客户端忽略未知字段 |
| `Household.ownerSource: string?` | 响应新增字段 | 客户端用于“历史推定/待确认”提示 |
| `PUT /households/{id}/owner` | **新端点** | body `{userId, expectedVersion}` → `Household`；仅房主 |
| `POST /households/{id}/dissolve` | **新端点** | `Idempotency-Key`，无 body → 204；仅房主且唯一成员 |
| `DELETE /households/{id}/members/{userId}` | 语义扩展 | 房主在多人家庭退出 → 409 `OWNER_MUST_TRANSFER`；单人 → 409 `OWNER_MUST_DISSOLVE` |
| plan `replace`/`commit`/`lock` | 授权收紧 | 新增 403 `PLAN_PERMISSION_DENIED` |
| `GET /households/{id}` / `members` / invitations | 不变 | — |

Contracts 改动集中在 1 个 IC（3A 的 owner 字段 + 3B 的 403 码可合并登记或分两个 IC）。

---

## 5. 需要修改的服务端入口

| 文件 | 改动 |
| --- | --- |
| `V2026xxxx__household_owner.sql`（新增） | 4 列 + 回填 + 索引 + CHECK NOT VALID |
| `HouseholdWriteService.createHousehold` | 插入 household 时写 `owner_user_id=actor, owner_source='CREATED'` |
| `HouseholdReadService.getHousehold` | 查询/返回 `owner_user_id, owner_source` |
| `HouseholdController.getHousehold/rename/create` | DTO 映射新字段 |
| `HouseholdOwnershipService`（新增） | `transfer(actor, home, target, expectedVersion)`、`dissolve(actor, home, key, digest)` |
| `HouseholdMembershipService.remove` | 房主退出分支（多成员/单人） |
| `HouseholdController` | 两个新端点 |
| `HouseholdErrors` | `OWNER_MUST_TRANSFER`(409)、`OWNER_MUST_DISSOLVE`(409)、`NOT_HOUSEHOLD_OWNER`(403)、`INVALID_OWNER_TARGET`(400) |
| `PlanningService.replace/commit/lock` | 接入 `PlanPermissions` |
| `PlanPermissions`（新增，纯函数） | 输入：旧 items/新 items/槽坐标/actor/owner；输出：允许或违规操作集 |
| `PlansErrors` | 403 `PLAN_PERMISSION_DENIED` |
| `contracts/openapi.json` | Household 字段、2 个端点、403 码、错误枚举 |
| 测试（新增） | 迁移校验、转移/解散/退出并发、权限矩阵、diff 12 组合、重放 |

**不改**：`HouseholdSettingsService`（改名仍任意成员）、`HouseholdInvitationService`（邀请码仍全员可读）、`InventoryService`、recipe/media/nutrition。

---

## 6. Android 客户端变更范围（3C）

| 区域 | 改动 |
| --- | --- |
| `api-client` | 重新生成（owner 字段 + 2 端点 + 错误码） |
| 权限状态 | 单一来源：`HouseholdRole`（`getHousehold().ownerUserId` 与 `sessions.currentUserId()` 比较）；随 `household.version` 失效；放 `AppContainer`/`SessionStore`，页面不得各自判断 |
| `PlanFragment` | 成员：隐藏锁定/删除/清空/替换；「选菜」改为「添加」（append 到现有 items）；份数只能 +；房主：完整操作 + 份数 −。403 显示“仅房主可操作”，409 保持版本冲突提示 |
| `PlanFragment` 份数 | 当前无份数 UI，需增加 +/−（成员仅 +），走 `replacePlanSlot`（新增/增加语义） |
| `HouseholdFragment` / `HouseholdViewModel` | 显示房主徽标；“转移房主”（选择 active 成员 + expectedVersion）；“解散家庭”（单人房主，二次确认）；退出被 409 时提示先转移/解散 |
| `ProfileFragment` | 显示当前身份（房主/成员），不重复实现判定 |
| `BdHomeFragment` | 家庭加菜保持 append；403 时提示并刷新 |
| 文案 | 新增权限/转移/解散/待确认文案（中英） |

---

## 7. 兼容性与安全风险

| 风险 | 等级 | 应对 |
| --- | --- | --- |
| 历史房主误判 | 高 | 仅用 `created_at` 签名；无签名不擅自指定（PENDING_CLAIM）；迁移前预演 SQL + 人工核对 |
| 迁移后 owner 非成员 | 高 | 服务校验 + 迁移后 SQL 断言 + 集成测试 |
| 成员通过 `replace`/`commit`/清空绕过 | 高 | 服务端 diff 判定（3B），不依赖 UI |
| 房主退出造成无主家庭 | 高 | 409 强制转移/解散 + CHECK |
| 并发转移/退出 | 中 | household 行锁 + version |
| 客户端旧版本 | 中 | 服务端强制；旧客户端会收到 403，需提示刷新/升级 |
| 邀请码仍全员可读 | 中 | 与 V2“房主管理成员”存在差异，列为待确认 |
| 改名仍任意成员 | 中 | 与 V2 家庭管理表述有差异，列为待确认 |
| `commit` 客户端未使用 | 低 | 3B 仍需覆盖，防止未来接入绕过 |

---

## 8. 自动化测试矩阵

### 8.1 3A（迁移与身份）

1. 迁移后：未关闭家庭 owner 非空（本库 3/3）；owner 是 active 成员；已关闭家庭 owner 为空。
2. 创建家庭：owner=创建者、`owner_source=CREATED`。
3. 转移：房主→成员成功，version+1；非房主转移 403；目标非成员 400；旧版本 409。
4. 转移后旧房主再转移/改计划 → 403。
5. 房主退出（多人）→ 409 `OWNER_MUST_TRANSFER`，成员/家庭数据不变。
6. 单人房主退出 → 409 `OWNER_MUST_DISSOLVE`。
7. 解散：房主单人 → 204，`closed_at` 非空、owner 空、邀请码失效、历史数据保留。
8. 非房主解散 → 403；多人家庭解散 → 409。
9. 并发：两设备同时转移 → 一成功一 409；转移与退出并发 → 无无主家庭。
10. 幂等：解散重放返回同一结果。

### 8.2 3B（计划权限）

1. 成员新增菜品 → 200；成员增加份数 → 200。
2. 成员减少份数/删除/清空/替换 → 403，且 DB 未改变。
3. 成员跨餐次移动（commit）→ 403。
4. 房主执行上述全部 → 200。
5. 成员批量提交仅含新增/增份 → 200；混入删除 → 403 整批回滚。
6. 锁定：成员 403；房主 200；锁槽替换仍 409 `PLAN_SLOT_LOCKED`。
7. 并发：两成员同时加菜（不同 recipe）→ 都成功或 409 冲突，不静默覆盖；两成员同时增份 → 版本冲突提示。
8. 重放：成员旧 key 重放不产生新写；被移除成员重放 404。
9. 直接构造未授权请求（绕过 UI）→ 403。

### 8.3 3C（客户端）

1. 成员计划页无锁定/删除/清空/替换入口；可加菜、可增份。
2. 房主全部入口可见且可用。
3. 403 → “仅房主可操作”；409 → 刷新提示。
4. 转移房主后旧房主界面立即降级（刷新后）。
5. 房主退出 → 引导转移/解散。
6. 横竖屏/平板与手机布局不丢状态。
7. 既有食记/冰箱/菜谱流程回归。

---

## 9. 分批实施计划

### Batch 3A：房主身份与安全迁移

- 迁移 + 回填 + CHECK + 索引
- 创建写 owner；`getHousehold` 返回 owner
- 转移、解散、房主退出规则
- 契约更新（IC）+ 生成代码
- 测试：§8.1
- 验收：迁移预演报告（每个家庭 owner 与来源）、无数据删除、服务端测试全绿、契约一致
- **不**改计划权限；此时成员仍可删减计划（已知风险，3B 紧接着修）

### Batch 3B：服务端计划权限

- `PlanPermissions` diff 引擎（纯函数 + 12 组合单测）
- `replace`/`commit`/`lock` 接入；403 `PLAN_PERMISSION_DENIED`
- 契约错误码 + IC
- 测试：§8.2
- 验收：权限矩阵全绿；成员无法以任何方式删减；房主不受影响

### Batch 3C：Android 权限适配

- 重新生成客户端；统一角色状态
- 计划页：成员/房主入口差异、份数 +/-、append 语义、错误提示
- 家庭页：房主徽标、转移、解散、退出引导
- 测试：单测（角色判断/按钮可见性纯函数）+ 真机双账号/三账号手工矩阵
- 验收：双账号演示（房主删减成功、成员 403）、旧房主降级、无回归

每批独立提交、独立测试、独立验收。

---

## 10. 需用户确认的决策（3A 前）

1. **历史房主回填规则**：是否接受 §3.2 的优先级（签名 → 单人 → PENDING_CLAIM）？当前库 3/3 命中签名。
2. **PENDING_CLAIM 的兜底**：无签名多人家庭是“保持无房主并提示认领”，还是“确定性指定最早成员并标记待确认”？
3. **成员移除他人**：V2 表述为“房主管理家庭成员”，现行产品为“任何人不能移除他人”。是否在 3A 一并改为“房主可移除成员”？本方案默认**不改**。
4. **改名权限**：是否仍任意成员可改名，还是收归房主？
5. **邀请码可见性**：是否仍全员可读/可创建？
6. **房主转移是否需要对方接受**（当前设计为房主单方面指定）。
7. **错误码**：新增 `PLAN_PERMISSION_DENIED` / `OWNER_MUST_TRANSFER` / `OWNER_MUST_DISSOLVE` / `NOT_HOUSEHOLD_OWNER` 是否可接受（需 IC）。

---

## 11. 与 V2 的对应

- §3.4 房主独有删减权 → 3B（服务端强制）
- §3.4 转移/退出 → 3A + 3C
- §5.1 统一实体 `Membership/Household` → 3A 增字段，不重建表
- §4 客户端权限来自统一状态 → 3C
- §12. 变更记录 → 每批独立提交 + 修改记录
