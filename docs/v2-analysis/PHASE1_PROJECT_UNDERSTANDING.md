# Phase 1 · 现有工程分析（Project Understanding）

> 分析对象：`D:\XJTLU\CSI301\code\civa-main`（下称"工程"）
> 代码基线：分支 `refactor/core-ui-and-bd-modules`，HEAD `be12e95`（2026-11-26），工作区干净
> 分析方式：逐文件阅读 + 调用链核对 + 契约/迁移交叉验证；**未修改任何代码**（本目录只新增文档）
> 判定原则：不以类名/文件名推断功能，所有"已实现"均给出实际调用点

---

## 1.1 版本、仓库与基线

| 项 | 实际值 | 证据 |
| --- | --- | --- |
| 工作工程 | `code\civa-main` | 有 `.git`、`local.properties`、可构建 |
| 分支 / HEAD | `refactor/core-ui-and-bd-modules` / `be12e95` "feat(today): add dishes to the household plan directly from Today" | `git log` |
| 远端 | `arch` → `https://github.com/XJTLU-CSI301-Group27/civa-arch.git`（`origin` 指向旧仓库 `civa-android`，已不用） | `git remote -v` |
| 与远端关系 | `be12e95` 与 `arch/main` 同提交 | `git ls-remote` |
| 未推送分支 | `main` = `246a1a3`（统一选菜弹窗第一版，用户实测有重大 bug，**已回退、禁止推送**）；另有 `backup/unified-picker-246a1a3`、`teammate`、`pre-tablet-merge` | `git branch -vv` |
| 交接快照 | `适配小米平板\civa-arch-main`（2026-11-26 打包，无 `.git`）与当前工程同源 | 目录对比 |
| 需求文档 | V2 已复制到 `docs/CSI301_Civa_项目推进方案_V2_整合版.md`（与根目录原件 SHA-256 一致） | `Get-FileHash` |
| 历史会话分析 | `review\SESSION_STATE.md`、`review\ARCHITECTURE_MAP.md` 等（2026-11-27 00:30 上下文压缩稿），本次已重新按代码核实，不直接采信 | — |

**基线风险**：`main` 分支当前指向有 bug 的 `246a1a3`，任何"从 main 拉分支"的操作都会引入该版本。冻结基线必须显式使用 `be12e95`。

---

## 1.2 技术栈、构建与依赖

| 层 | 实现 | 证据 |
| --- | --- | --- |
| 语言 | 纯 Java 17（客户端与服务端均为 Java，**无 Kotlin、无 Compose**） | `app/build.gradle:31`、`server/build.gradle:7` |
| Android 构建 | AGP 8.13.2 / Gradle Wrapper 8.13 / compileSdk 36 / minSdk 26 / targetSdk 36 / ViewBinding | `gradle/libs.versions.toml:2`、`app/build.gradle:26-32` |
| UI | XML Views + AppCompat 1.7.1 + Material 1.12.0 + Fragment 1.8.9 + RecyclerView + ConstraintLayout | `gradle/libs.versions.toml:5-13` |
| 网络 | Retrofit 2.11.0 + converter-gson + OkHttp 4.12.0（`retryOnConnectionFailure=false`，超时 10/20/20s） | `libs.versions.toml:14-16`、`core-data/.../RealApiProvider.java:42-46` |
| 本地存储 | Room 2.8.4（`civa-local.db`：`cache_entries`、`pending_writes`）+ SharedPreferences（4 组）+ AtomicFile JSON | `core-data/.../CacheStore.java:22`、`PendingWriteStore.java:27`、`feature-ac/.../CatalogCache.java:12`、`feature-bd/.../BdDraftStore.java:19-25` |
| 后端 | Spring Boot 3.5.16 / Java 17 / JDBC（JdbcTemplate，无 JPA）/ Spring Security / Flyway / PostgreSQL 16 | `server/build.gradle:13-24`、`V1__baseline.sql` |
| 契约 | `contracts/openapi.json`（539 KB）是唯一网络契约源，OpenAPI Generator 7.x 同时生成客户端 Retrofit 与服务端 Spring 接口 | `contracts/toolchain.properties`、`api-client/build.gradle`、`server/build.gradle:67-81` |
| 测试 | JUnit4（Android 本地）+ JUnit5/Spring Boot Test（服务端，真实 PostgreSQL，不用 H2）+ ArchUnit | `app/build.gradle:62-64`、`server/build.gradle:32-47` |
| 架构门禁 | 契约校验与生成一致性、`contract_guard.py` 变更授权、`FeatureDependencyArchTest`、`ModuleBoundaryArchTest`、`ResourceOwnershipTest`、`PublicSurfaceArchTest`、`ResourceName` lint=error | `ARCHITECTURE_BASELINE.md:32-43`、各模块 `build.gradle` |
| CI | 三 job：contracts（Python 自测 + validate/generate/check）、android（assembleDebug + testDebugUnitTest + lint）、server（postgres:16 service + test + bootJar），`CI gate` 汇总 | `.github/workflows/ci.yml` |

**依赖结论**：技术栈稳定、版本全部锁定、无实验性依赖。V2 需求不需要任何新依赖（无需引入图表库、推送库、状态管理框架）。

---

## 1.3 模块划分与依赖边界

```
app/           装配壳：CivaApplication、AppContainer、MainActivity、AppNavigator、PlaceholderFragment
api-client/    OpenAPI 生成的 Retrofit API + DTO（纯 Java，无 Android 依赖）
core-ui/       视觉系统基元 + Route/NavHost/ApiError/QuantityInputs/LocalePrefs（不依赖 data/feature）
core-data/     SessionStore、ApiCalls（缓存/401/代数）、PendingWriteStore、ApiProvider、AuthRepository、Room
feature-ac/    菜谱(公共/我的/家庭/想吃)、冰箱、食记、计划页、菜谱编辑器
feature-bd/    「今天」页（个人草稿 + 家庭计划视图）、加菜弹窗、营养、草稿存储
feature-ef/    登录/注册、家庭、资料、偏好、语言
server/        独立 Gradle 工程：auth/household/profile/recipe/inventory/plan/record/media/nutrition/application
contracts/     openapi.json、生成配置、IC 变更记录、fixtures、public-surface.draft.json
```

- 依赖方向由 `ModuleBoundaryArchTest` 强制：feature 只能依赖 core-ui/core-data/api-client；core-ui 不依赖 data/feature；core-data 不依赖 feature。
- feature 之间不能互相依赖，因此 **Today（bd）与 计划页（ac）之间没有共享 ViewModel**，只能各自读同一份服务端数据。
- 跨 feature 通信靠 `AcHost`/`BdHost`/`InventoryHost` 宿主接口，由 `MainActivity` 实现。

---

## 1.4 导航与页面职责

**导航实现**：单 Activity（`MainActivity`）+ Fragment；`AppNavigator` 按 `Route` 枚举切换；底部 `BottomNavigationView`，可用宽度 ≥600dp 时切换为 `NavigationRailView`；无 Navigation Component、无 Deep Link（Manifest 只有 MAIN/LAUNCHER）。

| Route | 实际实现 | 备注 |
| --- | --- | --- |
| `TODAY` | `feature-bd/BdHomeFragment` | 个人草稿 + 家庭计划双模式 |
| `RECIPES` | `feature-ac/catalog/RecipeCatalogFragment` | 4 个来源 chip：公共/我的/家庭/想吃 |
| `RECIPE_DETAIL` | `RecipeCatalogDetailFragment` | 按 `p:`/`h:` 前缀区分个人/家庭，无前缀=公共 |
| `RECIPE_EDIT` | `PersonalRecipeEditorFragment` / `HouseholdRecipeEditorFragment` | 前缀 `p:`/`h:`，新建家庭菜谱用哨兵 `hnew` |
| `INVENTORY` | `feature-ac/inventory/InventoryFragment` | 单一家庭库存入口 |
| `DIARY` | `feature-ac/diary/DiaryFragment` | 月历 + 当日详情 + 做饭历史 |
| `PLAN` | `feature-ac/plan/PlanFragment` | 14 天家庭计划网格（个人模式只读） |
| `PROFILE` / `PREFERENCES` / `HOUSEHOLD` / `LOGIN` | `feature-ef/*` | — |
| `COOKING_CONFIRM` / `MEAL_EDIT` / `INVENTORY_EDIT` | **`PlaceholderFragment`（死路由）** | 已定义但无 navigate 调用 |

**关键职责事实**：
- 「今天」页同时承担**个人用餐计划**与**家庭计划查看/加菜**，两者数据源完全不同（本地 JSON vs 服务端 `plan-slots`）。
- 计划页的"个人"模式**只读**渲染本地草稿；"家庭"模式才是可编辑的 14 天网格。二者默认模式不同（Today 默认 PERSONAL、Plan 默认 HOUSEHOLD），且互不同步。
- 详情页的关闭按钮是 `navigate(Route.RECIPES)` 而非 `popBackStack()`，会重建列表 Fragment 并清空返回栈。

---

## 1.5 数据库设计（实际表结构与实体关系）

PostgreSQL + Flyway，共 9 个迁移（`server/src/main/resources/db/migration/`）：

| 迁移 | 作用 |
| --- | --- |
| `V1__baseline.sql` | 全部基础表（254 行） |
| `V20261029143735__EF_allow_zero_recipe_quantity.sql` | 允许 `recipe_ingredients.quantity = 0`（未追踪食材） |
| `V20261121162000__BD_nutrition_estimates.sql` | 营养估算缓存表 |
| `V20261123201000__AC_recipe_catalog.sql` | 公共菜谱目录 `recipe_catalog`（1307 条，只读、无 owner） |
| `V20261123201100__AC_recipe_catalog_seed.sql` | 目录种子数据 |
| `V20261123202000__AC_personal_recipes.sql` | 个人菜谱 `user_recipes`（按 `user_id`，自由文本食材） |
| `V20261123202100__AC_recipe_favorites.sql` | 跨三空间的"想吃" `recipe_favorites(kind, recipe_id)` |
| `V20261123203000__BD_starter_ingredient_dictionary.sql` | 26 条标准食材字典 |
| `V20261125210000__equal_members_and_permanent_invitations.sql` | **家庭规则改为全员平等**：删除 owner 唯一索引、把 `OWNER` 全部降级为 `MEMBER`、加 `closed_at`、永久邀请码 |
| `V20261126213000__profile_images_and_plan_author.sql` | `user_recipes.image_id`、`plan_slots.author_id` |

### 实际业务实体

| 域 | 表 | 归属键 | 关键点 |
| --- | --- | --- | --- |
| 身份 | `users`、`sessions` | `user_id` | 会话仅存 token SHA-256，24h |
| 家庭 | `households`、`memberships`、`invitations` | `household_id` | `memberships.role` 仅 `MEMBER` 实际存在；`active` + 部分唯一索引保证一人一家 |
| 食材 | `ingredients`、`ingredient_aliases` | 全局 | 仅 26 条种子；单位 `G/ML/COUNT` |
| 菜谱（公共） | `recipe_catalog` | 无 | 自由文本 `ingredients` JSONB，`image_key` 为客户端资源键 |
| 菜谱（个人） | `user_recipes` | `user_id` | 自由文本 `ingredients` JSONB，可硬删除，`origin_household_recipe_id` 无 FK |
| 菜谱（家庭） | `recipes` + `recipe_ingredients` | `household_id` | **结构化食材**（`ingredient_id`+`quantity`+`unit`+`track_stock`），才能进入库存匹配 |
| 想吃 | `wishlists`（旧，仅家庭菜谱）、`recipe_favorites`（新，三 scope 多态） | `user_id` | 两套并存，客户端只用 `recipe_favorites` |
| 冰箱 | `inventory_batches`、`inventory_movements` | `household_id` | 批次化、非负 CHECK、变动留痕 |
| 计划 | `plan_slots`、`plan_items` | `household_id` | **只有家庭计划**；`UNIQUE(household_id,date,meal_type)`；`plan_items` 复合 FK 只能指向本家庭 `recipes` |
| 做饭 | `cooking_events` | `household_id` | `recipe_snapshot` JSONB 冻结历史；`request_id` 幂等 |
| 食记 | `meal_entries` | `user_id` | 个人；`basis`+`basis_kcal`+`total_kcal` 快照；UNKNOWN 必须 NULL |
| 幂等 | `idempotency_requests` | `(actor_id,key)` | 占位完成性由延迟约束触发器保证 |

### 必须记住的三条结构性事实

1. **三个菜谱范围 = 三张表 + 三套端点**，不是"一张表 + scope 字段"。公共/个人/家庭在字段集、食材结构、可写性上都不一致。
2. **服务端没有个人计划**。`plan_slots.household_id NOT NULL`，无 `scope`/`user_id`。个人「今天」计划是**客户端本地 JSON 草稿**（`filesDir/home-plan-drafts/<sha256(account)>.json`）。
3. **`plan_items` / `cooking_events` / `meal_entries` 的外键把菜谱锁死在本家庭 `recipes`**，因此公共与个人菜谱**在数据层无法进入家庭计划**。

---

## 1.6 网络、后端与家庭数据同步

| 项 | 现状 |
| --- | --- |
| 契约 | 48 条路径（`contracts/openapi.json`），全部 `/api/v1`；家庭路径统一 `/households/{householdId}/...`；个人路径 `/me/...` |
| 认证 | Bearer opaque token；token 只存内存（`SessionStore`），进程死亡后需重新登录；无 refresh |
| 错误模型 | `{code,message,traceId,fieldErrors[]}`，客户端按契约严格校验响应结构（`DtoChecks`） |
| 同步模型 | **无实时推送、无 WebSocket、无离线写队列**。成员之间靠"进入页面/手动刷新"看到变化（与 V2 §9.3 "首版刷新式同步"一致） |
| 并发控制 | 写请求携带 `expectedVersion`（乐观锁），冲突返回 409；家庭写入用 `household` 行锁串行化（`CookingEventService.lockHousehold`） |
| 幂等 | 服务端 `idempotency_requests` 完备（含并发重试测试）；**客户端只在库存/建家庭路径使用稳定 key**，做饭与食记每次点击都生成新 UUID |
| 缓存 | 设计上 Room `CacheEntry` 按 `userId+householdId+url` 隔离并有 7 天 TTL，但 **`enqueueCached` 在生产代码零调用**（仅测试）；实际生效的是两组 SharedPreferences 缓存（不含账号键、登出不清理） |
| 图片 | `/media` multipart 上传 + 鉴权下载；个人/家庭菜谱有 `imageId`；公共菜谱只有 `imageKey`（客户端 asset 键） |
| 营养 | 服务端 `/nutrition/estimates`（DeepSeek 或 stub），30 天缓存、限流；仅个人「今天」与食记使用 |

---

## 1.7 各功能业务逻辑现状（按领域）

### A. 菜谱（三范围）

- **公共**：只读目录 1307 条，`GET /recipe-catalog`，登录可见；客户端按分类/搜索分页；详情用 emoji 展示（`imageKey` 未解析为服务端图片）。
- **我的**：`/me/recipes`，完整 CRUD + 归档 + 硬删除；编辑器只填名称/分类/描述/图片/自由文本食材/步骤。
- **家庭**：`/households/{id}/recipes`，完整 CRUD（无 DELETE）+ 归档；**结构化食材**，可进入库存匹配与做饭扣减。
- **复制链路**：
  - 公共→我的：客户端 `saveToMine()` 按名称查重后 `POST /me/recipes`（字段级复制，**无来源 ID**，会丢 `servings/servingSize/sourceUrl`）。
  - 公共/个人→家庭：客户端 `addToHousehold()` 用 26 条字典做"名字子串匹配"转换，**任一食材名解析失败即整体拒绝**；固定 `yieldServings=2`、`durationMinutes=30`、`tags=["家庭菜谱"]`。
  - 家庭→个人：**服务端隐式副作用**——创建家庭菜谱时同事务给作者写一条 `user_recipes` 快照（不写 origin、更新时不刷新）。
- **冰箱为空是否可建菜谱**：**可以**。recipe 服务不注入 inventory，无任何库存前置校验（`PersonalRecipeService.java:81`、`HouseholdRecipeService.java:86`）。
- **归档**：服务端支持，但**客户端所有列表硬编码 `archived=false`**，归档后 App 内不可见、无法恢复。

### B. 想吃清单（Wishlist）

- 实际入口在「菜谱」Tab 的第四个 chip「想吃」，数据来自 `recipe_favorites`，按 `kind ∈ CATALOG/PERSONAL/HOUSEHOLD` 存。
- 旧 `/me/wishlist`（只能收藏家庭菜谱）客户端零调用，是待迁移的遗留实现。
- **没有按家庭共享的想吃清单**；收藏是纯个人数据。
- 想吃与排餐完全分离（想吃不会自动进计划），符合 V2 词汇表。

### C. 排餐计划

- **家庭计划**（服务端）：14 天 × 三餐 `plan_slots`，每槽 ≤10 道菜、可锁定；操作只有两种：整槽 `PUT replacePlanSlot`（选菜=替换成单道菜；清空=`items=[]`）与 `PUT lock`。批量 `POST plan-commits`（≤42 槽）服务端已实现但**客户端从不调用**。
- **家庭计划候选池**：只有**家庭菜谱**（`listRecipes(householdId)`，前 100 条）。公共与个人菜谱不在候选内。
- **个人计划**（客户端）：本地 JSON 草稿，最多 14 天 × 三餐，条目可为公共目录菜或手写菜名 + 照片；**不调服务端、不参与库存、不上传**。
- **计划页**：固定"今天起 14 天"，无翻页、无 1 天模式、**无生成/预览**；个人模式只读。
- **生成能力**：「今天」页的"盲盒/偏好盲盒"只是**单道菜随机推荐**，不是 1 天/14 天计划生成。

### D. 冰箱与库存匹配

- 只有**家庭库存**（`/households/{id}/inventory-batches`）；无家庭时冰箱页提示"去建家庭"。无个人库存空间。
- 批次字段齐全（数量、单位、库位、到期日）；卡片按 `ingredient|unit` 聚合，可展开多批次。
- 状态：已用完 / 到期未知 / 已过期 / 今天到期 / ≤3 天临期 / 到期日；筛选 `ALL/VALID/SOON/EXPIRED`。
- 变动：消耗/丢弃/修正，写 `inventory_movements`，服务端非负保护。
- **服务端匹配能力已具备且质量较高**：`POST /recipe-matches` 与 `POST /cooking-previews` → `InventoryCalculator`；过期批次排除（到期当天仍算可用）、单位必须精确一致（无换算）、需求量 `quantity × servings / yield` 向上取整 3 位、`trackStock=false` 跳过、`quantity=null` → `UNKNOWN`、覆盖率分数。
- **但客户端零调用 `matchRecipes`**：App 内没有任何"可做/缺料/可做优先"排序、徽标或筛选。缺料只出现在两处**阻断式**提示（详情页做饭、食记计划菜）。
- **多菜合计缺口不存在**：`match` 对每个 entry 都用同一份完整库存独立计算，两道菜各需 600g 而库存 800g 会双双报 `ENOUGH`。

### E. 做饭与食记

- **确认做饭**：入口只有两个——家庭菜谱详情页的"做饭"按钮、食记里"计划菜→吃了"。流程 = `previewCooking` → `confirmCooking`（扣库存 + 写 `cooking_events` 快照）→（食记路径）`createMealEntry` 关联 `cookingEventId`。
- **做饭幂等**：服务端完备（同 key 只扣一次，有并发测试）；**客户端每次点击 `UUID.randomUUID()`，超时重试会换新 key → 可能重复扣库存**。
- **做饭前置**：详情页只要 preview 有任一 shortage 就 toast 并中止，不展示缺什么、不给"只记录不扣库存"的出口。
- **食记**：月历（上月/下月/回今天）、当日早/午/晚/加餐、增改删、补录、在外吃手填、kcal 四种 basis、未知热量不计入总和。**未来日期不拦截**（前后端都不拦）。
- **"在家吃"路径直接扣库存**：`DiaryFragment.consumeBatch()` 逐批 `POST movements(reason=CONSUME)`，然后才 `createMealEntry`。这是**个人用餐直接扣原材料的独立通道**，与 `CookingEvent` 无关，且非原子、`after.max(ZERO)` 会把超扣夹成 0。
- **个人食记隔离**：服务端所有读写都带 `user_id = actor`，隔离成立。

### F. 家庭与权限

- **当前产品规则（2026-11-25 用户决定，写入 `docs/PRODUCT_RULES.md:52-55`）**：所有活跃成员完全平等，创建者也可退出，任何人不能移除他人，最后一人退出则家庭注销。
- **代码事实**：迁移把全部 `OWNER` 降级为 `MEMBER`；建户固定写 `'MEMBER'`；服务端只有 `requireMember`（active membership），**全仓无任何角色鉴权**；客户端也完全没有 role 概念。
- 因此"房主"在当前系统中**不存在**——既没有字段、没有判定、也没有 UI。

---

## 1.8 现有 UI Design System

| 项 | 现状 |
| --- | --- |
| 主题 | `CivaTheme.Civa`；配色 14 个 `civa_*` 色；字体 ZCOOL KuaiLe；圆角 28dp 卡片 + pill 按钮 |
| 组件 | `CivaWindow`（边到边）、`PressBounce`、`Motion`、`Haptics`、`Feedback`（唯一 Snackbar 通道）、`SubmitButton`、`BakeryWelcomeLayout`、`ChefCatView`/`BakeryCatStage`、`DishPlateView`、`DishActionOverlay`/`DishPopoverPlacement`、`ElasticChoiceView`、`PaperBackdropView`、`LocalePrefs`、`ImageSources`、`QuantityInputs`、`Route`/`NavHost` |
| 缺口 | **没有**共享的标题栏/卡片/徽标/空态/加载态/错误态/dialog 组件（`core-ui` 甚至没有 `layout/` 目录）；26 处直接 `new MaterialAlertDialogBuilder`；间距/圆角 token 定义了但**全仓 0 引用**，257 处裸 `dp` |
| 响应式 | 无任何 `layout-sw600dp`/`land` 资源；运行时宽度判断（导航 600dp、冰箱双栏 720dp、菜谱网格 3 列 600dp）；除冰箱外均单栏 |
| 国际化 | 中英资源基本对齐（`ac` 361/361、`ef` 95/95 等），**例外**：`bd_elastic_choices` 中文 19 键 / 英文 8 键；另有 3 处中文数据标识符（分类值、库位值、家庭 tag） |
| 无障碍 | 主要交互点有 contentDescription；`ElasticChoiceView` 保留原生 radio 语义；`PressBounce` 不吞点击 |

---

## 1.9 测试与异常处理现状

### 测试规模（按文件清点）

| 模块 | 单元/集成测试文件 | 测试方法 | 说明 |
| --- | --- | --- | --- |
| app | 7 | 25 | ArchUnit 架构门禁 |
| core-data | 8 (+1 androidTest 未在 CI) | 23 (+2) | MockWebServer、会话代数 |
| core-ui | 3 | 11（其中 1 个 `main()` 手工类不被 JUnit 执行） | 纯函数 |
| feature-ac | 6 | 37 | 展示层纯函数，**无 Fragment 测试** |
| feature-bd | 7 | 31 | 盲盒/草稿/营养 |
| feature-ef | 1 | 8 | 账号隔离 |
| api-client | 1（contractCandidateTest） | 1 | **CI 不执行** |
| server | 38 (+1 candidate) | 69 (+4) | 真实 PostgreSQL 集成测试 |
| **合计** | **72** | **211** | — |

### 覆盖强项
- 库存匹配与扣减：并发幂等、分配顺序、单位冲突、未知值、过期边界（`InventoryHttpIntegrationTest`、`InventoryCalculatorTest`）。
- 家庭生命周期：最后两人同时退出、加入与注销竞态、永久邀请码（`HouseholdLifetimeIntegrationTest` 等）。
- 会话一致性：`sessionGeneration` 丢弃陈旧回调（`ApiCallsTest`、`CachedCallsTest`）。
- 契约门禁：OpenAPI 结构、生成一致性、变更授权、模块边界、公共面登记。

### 覆盖盲区（与 V2 直接相关）
- **无任何"家庭角色/权限"测试**（因为没有角色）。
- 无"计划 replace 的语义 diff（删除/减少/替换）"测试。
- 无"非成员/被移除成员对 plan 写请求"测试。
- 客户端 `confirmCooking` / `createMealEntry` / 计划 Fragment **零测试**。
- CI 不跑 `connectedAndroidTest`、两个 contract-candidate 源集、`test_start_local.ps1`。
- lint 仅 `ResourceName` 为 error。

### 异常处理
- 服务端：`@RestControllerAdvice` 统一错误信封，401/403 JSON 同构；**无 `Exception` 兜底 → 未预期异常走 `/error`，响应不是契约信封**；**全仓 0 个 Logger**。
- 客户端：无全局 `UncaughtExceptionHandler`、无崩溃上报、仅 3 处 `Log`；错误靠逐调用点 `onError`；14 处空 catch，其中 3 处属于真正的静默失败（`ProfileFragment` 家庭名/成员、`BdTodayPlanReader` 读草稿失败 → 返回"今天没有计划"）。

---

## 1.10 功能实现状态总表（Phase 1 结论）

图例：✅ 已实现且符合 · 🟡 部分实现 · 🔴 已实现但逻辑不符 · ⚪ 未实现

| 功能 | 状态 | 依据（一句话） |
| --- | --- | --- |
| 登录/注册/会话 | ✅ | 服务端 BCrypt + opaque token，客户端内存会话与代数守卫 |
| 家庭：建/加入/改名/退出/永久邀请码/成员列表 | ✅ | 全员平等模型完整，含竞态测试 |
| 家庭：房主角色与权限 | ⚪ | 迁移已删除 OWNER，全仓无角色判定 |
| 菜谱：公共目录浏览/搜索/分类 | ✅ | 1307 条只读，分页 + 分类 + 离线兜底 |
| 菜谱：个人 CRUD/归档/删除 | ✅ | 按 owner 隔离 + 版本 + 幂等创建 |
| 菜谱：家庭 CRUD/归档/结构化食材 | ✅ | 成员可读写；update 非事务（缺陷） |
| 菜谱：空冰箱可创建 | ✅ | 无库存前置；有注释与测试 |
| 菜谱：公共→个人可编辑副本 | 🟡 | 客户端字段复制 + 名称去重，无来源 ID、丢字段 |
| 菜谱：公共/个人→家庭 | 🟡 | 依赖 26 条字典子串匹配，多数菜谱失败；硬编码 2 份/30 分钟 |
| 菜谱：归档后可查看/恢复 | ⚪ | 列表硬编码 `archived=false` |
| 菜谱：搜索（家庭 Tab）/食材筛选/口味筛选 | 🟡 | 家庭搜索框空转；食材筛选无 UI；口味筛选仅公共 |
| 菜谱：做过历史（按菜谱） | 🟡 | 数据可派生（`?recipeId=`），无聚合端点、无 UI |
| 想吃清单（三 scope 个人收藏） | ✅ | `recipe_favorites` + 菜谱 Tab「想吃」 |
| 想吃清单（家庭共享） | ⚪ | 无 scope 字段、无家庭维度 |
| 个人计划（今天） | 🟡 | 本地草稿可加菜/换日期，但**候选池只有公共目录**、不上服务端、不可跨设备 |
| 家庭计划：14 天 × 三餐查看/选菜/清空/锁定 | ✅ | 服务端 + 客户端完整 |
| 家庭计划：成员可添加 | ✅ | 任何 active 成员可 replace（但无"仅新增"约束） |
| 家庭计划：仅房主可删减 | ⚪ | 无角色、无 diff 校验，任何成员可清空/覆盖 |
| 家庭计划：候选池 = 公共 + 家庭 | ⚪ | 只有家庭菜谱；FK 也不允许公共菜谱 |
| 家庭计划：跨账号同步 | ✅ | 服务端共享 + 刷新可见 |
| 家庭计划：1 天/14 天生成 + 预览 | ⚪ | 无生成端点、无调用方、无预览 UI |
| 库存：批次/到期/消耗/丢弃/修正 | ✅ | 覆盖较好，含非负与变动留痕 |
| 库存：单一入口（无个人/家庭两套） | ✅ | 只有家庭库存入口，符合 V2 UI 要求 |
| 库存：单人模式库存 | ⚪ | 无家庭即不可用 |
| 库存匹配计算（服务端） | ✅ | `InventoryCalculator` 质量高 |
| 库存匹配接入 UI（可做优先/缺料标签/筛选） | ⚪ | 客户端零调用 `matchRecipes` |
| 缺料不阻断建菜谱/想吃/排餐 | ✅ | 三处均无库存前置（唯一阻断在"确认做饭"） |
| 缺料展示（缺什么、缺多少） | 🟡 | 仅做饭时一条 toast；食记里有清单但把 `available=null` 当 0 |
| 多菜合计缺口 | ⚪ | 每菜独立计算，不累计 |
| 确认做饭：预览→确认→扣库存一次（服务端） | ✅ | 幂等 + 事务 + 非负 |
| 确认做饭：客户端幂等键 | 🔴 | 每次点击新 UUID，重试会重复扣减 |
| 确认做饭：分配量校验 | 🔴 | 允许空 allocations、不校验配料/单位/过期 |
| 确认做饭：缺料时仍可记录真实做饭 | 🟡 | 食记提供"仍记一餐"（不扣库存）；详情页直接阻断 |
| 个人食记：月历/增改删/补录/热量/未知 | ✅ | 功能完整，UNKNOWN 不当 0 |
| 个人食记：不得扣库存 | 🔴 | "在家吃"直接 `changeInventory(CONSUME)` 扣库存 |
| 个人食记：未来日期拦截 | ⚪ | 前后端都不拦 |
| 历史快照（做饭） | ✅ | `recipe_snapshot` JSONB，编辑菜谱不改写 |
| 历史快照（食记） | 🟡 | 存 name/amount/kcal，但 `recipe_id` 弱引用，不存完整快照 |
| 并发冲突检测 | ✅ | `expectedVersion` → 409 |
| 并发首插槽 | 🔴 | 唯一键冲突未映射，返回 500 |
| 错误反馈一致性 | 🟡 | Snackbar 统一，但空态/错误态各 feature 各写 |
| 平板/横竖屏 | 🟡 | 导航 rail + 冰箱双栏；无 sw600dp 资源、菜谱无 master-detail |
| 中英文完整 | 🟡 | 主体完整，`bd_elastic_choices` 缺 11 键 |

---

## 1.11 文档与代码的已知漂移（必须先纠正认知）

| 文档说法 | 代码实际 | 影响 |
| --- | --- | --- |
| `docs/DATA_CONTRACT.md:337` "owners can remove ordinary members; OWNER_CANNOT_LEAVE" | 同文件 `:344-348` 已被"全员平等"推翻；迁移已删除 OWNER | 读文档者会误以为存在房主 |
| `PROJECT_DOC.md:301` "所有活动成员平等" | 与 V2 "房主独有删减权" 直接冲突 | 需要显式决策并记录 supersede |
| `docs/DATA_CONTRACT.md` "个人草稿不上服务端" | 与代码一致 | 与 V2 §5.1 逻辑模型建议不一致 |
| `UI_RULES.md:36-37` 承诺共享 title/card/badge/empty/error 组件 | `core-ui` 无 `layout/`，无这些组件 | 文档过度承诺 |
| `civa_dimens.xml` 注释"用 token 代替裸 dp" | 257 处裸 dp，token 0 引用 | 文档与实现不符 |
| 旧会话 `SESSION_STATE.md` 说"今天页家庭加菜来自家庭菜谱" | 与代码一致（已核实） | 可作为背景，不作为需求 |

---

## 1.12 Phase 1 可直接复用的资产（V2 开发不应重写的东西）

1. **服务端库存匹配与做饭扣减核心**（`InventoryCalculator`、`InventoryMatchingService`、`CookingEventService`）——V2 的"可做优先/缺料/扣减一次"都应复用它，只补调用方与边界校验。
2. **契约工具链与门禁**（OpenAPI 单一源 + 生成一致性 + IC 授权 + ArchUnit）——任何字段/接口改动走既有流程，不新建第二套。
3. **会话一致性机制**（`SessionStore.generation` + `ApiCalls` 丢弃陈旧回调）——新增页面直接复用，不要自建状态同步。
4. **幂等基础设施**（服务端 `IdempotencyExecutor`、客户端 `PendingWriteStore`）——做饭/食记补稳定 key 时直接复用，不新写队列。
5. **家庭共享读写**（`plan-slots` 乐观锁 + 行锁 + 409）——权限叠加在这一层，不重做同步。
6. **视觉基元**（`Feedback`、`PressBounce`、`SubmitButton`、`Motion`、`DishPlateView`、`ElasticChoiceView`）——新增"缺料标签/可做筛选/权限提示"直接复用。
7. **三范围菜谱的服务端读写与隔离**——不需要为一范围重写 CRUD，只需补跨范围引用与来源追踪。
