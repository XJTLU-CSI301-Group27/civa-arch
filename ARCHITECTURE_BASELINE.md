# Civa 架构基线（冻结）

本仓库是 **CSI301 Civa 项目的架构基线**，冻结于 2026-11-21，对应提交
`4dadf62 chore: let any team member approve controlled paths (#7)`。

## 为什么单独建这个仓库

原仓库 `XJTLU-CSI301-Group27/civa-android` 里合并的 BD 实现（PR #10）与 BD 组实际的最新版本
不一致，属于错误内容。团队决定：

- 不再在原仓库上继续协作与合并；
- 组员成果改为**压缩包交接**，由本地整合；
- 因此本仓库**不设置 PR 审核、不设置必需状态检查**，main 可直接推送。

## 里面有什么

| 内容 | 说明 |
| --- | --- |
| `app/` | 装配与导航壳：`CivaApplication`、`AppContainer`、`MainActivity`、`AppNavigator` |
| `api-client/` | 由 `contracts/openapi.json` 生成的 Retrofit 客户端（唯一网络契约产物） |
| `core-ui/` | 主题 `CivaTheme.Civa`、`Route`、`NavHost`、`ApiError`、`QuantityInputs` |
| `core-data/` | `SessionStore`、`ApiCalls`（含缓存与 401 处理）、`PendingWriteStore`、`ApiProvider`、Room |
| `feature-ef/` | 登录与家庭（唯一有真实页面的 feature） |
| `feature-ac/` | 空模块，等 AC 组按 `CivaScreen` 模式填充 |
| `feature-bd/` | 空模块，等 BD 组按同一模式填充 |
| `server/` | Spring Boot：`auth/`、`household/`、`application/`、`config/`、`web/`（501 占位） |
| `contracts/` | OpenAPI 唯一来源、生成器配置、fixtures、IC 变更记录 |
| `scripts/` | 契约门禁与 `preflight` |
| `docs/` | 规格、ADR、交接、验证日志 |

## 分支与标签

- `main` —— 上述架构基线，**可直接推送**，无审核门禁；
- `refactor/arch-alignment` —— 2026-11-20 做的四项"架构归位"（共享页面骨架、幂等写协议上移、
  服务端解耦、Host 收敛），已完成并通过 CI，但未合入基线；需要时再合；
- 标签 `pr10-backup-20261120` —— 被回退的 BD 实现，仅作历史留存。

## 组员代码怎么进这个仓库

1. 解压对方的压缩包到临时目录（不要覆盖整个仓库）；
2. 按模块把文件放进对应目录：`feature-ac/`、`feature-bd/`、`server/`；
3. 先跑一次构建确认没破坏契约：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
cd server; .\gradlew.bat test bootJar
```

4. 确认后再提交。**不要**把对方的 `build/`、`.gradle/`、`.idea/`、`local.properties`
或任何密钥一并提交。

## 本地离线备份

除了远端，本机保留了两份独立备份（路径仅对本机有效）：

- `D:\XJTLU\CSI301\backup\civa-full-20261121.bundle` —— 全量 git（所有分支、标签、历史），
  可用 `git clone civa-full-20261121.bundle civa` 还原；
- `D:\XJTLU\CSI301\backup\civa-arch-baseline-20261121.zip` —— 架构基线的纯源码快照。
