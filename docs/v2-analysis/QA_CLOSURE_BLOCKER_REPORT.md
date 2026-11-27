# Civa V2 QA Closure — Release Blocker Resolution Report

日期：2026-11-27（Asia/Shanghai）
基线：`34839134c5be69ee15a67685626ebd3a7bc3e239`
范围：QA Closure；未进入 Phase C / Final QA-2。

## A. 执行摘要

- 当前 HEAD（本轮开始）：`3483913`
- 本轮业务代码修改：无；只提交测试报告。
- Android：**236 tests / 0 failures**
- Server：**105 tests / 0 failures**
- lint / assembleDebug / checkContracts：通过（前序代码状态）
- 实际执行：
  - 做饭正常扣减、重复确认、库存不足：HTTP/DB PASS
  - MealEntry 创建/修改/删除：HTTP/DB PASS
  - Owner/Member 邀请、加入、计划权限：HTTP/DB PASS（邀请配置修复后）
  - 360dp ImportReviewDialog：基础 PASS（前序截图）
  - 六类损坏 Pending：自动化 PASS
- 仍未执行：
  - 手工/导入 Pending 冲突两方向
  - 损坏 Pending UI 路径
  - 横屏/英文/字体/深色/长列表
  - 做饭/食记/计划 UI 冒烟
  - 历史 trackStock=false 样本

## B. 测试组结果

### A. 做饭 / 库存 / MealEntry

#### A1 正常做饭 — PASS（HTTP/DB）

测试家庭：DemoHome `d8d967df-edc7-4559-aa2e-4518cf3b30f1`

准备：
- inventory batch `b6e76441-f647-451e-9f33-8f351cd1ff8c`
- 鸡蛋 5 COUNT
- 家庭菜谱 `c05ac525-a77f-4212-9b6a-8e978761896c`
  - 鸡蛋 2 COUNT
  - 食盐 适量（RECIPE_ONLY）
  - 份数 2

执行：
- cooking preview servings=1 → allocation 鸡蛋 1
- cooking confirm

证据：
- 批次 5→4
- CookingEvent `73b5d1ac-ce20-4e3e-b32b-b782e2d88ed0`
- InventoryMovement `a58e85b1-...`，before=5 / after=4 / COOK / 关联 event
- 只有鸡蛋 movement；食盐没有 movement

PASS。

#### A2 重复确认 — PASS（HTTP/DB）

- 同一 Idempotency-Key + 相同 payload 再次提交。
- 返回同一 event `73b5d1ac-...`（HTTP 201 replay）。
- DB cooking_events 1 条，movement 1 条。
- 无二次扣减。

PASS。

#### A3 不足库存 — PASS（HTTP/DB）

- 当前鸡蛋 4，servings=5 preview。
- shortages：required 5 / available 4 / MISSING。
- confirm 被拒绝：HTTP 400 `INVALID_RANGE`。
- 批次保持 4，event 保持 1，无部分扣减。

PASS（按现有规则拒绝；错误码并非 409）。

#### A4 MealEntry — PASS（HTTP/DB）

- 创建 `340c09dd-b431-4b81-ac83-4580c38350c5`
- 修改 name，version 1→2
- `expectedVersion=2` 删除成功
- 创建/修改/删除前后：鸡蛋批次保持 4，movements 保持 1，库存未减少

PASS。

未做：做饭/MealEntry 的真实 Android UI 操作。

### B. 家庭权限 — PASS（HTTP/DB）

邀请 500 根因已查明并解决。

- 真实邀请：
  - Owner uilocal01 对 DemoHome 创建邀请，HTTP 201
  - code 返回
  - 新账号 `qa_member_...` 注册并接受邀请，HTTP 201
  - Membership 创建：role=MEMBER，active=true
- 计划权限：
  - Owner 创建 slot：HTTP 200
  - Member 增加份数：HTTP 200
  - Member 减少份数：HTTP 403 `PLAN_PERMISSION_DENIED`
  - Member 删除菜品：HTTP 403
  - Member 锁定：HTTP 403
  - Owner 删除：HTTP 200
  - Owner 锁定/解锁：HTTP 200

DB/响应证据：
- Member 被拒绝后 slot version 未变化；
- Owner 操作成功并 version 增加；
- 未发生越权 DB 修改。

**UI 抽样：NOT RUN。** 本轮为 HTTP/DB 权限验证。

### C. Pending 写入安全

- 六类 codec：PASS（自动化）
- 手工 UNKNOWN → 导入：**NOT RUN**
- 导入 UNKNOWN → 手工：**NOT RUN**
- 损坏 Pending UI：**NOT RUN**
- 跨账号 Pending：前轮真机 PASS
- 同账号家庭切换：**NOT APPLICABLE**（产品无任意切换入口）
- 旧回调污染：**NOT RUN**

未执行两方向冲突，因此：
- 未验证导入不会覆盖手工 pending；
- 未验证手工不会覆盖导入 pending；
- 未验证冲突后原 key/payload 能恢复；
- 未验证最终只创建一份菜谱。

### D. UI 兼容

| 项 | 结果 |
| --- | --- |
| 360dp ImportReviewDialog | PASS（前序截图，文案修复前） |
| `3483913` 文案修复后复验 | **NOT RUN** |
| 平板竖屏 | PASS（前序） |
| 平板横屏 | **NOT RUN** |
| 英文 | **NOT RUN** |
| 字体放大 | **NOT RUN** |
| 深色 | **NOT RUN** |
| 长食材列表 | **NOT RUN** |
| C→TRACKED 替换提醒真机 | **NOT RUN** |

360dp 此前截图：`review/rg2-360.png`；当时“请选择食..”截断，已改“选择食材”但未重新构建复验。

### 历史兼容

- OurHome 6 道菜谱存在。
- 当前 8 道 recipes 的 `recipe_only_ingredients` 均为 `[]`。
- 当前 `trackStock=false` 样本为 0，无法执行历史 false 的真机回归。
- 普通详情/空 recipe-only/家庭→个人快照由既有测试和前轮证据覆盖，本轮未重新 UI 抽样。
- **历史 false：NOT RUN（无样本）**。

## C. 邀请 API 500 根因与修复

### 根因

- 不是 DemoHome 缺少 permanent invitation：invitations 表存在 permanent 行。
- 真实原因：服务端启动参数使用 `--civa.media.root=server/build/media`，但应用配置读取的是 `civa.media-dir`。
- 结果：
  - 媒体目录使用 `build/media`
  - 邀请密钥使用默认 `build/private-media/.private/invitation-receipt.key`
  - 数据库中的 invitation 是由 `build/media` 密钥封存的
  - 解密时 `AEADBadTagException: Tag mismatch` → HTTP 500

### 解决

- 用正确配置重启：
  - `--civa.media-dir=server/build/media`
- 重新请求：
  - Invitation HTTP 201
  - code 可读取
  - Member join HTTP 201
  - Membership 生效

### 性质

- **测试/启动配置问题**，不是邀请业务代码缺陷。
- 启动脚本 `scripts/start-local.ps1`/`start-native-local.ps1` 使用 `CIVA_MEDIA_DIR`，是正确方向；本次手动启动命令使用了错误的旧 property。
- 不需要修改数据库/Contract/邀请业务代码。
- 没有手工插入 invitation 记录。

## D. 数据清理与恢复

- DemoHome：0 菜谱 / 0 inventory batches / 0 movements / 0 cooking events / 1 条原有 plan slot。
- OurHome：6 菜谱 / 12 批次 / 8 movements，未变。
- 总 recipes 8 / user_recipes 17 / inventory_batches 12 / inventory_movements 11 / cooking_events 5 / meal_entries 7 / plan_slots 5。
- 删除测试 recipe/batch/movement/event/meal、RG3/RG4 personal snapshots、测试成员与 QA 账号。
- 测试邀请记录：保留 DemoHome 原有 permanent invitation；不修改真实数据。
- 本地 pending_writes=0。
- 设备 Pinyin IME 恢复，ADBKeyboard 卸载；屏幕恢复 1200×1920/320dpi。

## E. 最终发布裁决

### 1. 可复现 P0 缺陷

- 无新增已确认 P0 代码缺陷。
- 既有 fingerprint P0 已修复。

### 2. 关键安全验证缺口

- **存在**：
  - 手工/导入 Pending 两方向冲突；
  - 损坏 Pending UI；
  - Pending 身份/家庭切换边界。

### 3. 核心 P1 缺陷

- 360dp 文案修复未复验；
- C→TRACKED 替换提醒未真机验证；
- 横屏/英文/字体/深色/长列表未验证；
- 做饭/MealEntry/计划 UI 未抽样。

### 4. 阻塞条件

- Pending 冲突与损坏 Pending UI 未执行；
- 做饭/食记/计划 UI 未执行；
- 设备矩阵不完整。

### 5. Final QA-2

- **不能开始。**

### 6. Phase C1

- **不适合实施。**

### 7. 最小必要工作

1. 完成 Pending 两方向冲突真机验证。
2. 完成一条损坏 Pending UI 路径。
3. 复验 360dp 修复、C→TRACKED 提醒。
4. 横屏/英文/字体/深色/长列表冒烟。
5. 做饭、MealEntry、计划 UI 各一次。
6. 历史 trackStock=false 合法样本回归。

### 8. 最终结论

**NO-GO。**

已通过做饭/库存/MealEntry HTTP/DB、邀请与 Owner/Member 权限、六类 codec 和 360dp 基础布局；但关键 Pending 冲突、损坏 UI 与设备 UI 矩阵仍未完成。NOT RUN 不当作缺陷，但也不能作为发布通过证据。

## F. Phase C1/C2 建议（不实施）

### C1

前置：
- 家庭范围食材字段/目录；
- 家庭内名称/别名唯一约束；
- 规范单位不可变；
- 权限与搜索隔离；
- 数据库迁移/OpenAPI scope。

最小范围：家庭自定义名称 + 规范单位 + 家庭别名 + 选择/编辑，不含图片；约 3–5 人日。

### C2

- 复用 HOUSEHOLD Media，但需确认家庭归属、成员移除后权限、孤儿清理、大小限制；
- 在 C1 稳定后实施；约 2–3 人日。
- 当前不建议启动。

停止于 QA Closure，不进入 Phase C / Final QA-2。
