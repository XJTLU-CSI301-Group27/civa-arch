---
id: IC-20261121-NUTRITION-01
type: NORMAL
status: VERIFIED
allowedOperations:
  - estimateNutrition
allowedSchemas:
  - NutritionBasis
  - NutritionEstimate
  - NutritionEstimateRequest
allowedSymbols: []
contractDigest: sha256:679c8cecb9253c9c1a9950d85194daefde6391a97b429385f4571904836bb14f
baseSha: ae60b66ad4255663b26c30061766848971caf99e
headSha: 6a03211bea8db95adde6c89de267f5632d21af71
---
# IC-20261121-NUTRITION-01 营养估算改由服务端代理

类型：NORMAL
状态：IMPLEMENTING
提出组及负责人：BD（首页/营养），EF 负责契约决策
提出时间（带时区）：2026-11-21T16:30+08:00
关联 Issue/PR：无 Issue，随 BD 模块化（ADR-004）一起推进
基线 commit / 原始 head commit：ae60b66ad4255663b26c30061766848971caf99e

## 涉及 operationId / schema

- operationId：`estimateNutrition`（`POST /api/v1/nutrition/estimates`）
- schema：`NutritionEstimateRequest`、`NutritionEstimate`、`NutritionBasis`
- 错误语义沿用既有契约：`400 VALIDATION_ERROR`、`401 UNAUTHENTICATED`、
  `429 RATE_LIMITED`（带 `Retry-After`）、`500 INTERNAL_ERROR`、`501 CONTRACT_NOT_IMPLEMENTED`、
  `503 NUTRITION_UNAVAILABLE`（带 `Retry-After`）

## 原需求及复用现有接口的评估

首页的菜是**本机草稿**（`BdDraft`），既不是服务端 `recipes` 行，也没有 `recipeId`；
现有 41 个操作中没有任何一个能对“菜名 + 食材 + 做法”给出营养估算。
原实现由客户端直连第三方 AI 并携带本地 key，release 不可用且绕过契约、限流与审计。

## 旧接口与新接口

- 旧：客户端 `BdNutritionClient` → `core.data.JsonPost` → `api.deepseek.com`（无契约、无缓存、key 在设备）
- 新：客户端 → `POST /api/v1/nutrition/estimates` → 服务端缓存 → 供应商
  - 请求只含菜名/食材/做法，不含用户、家庭、照片；
  - 响应为 per_100g，未知为 `null`，附 `inputHash`、`providerVersion`、`cached`；
  - 服务端以内容哈希做跨用户共享缓存（只存哈希与指标，不存菜名明文）。

## 影响调用方、数据库、缓存及 UI

- 客户端：`feature-bd` 的 `BdNutritionClient` 退化为 DTO 映射，改走 `ApiCalls.enqueue`；
  `BdHost` 增加 `api()`/`calls()`；`ApiProvider` 增加 `nutrition()`；删除 `core.data.JsonPost`。
- 数据库：新增 `nutrition_estimates`（哈希主键 + 指标 + 供应商版本），30 天过期。
- 缓存：服务端共享缓存；客户端仍保留本地指纹缓存用于离线展示。
- UI：营养弹窗的加载/失败提示沿用现有文案；无数据时继续显示“未知”，不填 0。

## 兼容性判断及未验证项

- 兼容：新增端点，不改变既有序号；客户端本地草稿格式不变。
- 未验证：真实供应商的额度与延迟（本地用真实 key 做一次端到端，CI 用 stub）；
  多实例部署的限流需要共享计数器，当前为单实例内存窗口。

## 决定

采用：新增服务端代理端点。决定者：项目负责人（本会话 2026-11-21 明确同意按此方案实施）。

## 迁移与回退计划

- 迁移：客户端切到新端点后删除设备端 key（`civa-deepseek.json`）与 `JsonPost`。
- 回退：保留 `civa.nutrition.provider=stub` 可让功能在无供应商时按“暂不可用/示例”降级；
  端点本身可回退为 501，不影响其余功能。

## 实际命令、测试报告、失败与修复记录

- `validateContract` / `generateClient` / `generateServerApi` / `checkContracts`
- `server: unitTest`（含 `NutritionDigestTest`）、`server: test`（`NutritionHttpIntegrationTest`，stub provider）
- `app: assembleDebug testDebugUnitTest lint`
