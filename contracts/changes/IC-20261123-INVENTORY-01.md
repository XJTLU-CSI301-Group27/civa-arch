---
id: IC-20261123-INVENTORY-01
type: NORMAL
status: IMPLEMENTING
allowedOperations:
  - createIngredient
allowedSchemas:
  - CreateIngredientRequest
allowedSymbols: []
contractDigest: sha256:c248d07404c3a1ec7f763d4894e8315843837de85aa83617acf2a69b7fec0e2c
baseSha: ae60b66ad4255663b26c30061766848971caf99e
headSha: null
---
# IC-20261123-INVENTORY-01 库存：任意英文食材名称录入

类型：NORMAL
状态：IMPLEMENTING
提出组及负责人：C（库存），EF 负责契约决策
提出时间（带时区）：2026-11-23T19:05+08:00
关联 Issue/PR：C 组交付 PR #13（旧仓库），本次为合入 civa-arch 的契约增量
基线 commit：df51c9ab0bf5c7fc54a073d7b83f20a18cfdd1d1

## 涉及 operationId / schema

- operationId：`createIngredient`（`POST /api/v1/ingredients`）
- schema：`CreateIngredientRequest`（name 1–120，unit 复用既有 `Unit` 枚举）
- 复用既有 `Ingredient` 响应与请求错误语义：400/401/403/409/429/500/501

## 原需求及复用现有接口的评估

家庭菜谱的食材必须引用标准食材 ID（用于库存匹配与做饭扣减），但标准食材字典不可能覆盖用户输入的所有名称。
C 组验证了只保留 `listIngredients` + 从字典中选择会阻塞英文名称录入。因此需要一个显式、幂等的
"登记标准食材"入口，而不是伪造 ID 或借用位置字段。

## 旧接口与新接口

- 旧：只有 `listIngredients`（只读），无创建入口；客户端只能从字典选择。
- 新：`createIngredient` 复用 `Ingredient` 模型与 `Idempotency-Key` 约定；同名同单位幂等返回既有记录，
  同名不同单位返回 `409 UNIT_CONFLICT`；不迁移历史库存。

## 影响调用方、数据库、缓存及 UI

- 客户端：`feature-ac` 库存添加流程与后续菜谱编辑器的食材选择器可现场登记新食材；
- 数据库：复用既有 `ingredients`、`ingredient_aliases`，无结构变更；
- 缓存/UI：仅新增一个调用路径，不影响既有页面数据。

## 兼容性判断及未验证项

- 兼容：纯新增 operation，不改动任何既有 operation/schema；其余 41 个基础操作与主体完全一致（已逐 schema 比对）。
- 未验证：真实设备上的英文输入体验（C 组已有 47 项报告，合入后由主体 CI 复验）。

## 决定

采用：新增 `createIngredient`。决定者：项目负责人（2026-11-23 明确确认保留）。

## 迁移与回退计划

- 迁移：无数据迁移；客户端按需调用。
- 回退：删除该 operation 即可回到 42 个操作，不影响既有库存读写。

## 实际命令、测试报告、失败与修复记录

- 契约：`validateContract` / `generateClient` / `generateServerApi` / `checkContracts`
- 服务端：`server:unitTest`（IngredientNamesTest、InventoryRulesTest、InventoryCalculatorTest）、`server:test`（InventoryHttpIntegrationTest）
- Android：`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
