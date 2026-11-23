---
id: IC-20261123-FAVORITES-01
type: NORMAL
status: IMPLEMENTING
allowedOperations:
  - listRecipeFavorites
  - getRecipeFavorite
  - addRecipeFavorite
  - removeRecipeFavorite
allowedSchemas:
  - RecipeFavorite
  - RecipeFavoritePage
  - RecipeFavoriteKind
allowedSymbols: []
contractDigest: sha256:9862c3ec053f102d804012b2ba4f01be148a82bd5f890e12cb7b8cee641d1258
baseSha: ae60b66
headSha: null
---
# IC-20261123-FAVORITES-01 统一"想吃"收藏

类型：NORMAL
状态：IMPLEMENTING
提出组及负责人：A（菜谱），EF 负责契约决策
提出时间（带时区）：2026-11-23T21:10+08:00
基线 commit：ae60b66（分支基线；本次契约基于 0594b5d 增量）

## 涉及 operationId / schema

- `GET /api/v1/me/recipe-favorites`：`listRecipeFavorites`（可按 kind 过滤）
- `GET/PUT/DELETE /api/v1/me/recipe-favorites/{kind}/{recipeId}`：`getRecipeFavorite`、
  `addRecipeFavorite`、`removeRecipeFavorite`
- schema：`RecipeFavorite`、`RecipeFavoritePage`、`RecipeFavoriteKind`（CATALOG/PERSONAL/HOUSEHOLD）

## 原需求及复用现有接口的评估

现有 `wishlists` 表外键只指向家庭 `recipes(id)`，无法收藏公共目录与私人菜谱；产品明确要求"想吃"
覆盖三层。因此新增统一收藏表（kind + ref_id，无跨表外键），响应内嵌展示所需的名称/emoji/分类，
避免客户端 N+1 请求。

## 旧接口与新接口

- 旧：`/api/v1/me/wishlist` 仅家庭菜谱（继续保留，P3 家庭菜谱接入时迁移）。
- 新：`/me/recipe-favorites` 覆盖三层；同一用户同一目标幂等收藏；列表按收藏时间倒序。

## 影响调用方、数据库、缓存及 UI

- 客户端：菜谱 Tab「想吃」来源、详情页收藏按钮（公共/私人详情）。
- 数据库：新增 `recipe_favorites(user_id, kind, ref_id)`；删除个人菜谱时级联清理其收藏。
- 无缓存失效问题（收藏为个人小数据，直接读写服务端）。

## 兼容性判断及未验证项

- 兼容：纯新增；既有 45 个 operation 未改动。
- 未验证：家庭菜谱收藏（HOUSEHOLD）在 P3 接线后的行为。

## 决定

采用：新增统一收藏。决定者：项目负责人（2026-11-23 计划评审通过 P2）。

## 迁移与回退计划

- 迁移：`V20261123202100__AC_recipe_favorites.sql`；回退删除接口与表，旧 wishlist 不受影响。

## 实际命令、测试报告、失败与修复记录

- `validateContract` / `generateClient` / `generateServerApi` / `checkContracts`
- `server:test`（RecipeFavoriteHttpIntegrationTest）、Android 单测与 lint
