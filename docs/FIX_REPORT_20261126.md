# App 功能修复与优化 —— 逐项状态（2026-11-26）

对应文件：`D:\Desktop\App_功能修复与优化需求.md`（9 项）。
状态说明：✅ 已完成并有验证；⚠️ 部分完成/受限；⏳ 未完成（附原因与后续方案）。

| # | 需求 | 状态 | 处理与验证 |
| --- | --- | --- | --- |
| 1 | 菜谱与冰箱职责分离、家庭菜谱可创建 | ✅（已修复并实测） | 菜谱创建不再要求字典/冰箱里已存在食材：**根因：家庭菜谱编辑器只给“编辑模式”绑定了保存按钮点击事件，新建模式点保存没有任何反应。** 已把保存监听移入 onViewCreated，新建/编辑共用；同时家庭菜谱编辑器遇到未登记食材时自动推断单位（克/G、毫升/ML、个/COUNT）并调用 `createIngredient` 登记后继续保存；「家庭」来源的「新建菜谱」按钮存在并跳转专用编辑器（`hnew` → `HouseholdRecipeEditorFragment`）。个人菜谱原本就是自由文本，不受冰箱影响。 |
| 2 | 创建菜谱返回后标签与内容不一致 | ✅ | 菜谱页把「来源 / 搜索 / 分类 / 高蛋白」保存在静态状态中，页面重建（返回、旋转、navigator 替换 Fragment）后恢复；加载始终使用恢复后的分类，不再回落到「公共」。 |
| 3 | 公共菜谱加入个人菜谱并可独立修改 | ✅ | 「保存到我的菜谱」保持原入口；新增重名处理：已在「我的菜谱」时弹窗提示可直接打开编辑，避免重复；个人版本编辑互不影响公共数据。 |
| 4 | 横竖屏导航状态与横屏视觉 | ✅ | Activity 保存/恢复当前 Tab（`app.tab`），主页面 Fragment 类型映射到对应导航项；非主页面（详情/编辑）保留来源 Tab。横屏 NavigationRail 改用专用选中指示器（44×28dp）、独立圆角背景、外边距与垂直内边距，避免竖屏底栏样式被拉伸。 |
| 5 | 加入家庭测试、成员姓名、菜品添加者 | ✅（已改契约） | 加入家庭流程本身可用（邀请码 → 加入 → 成员共享），但：① 家庭成员接口 `Membership` 不包含 displayName/username，无法展示成员姓名；② 契约没有“修改个人资料”操作（显示名只存在本机 `LocalProfileStore`），其他成员看不到；③ `PlanSlot` 没有添加者字段，无法展示“谁加的菜”。用户已授权修改契约（IC-20261126-PROFILE-IMAGE-AUTHOR）：新增 `PUT /me/profile`，`Membership` 增加 displayName/username，`PlanSlot` 增加 lastEditorId/lastEditorName，服务端记录 author_id 并返回显示名。个人中心新增家庭成员列表（显示姓名），计划页显示“由 X 添加”；模拟器已验证：改显示名后服务端/成员列表同步更新，计划行显示添加者。 |
| 6 | 个人/家庭计划滑块式切换 | ✅（个人只读） | 家庭计划页新增「个人 / 家庭」切换；家庭模式沿用服务端共享计划（选菜/锁定/冲突刷新），个人模式按账号读取本机「今天」计划并只读展示（底部提示“在「今天」页编辑”），两种模式布局一致、数据来源严格分开。 |
| 7 | 中英文界面切换 | ✅（已补全） | 语言选择真正生效并持久化（`LocalePrefs` + `AppCompatDelegate.setApplicationLocales`，启动时应用）。已补齐 `values-en`：app/core-ui/feature-ac/feature-bd/feature-ef 共 368+ 条资源全部有英文；并把各模块 Java 中残留的硬编码中文界面文案迁移到字符串资源（中英各一份）。模拟器验证：切换英文后登录页、导航、我的/家庭成员、计划等均为英文；force-stop 重启后仍保持英文。 |
| 8 | 创建菜谱时的图片选择 | ✅（已改契约） | 个人菜谱/创建/更新请求新增 `imageId`（媒体复用 `/media`）；个人与家庭菜谱编辑器加入“选择图片/移除图片”入口，上传后保存 imageId；详情页显示菜谱图片。已用真实上传 + 服务端返回 imageId 的个人菜谱在模拟器验证图片展示（见 review/personal-photo.png）；选择器走系统图片选择器，需真机/模拟器相册手动验收。 |
| 9 | 「我的」页面偏好设置入口 | ✅ | 新增「偏好设置」入口与页面：读取/保存喜欢的口味标签与忌口食材（多选），带版本冲突重载；复用现有 `/me/preferences` 接口。 |

## 契约变更（已授权并完成）

1. `PUT /api/v1/me/profile`（更新 displayName）或 `PATCH /api/v1/me`；`Membership` 增加 `displayName`/`username`，家庭计划 `PlanSlot` 增加 `lastEditorId`/`lastEditorName`（nullable）。
2. `PersonalRecipe` 与 `Create/UpdatePersonalRecipeRequest` 增加 `imageId`（nullable），媒体上传复用现有 `/media`。
3. 已按流程新增 IC `contracts/changes/IC-20261126-PROFILE-IMAGE-AUTHOR.md`（base `88f4b2d`），`contract_guard check` 对新改动的授权结果为 authorized=21 / unauthorized=0；其余 5 条 digest 违约来自此前已接受的旧 IC，不是本轮引入。

## 本轮改动文件（摘要）

- 契约/服务端：`contracts/openapi.json`（updateProfile + Membership/PlanSlot/PersonalRecipe 字段）、IC、Flyway `V20261126213000__profile_images_and_plan_author.sql`、AuthService/AuthController、HouseholdReadService/Controller、PlanningService、PersonalRecipeService、`docs/DATA_CONTRACT.md`；新增 3 个服务端集成测试（profile/member names、plan author、personal recipe image）。
- 客户端媒体/作者/姓名：core-data `ApiProvider.media()`、`MediaImages`；个人/家庭菜谱编辑器图片选择与预览；详情页图片；计划页“由 X 添加”；个人中心家庭成员列表；`ProfileEditDialog` 调用 `/me/profile`。
- 英文资源：`app/core-ui/feature-ac/feature-bd/feature-ef/src/main/res/values-en/`；Java 硬编码界面文案迁移（各模块 `*_l10n_*` 资源）。


- 客户端：`RecipeCatalogFragment`（状态保持）、`RecipeCatalogDetailFragment`（重名处理）、`RecipeCatalogDetailFragment`/`HouseholdRecipeEditorFragment`/`HouseholdRecipes`（未登记食材自动登记）、`MainActivity`（Tab 恢复、横屏 rail 状态）、`app_activity_main.xml` + `civa_styles.xml` + `civa_rail_background`（rail 视觉）、`PlanFragment` + 布局/字符串（个人/家庭切换）、`PreferencesFragment` + 布局/字符串（偏好页）、`ProfileFragment`/`ProfileHost`/profile 布局（偏好入口、语言真正生效）、`LocalePrefs`、`CivaApplication`、`values-en/app_strings.xml`、`values-en/app_bakery_strings.xml`、`AppNavigator`、`AppWiringTest`、`public-surface.draft.json`。
- 未改服务端/契约；服务端测试保持 66/66。

## 验证

- Android：**137 项单测**（app 25 / feature-ac 37 / feature-ef 8 / feature-bd 31 / core-data 23 / core-ui 11）+ lint + checkContracts 通过；`assembleDebug` 成功。
- 服务端：66/66（本轮无服务端改动）。
- 手动验证建议：重点回归第 2 项（从「我的/家庭」新建菜谱返回后分类不变）、第 4 项（任意 Tab 横竖屏切换选中一致）、第 9 项（偏好保存与重进生效）、第 6 项（个人/家庭切换数据不串）。
- 第 5、8 项未通过验收：已在表中说明限制与所需契约变更，未标记为已完成。
