# Civa 架构交接

2026-11-09 本地环境、启动排障和设备验证补充见 [本地调试与运行交接](LOCAL_RUN_HANDOFF.md)。下文保留 2026-10-30 的架构历史记录；其中工具链位置及无模拟器的状态须以新文档为准。

更新时间：2026-10-30。以实际工作区和最新测试结果为准；本文件是交接记录，不代表阶段验收已经通过。

## 目标与工作区

用户要求先阅读 `docs` 中的文档，再按照架构规格在当前目录创建项目并开始实现；持续目标是完成 `PROJECT_DOC.md` 所述阶段 A–C 和真实验证。仓库为 `https://github.com/XJTLU-CSI301-Group27/civa-android`，工作目录为 `E:\XJTLU\大四\CSI301\CW\code\civa`。当前本地分支 `bootstrap/architecture`，HEAD 为 `69e91a03a422c87208f786c94826755456290e4e`。

**工作树有大量未提交内容，几乎所有新增项目文件尚未跟踪；只有两个 `gradlew` 的可执行模式已暂存。** 不要重置、覆盖或批量暂存后假定它们都是自己写的。用户授权本地开发，尚未授权推送或创建 PR。Git 如遇 safe.directory，可对单次命令使用 `git -c safe.directory='E:/XJTLU/大四/CSI301/CW/code/civa' ...`。

继续前阅读根 `AGENTS.md`、`PROJECT_DOC.md`、`OPEN_QUESTIONS.md`、`docs/DATA_CONTRACT.md`、`docs/INTEGRATION_RULES.md` 和 `docs/verification/BOOTSTRAP.md`。后者按时间记录真实命令、结果和限制；后面的记录会更新前面的状态。

## 已有实现

- Android 多模块 Gradle 工程、生成的 Retrofit API、统一网络/会话层、真实登录注册界面与应用容器。debug 默认 `http://10.0.2.2:8080/`；release 需要显式 HTTPS `CIVA_API_BASE_URL`。
- 服务端独立 Gradle 工程、PostgreSQL/Flyway 迁移、认证、家庭创建/加入/查询、幂等执行器和成员权限检查。邀请创建与成员移除仍为 501。
- 候选 `contracts/openapi.json` 是两端生成输入，尚未获得团队批准。未实现业务接口明确返回 501；`README.md` 有当前清单。路由与候选契约的实际比对测试已执行。
- Room 本地表和按账号隔离的 GET 缓存骨架已接入；`PendingWriteStore` 已实现安全创建/账号隔离/恢复/清理，家庭创建/加入界面与进程死亡恢复已接入。不要将仅编译的 Android 设备测试视为已运行。

## 当前进行中的工作

已将 `DtoChecks` 从只检查非空字段改为按候选 OpenAPI schema 验证缓存 DTO 的字符串长度、日期、整数、数组和引用；`core-data/build.gradle` 将同一契约复制为 Android 资源，`CacheStore` 读取该资源。首次测试发现 Gson 将 `uniqueItems` 反序列化为 Set 时会丢失重复项，已改为分别测试原始 JSON 与生成 DTO 的行为；修正后 `:core-data:testDebugUnitTest :core-data:assembleDebugAndroidTest assembleDebug lint --no-daemon` 通过，core-data 测试 15 项、失败和跳过均为 0，日志 `build/cache-schema-validation-rerun.log`。检查 debug APK 内契约资源的 SHA-256 与仓库 `contracts/openapi.json` 一致。设备测试包仅编译，未在设备运行。

**已完成：** 缓存 GET 在 Retrofit 反序列化前校验原始 JSON（缺失 required nullable 字段、Set 原始重复项、非规范 UUID、未知字段保留）已修复断网误判：`DISCONNECT_AT_START` 实际返回 200 且空 body，原校验把空 body 的 EOFException 归为 `INVALID_RESPONSE`；现空 body 视为传输失败映射 `NETWORK_ERROR`，非空但非法 JSON/schema 仍为 `INVALID_RESPONSE`。已删除 `ApiCalls.java`、`RawResponseValidation.java`、`RealApiProvider.java` 的 4 处临时 debug 打印。`:core-data:testDebugUnitTest :core-data:assembleDebugAndroidTest assembleDebug lint --no-daemon` 返回 0，core-data 单测 16 项、失败和跳过均为 0。

## 未完成的关键任务（按负责人分派）

### 已完成（EF，本会话，均真实验证）

- 缓存 GET 原始响应校验（空 body→`NETWORK_ERROR`）、缓存清理失败重试、`PendingWriteStore` 与家庭创建/加入界面及进程死亡恢复、`contract_guard.py`（授权门禁+digest 归一化+draft+Git SHA）、`checkContracts`、`preflight`、`.github/workflows/ci.yml`、fixtures、ArchUnit 依赖/公共面字节码检查（含完整签名比对：返回类型+参数类型+字段类型）、`contracts/changes/README.md`、public-surface 登记。
- 真实检验已通过：根 `validateContract generateClient generateServerApi checkContracts` + `assembleDebug testDebugUnitTest lint`（22 单测）+ server `test bootJar` 连真实 PostgreSQL（16 项）+ scripts（17 项）。详见 `docs/verification/BOOTSTRAP.md`。

### 需 AC 组（feature-ac + server recipe/inventory）

按 `PROJECT_DOC.md` §5/§9，菜谱与库存的**真实读写页面与业务**由 AC 交付，当前均为 501 占位。A（菜谱）：`listRecipes/createRecipe/getRecipe/updateRecipe/setRecipeArchived/listWishlist/addWishlist/removeWishlist` + 菜谱列表/详情/编辑/收藏/做过展示页。C（库存）：`listIngredients/listInventoryBatches/createInventoryBatch/updateInventoryBatch/changeInventory/listInventoryMovements/matchRecipes` + 冰箱页。跨组仅通过 `ApiProvider` + `ApiCalls`，幂等写用 `PendingWriteStore`，不得直接调 OkHttp/retrofit（已有 ArchUnit 门禁）。

### 需 BD 组（feature-bd + server planning/records）

按 §5/§9，排餐与食记由 BD 交付，当前 501 占位。B（排餐）：`listPlanSlots/replacePlanSlot/setPlanSlotLock/commitPlan` + 排餐页。D（做饭/食记）：`previewCooking/confirmCooking/listCookingEvents/listMealEntries/createMealEntry/updateMealEntry/deleteMealEntry/getMealSummary` + 做饭确认/食记/回顾页。confirmCooking 涉及跨表原子扣库存，须走服务端 `CookingCoordinator`，客户端只展示预览结果。

### 需团队决策（阻塞，实现不得自行决定）

- 邀请码幂等重放冲突：数据库只存哈希 vs 重放需返回原始码（`OPEN_QUESTIONS.md`）。`createInvitation` 与 `removeMember` 现仍 501。
- `OPEN_QUESTIONS.md` 待决：契约基线审批、主题命名（`Theme.Civa` vs `CivaTheme.Civa`）、`INVALID_RESPONSE` 错误码、家庭 OWNER 数量。
- `public-surface.json` 转正式需 owner 审批（签名比对已做到返回类型+参数类型+字段类型简单名匹配）。
- 前端视觉样式：已「留白」为中性占位，待团队定配色/字体/主题。

### 需设备/远端（本机无法完成）

- Room 设备测试与端到端双账号流程：本机无 AVD/设备、SDK 未装模拟器（已核实），`assembleDebugAndroidTest` 只证明编译。
- CI 远端触发、PR/分支保护验证、CODEOWNERS（缺真实 GitHub 账号，规范禁止把「EF」当有效 owner）。

不要把 501、占位页、假数据、未运行测试或未获授权的契约说成完成。

## 本机验证环境

项目路径包含中文。Android Gradle 使用 `android.overridePathCheck=true`，部分 Java 测试还需要已存在的临时 junction `%TEMP%\civa-worktree` 指向项目目录。JDK 17 在该 junction 的 `build/jdk17/jdk-17.0.21.0.2+1`，Android SDK 在 `build/android-sdk`，Gradle 缓存在 `%TEMP%\civa-gradle-8.13`；这些均为本机忽略目录，不是可交付依赖。执行前先检查路径真实存在。

```powershell
$taskAlias = Join-Path $env:TEMP 'civa-worktree'
$env:JAVA_HOME = Join-Path $taskAlias 'build/jdk17/jdk-17.0.21.0.2+1'
$env:GRADLE_USER_HOME = Join-Path $env:TEMP 'civa-gradle-8.13'
$env:ANDROID_HOME = Join-Path $taskAlias 'build/android-sdk'
Set-Location -LiteralPath $taskAlias
.\gradlew.bat :core-data:testDebugUnitTest :core-data:assembleDebugAndroidTest assembleDebug lint --no-daemon
```

当前没有检测到设备或 AVD，`assembleDebugAndroidTest` 只证明设备测试包能编译。后端集成测试必须使用隔离的 `_test` PostgreSQL 库，项目已有测试库校验；本机隔离集群位于忽略的 `build/pg-test-data`，监听 `127.0.0.1:55432`，数据库 `civa_test`，**用户名 `civa`**，凭据文件 `build/pg-test-password.txt` 不得打印或提交。测试前确认集群状态，完成后停止；禁止删除数据库或改写已应用的迁移。历史成功命令、真实测试范围和日志见 `docs/verification/BOOTSTRAP.md`。

本机 Python 曾为 3.10.9，不满足规格要求的 Python 3.11+；不要把有限的脚本单测称作正式 preflight。Room 编译器首次遭遇 Maven TLS 问题，构建期 Guava 固定版本的本机解决办法和限制见 `docs/decisions/ADR-001-baseline.md`。

## 安全与交付

不要输出或提交访问令牌、数据库密码、邀请码明文及用户私有数据。不要运行有可能清理用户已有数据库的数据迁移/清库命令；仅使用已验证的隔离测试库及明确的测试数据。新 AI 应先核对工作树、运行中的构建和最新日志，再继续；不要把 501、假数据、未运行测试或未获授权的契约说成完成。

## EF bakery UI update — 2026-11-25

Login/register/household, cold-launch chef cat, profile/avatar editor, recipe close
and home meow update: read [EF_BAKERY_UI_HANDOFF.md](EF_BAKERY_UI_HANDOFF.md),
[file changes](EF_BAKERY_UI_CHANGELOG.md) and
[verification evidence](EF_BAKERY_UI_VERIFICATION.md) before integration.
The assigned downloaded local project was updated; this does not represent a
GitHub push or integration with latest main.


## Latest visual revision — 2026-11-25, round 2

Current cat artwork, lighter 26/74 welcome scene, globe language button and
user-supplied video intro supersede the first visual pass. Start with
[EF_CAT_REFRESH_HANDOFF.md](EF_CAT_REFRESH_HANDOFF.md),
[CAT_REFRESH_CHANGELOG.md](CAT_REFRESH_CHANGELOG.md) and
[CAT_REFRESH_VERIFICATION.md](CAT_REFRESH_VERIFICATION.md).

## Latest spacing update — 2026-11-25, round 3

Login greeting/form position, Today title/cat order and size, and Recipes cat
attachment are updated. Read [EF_LAYOUT_POLISH.md](EF_LAYOUT_POLISH.md) for exact
scope, five changed files, screenshots and measured spacing before integration.


## Latest login / Today / green intro update — 2026-11-25, round 4

Login form is further upward, Today title is 24dp closer to the cat, and the newly supplied green-source animation replaces the old intro. Read [EF_GREEN_INTRO_HANDOFF.md](EF_GREEN_INTRO_HANDOFF.md) before integration; it includes exact source hashes, media processing, validation and retained local data. This is a local update, not a GitHub push or main integration.


## Latest recipe header / household flow update — 2026-11-25

Read [EF_FAMILY_CATALOG_HANDOFF.md](EF_FAMILY_CATALOG_HANDOFF.md) before integration. Create Recipe moves to the title row; Favorites clears the cat; household onboarding can be skipped to Profile; ordinary members can actually leave and rejoin while owners retain their existing restriction. Includes the first-login return-stack fix, additive core-data helper, existing removeMember server implementation, full file audit and isolated test evidence. No GitHub push/main integration is implied.

## Latest empty states and invitation follow-up (2026-11-25)

Read [EF_EMPTY_INVITATION_HANDOFF.md](EF_EMPTY_INVITATION_HANDOFF.md) before integration. It supersedes prior center Add Recipe and first-login Skip-to-Profile rules. First setup now returns Today; Profile entry returns Profile for every action. User entered household UUID, not an invitation; add explicit guidance and a tested owner-generation candidate. Encrypted idempotency receipt status: 用户已确认采用；小组整合时仍需按团队流程审阅。
Local production app/backend updated; existing local files retained; all final checks linked. No GitHub push, branch/main integration or team contract approval is implied by this downloaded working copy. Historical 501 notes above are historical: removeMember was completed in the preceding handoff, createInvitation is locally implemented in this candidate.

## Latest shared-family policy (2026-11-25)

Current handoff: [EF_SHARED_FAMILY_HANDOFF.md](EF_SHARED_FAMILY_HANDOFF.md). User explicitly removed owner privileges and requested permanent reusable household codes. Creator can leave, last member closes identity; everyone may read/copy the fixed code; joined vs unjoined settings differ; Profile shows actual household name/ID. This supersedes owner/one-use statements in historical entries above.
Bring migration V20261125210000, new GET and nullable Invitation.expiresAt with both generated clients; preserve private key. Local full tests and normal migration passed; official app/backend updated, data preserved. No GitHub push/latest-main integration/team contract freeze claimed. IC is documented for team integration review.

## Latest household name editor (2026-11-25)

Read [EF_FAMILY_NAME_HANDOFF.md](EF_FAMILY_NAME_HANDOFF.md): joined-only content top aligned; 修改家庭名 input + fixed invitation/copy + save + leave, shared name actually saved with version checking. New additive PUT renameHousehold reuses DTO and both generators; no migration/dependency/host signature changes. Prior equal-member/permanent-code policy stays. Full tests/UI and data-preserving install verified locally; no GitHub push/main integration/team acceptance claimed.

## 小米平板USB连接（2026-11-25）

真机调试入口为根目录 start-civa-usb.bat；先保持电脑后端运行，再连接授权的USB设备。使用localhost构建与adb reverse，不修改默认模拟器地址。已在MI_PAD_4_PLUS验证注册/登录服务可达，未代用户登录。记录见 [EF_USB_TABLET_HANDOFF.md](EF_USB_TABLET_HANDOFF.md)。


## 最新平板界面适配（2026-11-26）

请先阅读 [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md)。登录顶角恢复；所有主页面固定底部导航；三餐始终竖排。4个实现文件（其中2个宽屏覆盖删除），无接口/后端变更；实测API27平板、宽/窄尺寸、加菜前后边界，124项测试及lint通过。正式App已保留资料覆盖安装。旧ZIP未重打包；无GitHub推送/main整合。


## 启动键盘后续修复（2026-11-26）

登录容器接收初始焦点、MainActivity默认隐藏输入法；打开/重开不自动弹键盘，点击用户名/密码仍正常显示。已在原小米平板保留资料覆盖安装并验证；本轮8项登录模块测试及app lint通过。完整2文件审计与结果见 [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md#启动不自动弹键盘2026-11-26后续修复)。


## 今天三餐与家庭解耦（2026-11-26）

阅读 [BD_PERSONAL_PLAN_HANDOFF.md](BD_PERSONAL_PLAN_HANDOFF.md)：按用户要求改为个人账号scope；同账号创建/退出/加入家庭不再清空今天三餐。兼容旧数据和照片、一次迁移防复活；平板3份旧文件归属确认后恢复。全套Android测试和隔离家庭切换通过，正式App保留资料更新。旧ZIP未重打包，无GitHub推送/main整合。


## 真机登录地址防复发（2026-11-26）

实际安装包曾含模拟器10.0.2.2，单独恢复USB转发不足。已核对并覆盖正确127.0.0.1包，用户确认能登录。新增本机debug地址键civa.debugApiBaseUrl与USB启动器持久化，CLI优先、release显式HTTPS规则不变。2个实现文件/实际配置测试及旧包哈希见 [EF_USB_TABLET_HANDOFF.md](EF_USB_TABLET_HANDOFF.md)。


## 今天标题字号稳定（2026-11-26）

仅改BdHomeLayout：关闭wrap_content标题的框架autosize，从父布局预算重新拟合38sp/紧凑25sp目标，避免短暂缩小后不恢复。实测4轮切页、2轮短窗口恢复、窄尺寸和键盘收起，正常标题均为158px；正式App保留资料更新并核对实际包为真机地址。完整审计见 [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md)。


## 完整小组源码包（2026-11-26）

本次完整交接入口：[GROUP_HANDOFF_20261126.md](GROUP_HANDOFF_20261126.md)。涵盖当前平板适配、个人排餐解耦、登录连接与家庭规则；私有小组ZIP按用户授权附后端DeepSeek配置及新环境Docker密码。组员先阅读规则和各模块交接，勿提交带Key的.env。包含完整源码和脚本，排除缓存、个人配置、数据库及私钥；ZIP逐文件校验，不代表GitHub推送/main整合。
