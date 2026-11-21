# Civa 架构基线（冻结）

本仓库是 **CSI301 Civa 项目的架构基线**，冻结于 2026-11-21，对应提交
`4dadf62 chore: let any team member approve controlled paths (#7)`。
后续的视觉系统与 BD 功能迁移在各自 ADR 中记录，主线可以直接推进。

## 为什么单独建这个仓库

原仓库 `XJTLU-CSI301-Group27/civa-android` 里合并的 BD 实现（PR #10）与 BD 组实际的
最新版本不一致，属于错误内容。团队决定：

- 不再在原仓库上继续协作与合并；
- 组员成果改为**压缩包交接**，由本地整合；
- 因此本仓库**不设置 PR 审核、不设置必需状态检查**，main 可直接推送。

## 里面有什么

| 内容 | 说明 |
| --- | --- |
| `app/` | 装配与导航壳：`CivaApplication`、`AppContainer`、`MainActivity`、`AppNavigator`、`PlaceholderFragment`；debug 有独立的 `BdPreviewActivity` 预览入口 |
| `api-client/` | 由 `contracts/openapi.json` 生成的 Retrofit 客户端（唯一网络契约产物） |
| `core-ui/` | **共享视觉系统**：`civa_*` 色板、`civa_kuaile` 字体、`CivaText/CivaWidget/CivaShape` 样式 token，以及 `PaperBackdropView`、`BakeryCatStage`、`DishPlateView`、`DishActionOverlay`、`DishPopoverPlacement`、`ElasticChoiceView`、`PressBounce`、`CivaWindow`、`ImageSources` 等 UI 基元；另有 `Route`、`NavHost`、`ApiError`、`QuantityInputs` |
| `core-data/` | `SessionStore`、`ApiCalls`（缓存与 401/吊销处理）、`PendingWriteStore`、`ApiProvider`、`AuthRepository`、`JsonPost`（非契约 HTTPS 通道）、Room |
| `feature-ef/` | 登录与家庭：`LoginFragment`、`HouseholdFragment` + Host 接口 |
| `feature-bd/` | 首页/排餐/菜品：`BdHomeFragment`、`BdHomeViewModel`、`BdFlowDialog`、`BdDraft`/`BdDraftStore`、`BdRecipeCatalog`、`BdPhotoFiles`、`BdNutrition*`；312 条菜谱与 149 张图在模块 assets 内 |
| `feature-ac/` | 仍为空模块，等 AC 组按 `core-ui` 基元填充 |
| `server/` | Spring Boot：`auth/`、`household/`、`application/`、`config/`、`web/`（其余端点 501 占位） |
| `contracts/` | OpenAPI 唯一来源、生成器配置、fixtures、IC 变更记录、`public-surface.draft.json` |
| `scripts/` | 契约门禁与 `preflight` |
| `docs/` | 规格、ADR（001–004）、交接、验证日志 |

## 门禁（实际会跑的）

| 门禁 | 位置 | 拦什么 |
| --- | --- | --- |
| 契约校验与生成一致 | `validateContract` / `generateClient` / `generateServerApi` / `checkContracts` | OpenAPI 结构、生成产物、契约索引 |
| 契约变更授权 | `scripts/contract_guard.py` | 未登记 IC 记录的契约改动 |
| feature 不得直连传输 | `FeatureDependencyArchTest` | feature 包里的 `okhttp3`/`java.net`；并断言被测包非空 |
| 模块边界 | `ModuleBoundaryArchTest` | feature→app、feature 互相依赖、core-ui→core-data/feature、core-data→feature |
| 资源归属 | `ResourceOwnershipTest` | feature 引用 `app_*`、feature 重定义 `civa_*`/`Civa*`、app 直用 feature 资源 |
| 公共面 | `PublicSurfaceArchTest` + `PublicSurfaceCompletenessTest` | 已登记符号缺失，以及**新 public 类型未登记** |
| 资源命名 | 各模块 `lint { error 'ResourceName' }` | `app_`/`civa_`/`bd_`/`ef_` 前缀违规 |
| CI | `.github/workflows/ci.yml` 的 `CI gate` | 契约、Android（assemble + testDebugUnitTest + lint）、服务端集成测试 |

## 分支与标签

- `main` —— 上述基线 + 已合入的视觉系统与 BD 模块化；**可直接推送**，无审核门禁；
- `refactor/core-ui-and-bd-modules` —— 2026-11-21 的两项工作：视觉系统与 UI 基元进
  `core-ui`（ADR-003）、BD 业务进 `feature-bd`（ADR-004）；
- `refactor/arch-alignment` —— 2026-11-20 的四项"架构归位"，未合入基线，需要时再合；
- 标签 `pr10-backup-20261120` —— 被回退的 BD 实现，仅作历史留存。

## 本地起后端（一键）

`gradle/local-stack.gradle` 注册了 `local` 任务组，Android Studio 里直接用 Run 配置或
Gradle 工具窗运行即可，不需要把 `server/` 作为第二个 Gradle 工程挂进 IDE：

| 任务 | 作用 |
| --- | --- |
| `dbUp` / `dbPs` / `dbLogs` / `dbDown` | Docker 里的 PostgreSQL 开发库 |
| `serverJar` | 用 `server/` 自己的 wrapper 构建 `civa-server.jar` |
| `serverRun` | 用 `.env` 里的 DB 配置运行该 jar（阻塞，Run 窗口停止） |
| `serverStop` | 杀掉占用 `SERVER_PORT` 的残留进程（Windows） |

`.env` 只在任务执行时读取，CI 不需要 `.env` 也不会执行这些任务。完整说明见
`docs/LOCAL_DEV_STACK.md`。

## 组员代码怎么进这个仓库

1. 解压对方的压缩包到临时目录（不要覆盖整个仓库）；
2. 按模块把文件放进对应目录：`feature-ac/`、`feature-bd/`、`server/`；
3. 先跑一次完整校验确认没破坏契约与门禁：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
.\gradlew.bat :core-ui:testDebugUnitTest :feature-bd:testDebugUnitTest :feature-ef:testDebugUnitTest
cd server; .\gradlew.bat test bootJar
```

4. 确认后再提交。**不要**把对方的 `build/`、`.gradle/`、`.idea/`、`local.properties`
或任何密钥一并提交。

## 本地离线备份

除了远端，本机保留了两份独立备份（路径仅对本机有效）：

- `D:\XJTLU\CSI301\backup\civa-full-20261121.bundle` —— 全量 git（所有分支、标签、历史），
  可用 `git clone civa-full-20261121.bundle civa` 还原；
- `D:\XJTLU\CSI301\backup\civa-arch-baseline-20261121.zip` —— 架构基线的纯源码快照。
