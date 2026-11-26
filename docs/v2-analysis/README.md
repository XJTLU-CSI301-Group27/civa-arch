# Civa V2 · 现状分析与实施计划（Phase 1–5）

本目录是**只读分析产物**，不含任何代码改动。目的：在动代码之前，把"现在是什么、V2 要什么、差在哪、改哪里、按什么顺序改"全部落到可核对的文件上。

## 文档索引与阅读顺序

| 文件 | 内容 | 适合谁读 |
| --- | --- | --- |
| [PHASE1_PROJECT_UNDERSTANDING.md](PHASE1_PROJECT_UNDERSTANDING.md) | 技术栈、模块、页面职责、数据库、网络、业务逻辑、UI 系统、测试现状、功能状态总表、文档漂移 | 全体；新人接手先读 |
| [PHASE2_REQUIREMENTS_ANALYSIS.md](PHASE2_REQUIREMENTS_ANALYSIS.md) | V2 需求分类（已确认/建议/待确认/约束/未来）+ 需求 ID + 五个重点变化的解读 + 文档冲突 | 产品/组长 |
| [PHASE3_GAP_ANALYSIS.md](PHASE3_GAP_ANALYSIS.md) | 24 项已实现（不要重做）、20 项不符、17 项部分、12 项缺失、13 项待确认；含 12 条跨模块流程检查 | 开发 + 评审 |
| [PHASE4_IMPACT_ANALYSIS.md](PHASE4_IMPACT_ANALYSIS.md) | 8 个变更簇对 UI/Fragment/Service/DB/契约/权限/同步/历史/测试的影响；迁移方案；不要碰的清单 | 开发 + 契约负责人 |
| [PHASE5_IMPLEMENTATION_ROADMAP.md](PHASE5_IMPLEMENTATION_ROADMAP.md) | 30 个任务卡（目标/改动/文件/依赖/风险/测试标准/完成条件）、8 个批次、QA 映射、时间可行性与三种范围、6 个待确认决策 | 组长 + 全体 |

需求原件副本：[`../CSI301_Civa_项目推进方案_V2_整合版.md`](../CSI301_Civa_项目推进方案_V2_整合版.md)（与工作区根目录原件 SHA-256 一致）。

## 一页结论

1. **工程基础好**：Java 17 + XML Views + 契约优先 + PostgreSQL，模块边界与架构测试齐全，211 个测试，库存匹配与做饭幂等领域质量高于一般课程项目。
2. **V2 的核心差距不在"缺页面"，而在三条业务链**：
   - **家庭权限**：系统里没有"房主"，任何成员都能清空/覆盖家庭计划（P0）。
   - **做饭/食记边界**：食记"在家吃"直接扣库存；客户端做饭幂等键每次新生成（P0）。
   - **库存感知**：服务端匹配能力完备但客户端零调用；家庭计划候选只有家庭菜谱，公共菜谱几乎进不来（P1）。
3. **不建议重写任何东西**：三张菜谱表并存是既有事实，V2 也明确不强制合并；建议只做加法（1 个迁移 + 1–2 个 IC）。
4. **时间紧**：距 12/07 约 10 天，全部 P0+P1 需 20–24 人日。建议按 **M 范围**（约 18–21 人日）执行，把"1/14 天自动生成"作为可裁剪项。

## 关键事实（避免被旧文档误导）

| 说法 | 实际 |
| --- | --- |
| "有房主/OWNER" | ❌ 2026-11-25 的迁移把全部 OWNER 降级为 MEMBER，代码里无任何角色鉴权 |
| "个人计划在服务端" | ❌ 个人「今天」计划是客户端本地 JSON 草稿 |
| "家庭计划可以选公共菜谱" | ❌ 三层阻止（候选池 / 服务端校验 / `plan_items` 外键） |
| "库存可做优先已实现" | ❌ 服务端有 `matchRecipes`，客户端零调用 |
| "做饭幂等" | 🟡 服务端正确，客户端每次点击换新 key |
| "个人食记不扣库存" | ❌ "在家吃"路径直接 `changeInventory(CONSUME)` |

## 如何核对本文的结论

所有结论都带 `path:line`。建议抽查以下 8 处（覆盖最关键的判断）：

```powershell
# 1. 没有房主：迁移把所有 OWNER 降级
Get-Content server/src/main/resources/db/migration/V20261125210000__equal_members_and_permanent_invitations.sql | Select-Object -First 6
# 2. 计划写只校验成员，且整槽删除重写
Select-String -Path server/src/main/java/edu/xjtlu/civa/server/plan/PlanningService.java -Pattern 'requireMember|DELETE FROM plan_items'
# 3. 个人计划是本地草稿
Get-Content feature-bd/src/main/java/edu/xjtlu/civa/feature/bd/BdTodayPlanReader.java | Select-Object -First 15
# 4. 家庭计划候选只有家庭菜谱
Select-String -Path feature-bd/src/main/java/edu/xjtlu/civa/feature/bd/BdHomeFragment.java -Pattern 'listRecipes'
# 5. 匹配只支持家庭菜谱
Select-String -Path server/src/main/java/edu/xjtlu/civa/server/inventory/InventoryMatchingService.java -Pattern 'household_id=\?'
# 6. 客户端零调用 matchRecipes
Select-String -Path feature-*/src/main/java/**/*.java -Pattern 'matchRecipes'   # 无结果
# 7. 食记"在家吃"直接扣库存
Select-String -Path feature-ac/src/main/java/edu/xjtlu/civa/feature/ac/diary/DiaryFragment.java -Pattern 'ChangeReason.CONSUME'
# 8. 做饭幂等键每次新生成
Select-String -Path feature-ac/src/main/java/edu/xjtlu/civa/feature/ac/diary/DiaryFragment.java, feature-ac/src/main/java/edu/xjtlu/civa/feature/ac/catalog/RecipeCatalogDetailFragment.java -Pattern 'confirmCooking\(home, UUID.randomUUID'
```

## 当前状态

- Phase 1–5 **已完成**（本目录）。
- **未修改任何业务代码**；唯一新增是 V2 需求文档副本 + 本目录 5 份分析文档。
- 下一步：等待用户回答 Phase 5 §5.6 的 6 个决策，然后按 Batch 1 开始实施（Batch 1 不依赖任何决策）。
