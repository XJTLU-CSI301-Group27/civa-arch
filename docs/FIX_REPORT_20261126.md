# App 功能修复与优化 —— 逐项状态（2026-11-26）

对应文件：`D:\Desktop\App_功能修复与优化需求.md`（9 项）。
状态说明：✅ 已完成并有验证；⚠️ 部分完成/受限；⏳ 未完成（附原因与后续方案）。

| # | 需求 | 状态 | 处理与验证 |
| --- | --- | --- | --- |
| 1 | 菜谱与冰箱职责分离、家庭菜谱可创建 | ✅ | 菜谱创建不再要求字典/冰箱里已存在食材：家庭菜谱编辑器遇到未登记食材时自动推断单位（克/G、毫升/ML、个/COUNT）并调用 `createIngredient` 登记后继续保存；「家庭」来源的「新建菜谱」按钮存在并跳转专用编辑器（`hnew` → `HouseholdRecipeEditorFragment`）。个人菜谱原本就是自由文本，不受冰箱影响。 |
| 2 | 创建菜谱返回后标签与内容不一致 | ✅ | 菜谱页把「来源 / 搜索 / 分类 / 高蛋白」保存在静态状态中，页面重建（返回、旋转、navigator 替换 Fragment）后恢复；加载始终使用恢复后的分类，不再回落到「公共」。 |
| 3 | 公共菜谱加入个人菜谱并可独立修改 | ✅ | 「保存到我的菜谱」保持原入口；新增重名处理：已在「我的菜谱」时弹窗提示可直接打开编辑，避免重复；个人版本编辑互不影响公共数据。 |
| 4 | 横竖屏导航状态与横屏视觉 | ✅ | Activity 保存/恢复当前 Tab（`app.tab`），主页面 Fragment 类型映射到对应导航项；非主页面（详情/编辑）保留来源 Tab。横屏 NavigationRail 改用专用选中指示器（44×28dp）、独立圆角背景、外边距与垂直内边距，避免竖屏底栏样式被拉伸。 |
| 5 | 加入家庭测试、成员姓名、菜品添加者 | ⚠️ 受限 | 加入家庭流程本身可用（邀请码 → 加入 → 成员共享），但：① 家庭成员接口 `Membership` 不包含 displayName/username，无法展示成员姓名；② 契约没有“修改个人资料”操作（显示名只存在本机 `LocalProfileStore`），其他成员看不到；③ `PlanSlot` 没有添加者字段，无法展示“谁加的菜”。需要新增契约（见下文“需要的契约变更”），本轮未改契约以免破坏已冻结接口。 |
| 6 | 个人/家庭计划滑块式切换 | ✅（个人只读） | 家庭计划页新增「个人 / 家庭」切换；家庭模式沿用服务端共享计划（选菜/锁定/冲突刷新），个人模式按账号读取本机「今天」计划并只读展示（底部提示“在「今天」页编辑”），两种模式布局一致、数据来源严格分开。 |
| 7 | 中英文界面切换 | ⚠️ 部分 | 语言选择已真正生效：`LocalePrefs` + `AppCompatDelegate.setApplicationLocales`，选择持久化并在启动时应用；已提供 `values-en` 的 App 外壳文案（导航、页面标题等）。业务页面（登录/菜谱/食记/计划/冰箱/我的）的大部分字符串仍是中文默认资源，尚未提供英文翻译，切换后会出现中英混排。 |
| 8 | 创建菜谱时的图片选择 | ⏳ 未完成 | 服务端媒体接口已完成（上传/读取 + 测试），但：① `PersonalRecipe` 契约没有 imageId 字段，个人菜谱无法保存图片；② 家庭菜谱契约有 imageId，但编辑器尚未接入系统图片选择/预览。需要先补个人菜谱图片字段（契约 + IC），再做选择器与展示。 |
| 9 | 「我的」页面偏好设置入口 | ✅ | 新增「偏好设置」入口与页面：读取/保存喜欢的口味标签与忌口食材（多选），带版本冲突重载；复用现有 `/me/preferences` 接口。 |

## 需要的契约变更（第 5、8 项）

1. `PUT /api/v1/me/profile`（更新 displayName）或 `PATCH /api/v1/me`；`Membership` 增加 `displayName`/`username`，家庭计划 `PlanSlot` 增加 `lastEditorId`/`lastEditorName`（nullable）。
2. `PersonalRecipe` 与 `Create/UpdatePersonalRecipeRequest` 增加 `imageId`（nullable），媒体上传复用现有 `/media`。
3. 以上属于公共契约变更，按团队流程需要 IC + digest 复审（当前基线 `ae60b66`），本轮未擅自修改。

## 本轮改动文件（摘要）

- 客户端：`RecipeCatalogFragment`（状态保持）、`RecipeCatalogDetailFragment`（重名处理）、`RecipeCatalogDetailFragment`/`HouseholdRecipeEditorFragment`/`HouseholdRecipes`（未登记食材自动登记）、`MainActivity`（Tab 恢复、横屏 rail 状态）、`app_activity_main.xml` + `civa_styles.xml` + `civa_rail_background`（rail 视觉）、`PlanFragment` + 布局/字符串（个人/家庭切换）、`PreferencesFragment` + 布局/字符串（偏好页）、`ProfileFragment`/`ProfileHost`/profile 布局（偏好入口、语言真正生效）、`LocalePrefs`、`CivaApplication`、`values-en/app_strings.xml`、`values-en/app_bakery_strings.xml`、`AppNavigator`、`AppWiringTest`、`public-surface.draft.json`。
- 未改服务端/契约；服务端测试保持 66/66。

## 验证

- Android：**137 项单测**（app 25 / feature-ac 37 / feature-ef 8 / feature-bd 31 / core-data 23 / core-ui 11）+ lint + checkContracts 通过；`assembleDebug` 成功。
- 服务端：66/66（本轮无服务端改动）。
- 手动验证建议：重点回归第 2 项（从「我的/家庭」新建菜谱返回后分类不变）、第 4 项（任意 Tab 横竖屏切换选中一致）、第 9 项（偏好保存与重进生效）、第 6 项（个人/家庭切换数据不串）。
- 第 5、8 项未通过验收：已在表中说明限制与所需契约变更，未标记为已完成。
