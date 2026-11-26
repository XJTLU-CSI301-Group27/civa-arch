# Civa Visual & Interaction Polish Report

日期：2026-11-26 · 范围：Android App 现有 UI（手机 + 小米平板）
本轮只做**审查、方案与计划**，不修改代码。所有建议以现有 Design System 为约束。

---

## 0. 结论先行

现有视觉系统是完整且统一的：烘焙纸背景 + 焦糖主色 + 手绘猫插画 + 圆润卡片 + 克制的低阴影，已经不像“课程作业的临时界面”。当前影响完成度的主要不是配色或布局，而是**状态与状态之间缺少过渡**：

1. 页面切换是硬替换（`FragmentTransaction.replace`，无任何转场）；
2. 列表数据更新用 `notifyDataSetChanged()`，新增/删除/切换分类会瞬间跳变；
3. 菜谱卡片等自定义可点击容器**没有按压反馈**（只有 Button/ImageButton 被 `PressBounce` 绑定）；
4. 所有自定义动画都不尊重系统“减少动态效果/动画缩放”设置；
5. 提交按钮只有 disable，没有 loading 进度，成功/失败反馈依赖 8 处 Toast，风格不统一；
6. 刷新时整块 `removeAllViews()` 重建，内容闪一下；
7. 首次启动 7.35s 的猫猫视频没有点击跳过。

建议按 **Essential → Recommended → Optional** 三阶段推进；第一阶段改动集中在 5 个文件左右，预计能把“完成度”提升最明显的一档。

---

## 1. 现有 Design System 分析（作为约束）

| 维度 | 现状 | 证据 |
| --- | --- | --- |
| 主题 | 单一浅色 MaterialComponents，无深色模式 | `civa_styles.xml` `CivaTheme.Civa` |
| 主色 | 焦糖 `#AA7048` / 深焦糖 `#795034` / 围裙蓝 `#718994` | `civa_colors.xml` |
| 背景/表面 | 烘焙纸 `#F6F0E3` / 奶油纸 `#FFFBF2`，描边 `#DFCEB3` | 同上 |
| 文字 | 奶咖 `#624B39`、弱化 `#947C65` | 同上 |
| 字体 | 展示标题用手写体 `civa_kuaile`，正文 sans；标题 30sp/38sp，正文 14–16sp | `CivaText.*` |
| 形状 | 大圆角：按钮 24dp、导航胶囊 50%、卡片 14–28dp（不统一） | `CivaWidget.*`、各 drawable |
| 阴影 | 非常克制，全局仅 4 处 elevation，以描边/色块分层 | layout 扫描 |
| 图标 | 矢量线性图标 + emoji + 手绘猫插画混合；导航 24dp、点击区 48dp | `app_activity_main.xml` 等 |
| 已有动效 | ① `PressBounce`：自写 spring（scale 0.94，stiffness 260 / damping 12）；② 首启 1100ms 入场 + 7.35s 猫猫视频；③ 气泡 130/180ms overshoot；④ 菜谱操作浮层猫 180ms spring；⑤ 弹性选择器 280ms 颜色+位移 | `PressBounce.java`、`AppIntroView.java`、`DishActionOverlay.java`、`ElasticChoiceView.java` |
| 导航结构 | 底部 5 tab；≥600dp 宽屏切换为左侧 NavigationRail；无页面转场 | `MainActivity`、`AppNavigator` |
| 列表 | `CatalogAdapter` 全量 `notifyDataSetChanged`，无 DiffUtil、无 stable id、无自定义 ItemAnimator | `CatalogAdapter.java:26-29` |
| 反馈 | Toast 8 处、Material 对话框 26 处、无 Snackbar、无触觉反馈 | 扫描结果 |
| 无障碍 | 多数图标有 contentDescription；无 reduced-motion 处理 | 扫描结果 |

**因此优化的边界是：** 不换主色、不改布局结构、不引入第三方动画库；动效只服务于“状态连续性/因果反馈”；优先用 Android/Material 原生能力（`ViewPropertyAnimator`、`FragmentTransaction.setCustomAnimations`、`MaterialSharedAxis/FadeThrough`（Material 1.12 已内置）、`DefaultItemAnimator`、`TransitionManager`）。

---

## 2. 问题清单与建议（按优先级）

> 字段：页面/组件 · 当前行为 · 问题 · 建议 · 动画 · 参数 · 复杂度 · 风险 · 收益 · 是否值得

### 2.1 Essential Polish（必要，建议第一批）

| # | 页面/组件 | 当前行为 | 问题 | 建议 | 动画 | 参数 | 复杂度 | 性能/回归风险 | 收益 | 值得 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **E1** | 全局导航（`AppNavigator`） | 所有页面 `replace`，硬切 | 失去层级与方向感，详情/编辑像“闪出来” | 用 `setCustomAnimations`：主 tab 之间 **FadeThrough**，push 详情/编辑/计划用 **SharedAxis X**，pop 反向；返回手势保持系统行为 | 是 | tab 切换 180ms fade-through（enter/exit 各 90ms stagger）；push/pop 220ms emphasized `cubic-bezier(0.2,0,0,1)`，位移 8% 宽度 | S | 低：不改 backstack；需确认旋转/恢复；动画时长由系统缩放控制 | 极大：第一眼“像一个完成的 App” | ✅ |
| **E2** | 菜谱列表（公共/我的/家庭/想吃）、收藏 | `notifyDataSetChanged()` 全量刷新 | 新增/删除/切换分类瞬间跳变；滚动位置可能闪 | `DiffUtil` + stable id + `DefaultItemAnimator`（add/remove fade + move 180ms）；分页 append 用 payload | 是 | item add: fade in 160ms + translateY 6dp；remove: fade 120ms；move: 180ms；item animator `changeDuration=180` | M | 中：Diff 必须正确处理重复/空 id；分页时避免二次动画 | 高：列表是使用频率最高区域 | ✅ |
| **E3** | 菜谱卡片、想吃卡片、计划行、日记行、冰箱卡片 | 卡片可点击但无 ripple/缩放；`PressBounce` 只绑 Button/ImageButton | 点击“没手感”，像静态网页 | 卡片根布局加 ripple（`foreground=?attr/selectableItemBackground` 或自定义纸感 ripple），并纳入 `PressBounce`（scale 0.97）；滚动时返回 false 不影响滑动 | 是 | 按下 0.97（spring 复用现有），release 回弹 ≤400ms；ripple 默认 | S | 低：需确认不拦截 RecyclerView 滑动（现有 Binding 返回 false，可复用） | 高：用户最常点的对象 | ✅ |
| **E4** | 全部自定义动效（`PressBounce`、`BakeryCatStage`、`DishActionOverlay`、未来转场） | 不检查系统动画设置 | 无障碍/省电模式用户仍播放动画；部分低端设备浪费帧 | 增加 `Motion` 工具：`ValueAnimator.areAnimatorsEnabled()` + 系统 `durationScale` 判断；关闭时直接终态（不改变任何状态结果） | 否（降级） | 当 scale==0：按压只变透明度/无动画；转场使用无动画 commit | S | 低：只读系统设置；需在设置变更后重新读取 | 高：无障碍硬要求 | ✅ |
| **E5** | 保存/提交类按钮（菜谱编辑器、计划保存、偏好保存、改资料、创建/加入家庭） | 提交时 `setEnabled(false)`，按钮文字不变，无进度 | 用户不确定是否生效；重复点击风险靠零散 disable | 按钮 loading 态：文字→短文案/内嵌小 Progress（<20dp）+ 保持宽度；成功恢复；失败恢复+错误提示；统一封装在 `PressBounce` 同层的 Button 状态工具 | 是 | 文字交叉淡入 120ms；spinner 16dp，indeterminate；最小展示 300ms 防闪烁 | S–M | 低：逻辑只是状态展示；要覆盖所有提交按钮 | 中高：交互可靠感 | ✅ |
| **E6** | 全局提示（保存成功/失败、删除、收藏、计划冲突） | 8 处 `Toast`，位置/时长/样式不统一 | Toast 与品牌 UI 脱节，无操作入口 | 统一 `Snackbar`（主题色、底距 16dp、2s）；删除类给“撤销”（若业务支持）；页面内错误尽量 inline 文案 | 是 | Snackbar 滑入 150ms `decelerate`；自动消失 2s | S–M | 低：替换反馈通道，不改业务；需确保错误可见性 | 中：整体精致度 | ✅ |
| **E7** | 状态切换：想吃/已想吃、计划锁定/解锁、来源 chip、偏好勾选 | 文字/颜色瞬间切换 | 状态变化缺少“确认感” | 可切换控件统一 120–160ms 颜色/缩放过渡；收藏图标加轻微 overshoot（1.0→1.12→1.0，180ms）；锁图标旋转 90° + scale | 是 | 颜色 120ms standard；图标 180ms overshoot(1.2)；锁旋转 160ms | S | 低：只改视觉表现，不改状态写入 | 中：细节精致 | ✅ |
| **E8** | 宽屏左侧导航 / 底部导航选中态 | 选中项切换瞬间跳变（Material 默认） | 横竖屏切换、tab 切换缺少空间连续感 | 选中图标 scale 1→1.06→1 + indicator 颜色 140ms；tab 切换内容用 E1 的 fade-through 统一 | 是 | 140ms fast-out-slow-in；内容 180ms fade-through | S | 低：navigation 自带 state 动画可复用；不要影响选中回调 | 中：导航体验 | ✅ |
| **E9** | 所有网络按钮 / 列表项操作 | 零散 `setEnabled`；部分入口可重复触发 | 弱网/双击可能重复请求；禁用态视觉差异小 | 统一 `ButtonGuard`：禁用时 alpha 0.45、拦截点击、请求返回恢复；配合 E5 loading | 否 | 禁用渐变 90ms | S | 低：需逐个接入，覆盖遗漏入口 | 中高：稳定性 | ✅ |

### 2.2 Recommended Polish（推荐，第二批）

| # | 页面/组件 | 当前行为 | 问题 | 建议 | 动画 | 参数 | 复杂度 | 风险 | 收益 | 值得 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| R1 | 计划页、食记页、冰箱详情 | 刷新时 `removeAllViews()` 重建 | 内容闪白/跳动，滚动位置可能丢 | 用 `TransitionManager.beginDelayedTransition(container, Fade())` 包住重建；或改为局部更新 | 是 | Fade 120–160ms；仅小容器，大列表用 Diff | S–M | 中：避免动画期间丢焦点/滚动 | 中 | ✅ |
| R2 | 菜谱/冰箱空态 → 有数据 | 直接切换 | 突兀 | 空态 fade out + 内容 fade in（160ms）；复用同一容器尺寸 | 是 | 160ms fade | S | 低 | 中 | ✅ |
| R3 | 计划页「个人/家庭」切换 | 背景色瞬间切换，列表直接换 | 模式切换缺少指向 | 用 `MaterialButtonToggleGroup` 或自绘 indicator 滑动 160ms；列表以 FadeThrough 切换 | 是 | indicator 160ms emphasized；内容 180ms | S–M | 低 | 中 | ✅ |
| R4 | 菜谱来源/分类 chips | 手动循环取消选中，选中态瞬变 | 交互像“网页 radio”，无动画 | 改 `ChipGroup app:singleSelection=true` + `selectionRequired`，让 Material 自己处理；保留现有颜色 | 否（组件默认） | 默认 | S | 低 | 中：代码更少更稳 | ✅ |
| R5 | 首启猫猫视频 / intro | 固定 7.35s，无跳过 | 重复演示等待长，用户无法跳过 | 首次启动保留；非首次缩短或直接跳过（`SharedPreferences` 首启标记）；支持点击跳过；reduced-motion 直接进入登录 | 是 | 点击后 180ms fade out；首次完整，之后 ≤1.2s | S–M | 中：影响演示节奏；需产品确认“首次”定义 | 中高：演示/日常体验 | ✅（需确认） |
| R6 | 对话框体系（26 处） | Material 默认 + 自绘 DialogFragment 混用 | 标题字号/圆角/间距不一致（22sp vs 30sp；24/28dp 半径） | 定义 `CivaDialog` tokens：标题 22sp kuaile、正文 15sp、圆角 28dp、按钮间距 8dp；统一使用 | 否（Material 默认入场） | 系统默认 | S–M | 低：纯样式 | 中：整体精致度 | ✅ |
| R7 | 全局 Typography / Spacing | 字号 13/14/15/16/18/22/30/38 并存；padding 12/14/16/18/20 混用 | 页与页之间“呼吸感”不一致 | 建 spacing token 4/8/12/16/20/24；标题用 30/22/16 三档；正文 16/14，辅助 12；只改样式与 padding，不动布局结构 | 否 | — | S–M | 低：需逐屏截图比对 | 中高：高级感主要来自此 | ✅ |
| R8 | 圆角 | 14/17/18/20/22/24/28dp 并存 | 同类组件圆角不一致 | 三档：小 12（输入/小卡）、中 20（主卡/按钮 24 保留）、大 28（对话框/大容器）；同类统一 | 否 | — | S | 低：视觉回归需截图 | 中 | ✅ |
| R9 | 图标/触控区 | 24dp 线性图标 + emoji + 48dp 触控区；个别图标无描述 | 图标的视觉重量不统一 | 统一 24dp 图标网格；确保 ≥48dp 触控；补 contentDescription；emoji 与图标不混排同一层级 | 否 | — | S–M | 低 | 中 | ✅ |
| R10 | 首屏/列表加载 | 文字“加载中…”或空白 | 加载中状态廉价 | 轻量 skeleton（2–4 个占位卡）或内容区 fade+进度条；不做 shimmer 全屏 | 是 | 160ms fade；骨架静态即可 | M | 中：占位与真实布局需一致 | 中 | 可选 |
| R11 | 关键操作触觉 | 无触觉 | 收藏/锁定/删除缺少物理确认 | `performHapticFeedback(KEYBOARD_TAP / CONFIRM)`，尊重系统设置；不做自定义振动 | 否 | 系统常量 | S | 低：部分用户关触觉；系统开关已处理 | 中 | ✅ |
| R12 | 保存成功 | Toast 文字 | 缺少“完成感” | 成功瞬间目标卡片轻微 scale 1.02 + 勾选图标 180ms 淡入；克制使用 | 是 | 180ms overshoot | S | 低：需避免连续触发 | 低中 | 可选 |

### 2.3 Optional Delight（锦上添花，时间允许再做）

| # | 建议 | 说明 | 动画 | 复杂度 | 风险 | 收益 | 值得 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| O1 | 菜谱卡片 → 详情共享元素转场 | MaterialContainerTransform 从卡片图片/标题过渡到详情 | 300ms emphasized | L | 中高：需要 transitionName、RecyclerView 复用、返回协调；低端机可能掉帧 | 很高“哇”感 | 课程项目中优先级低，可不做 |
| O2 | 月历选中日期 morph + 月份切换 | 日期圆圈 scale/颜色 120ms；月份内容 fade-through | 120/180ms | S–M | 低 | 中 | 可以做 |
| O3 | 猫猫/插画情境化小动效 | 保存成功、空态、锁定时复用已有 `BakeryCatStage` 气泡，不做新角色 | 130–220ms | S | 低（已有实现） | 低中 | 有余力可做 |
| O4 | 数值滚动（热量/数量/统计） | 350ms 数字滚动 | 350ms | S | 低 | 低 | 可选 |
| O5 | 下拉刷新 + 猫猫厨师指示器 | 列表/冰箱下拉刷新头部 | 300ms | M | 中（RecyclerView 替换/滚动冲突） | 中 | 非必要，现有刷新按钮已可用 |
| O6 | 弹性选择器延展到更多选择场景 | 复用 `ElasticChoiceView` 的拖拽手感 | 已有参数 | M | 中（交互复杂） | 低 | 不建议扩大范围 |

---

## 3. Motion Design System（轻量版）

### 3.1 时长 / 曲线 token

| Token | 值 | 用途 |
| --- | --- | --- |
| `motion.micro` | 90ms | 禁用态/颜色微变 |
| `motion.short` | 140ms | chip 选中、favicon、按压回弹辅助 |
| `motion.medium` | 180–220ms | 页面转场、列表 item 动画、内容 fade |
| `motion.long` | 300–320ms | 大容器/对话框内容、首次引导 |
| `motion.max` | 400ms | 任何非引导动画上限 |

- **Enter / 出现**：`PathInterpolator(0.05, 0.7, 0.1, 1)`（decelerate 变体），180–220ms。
- **Exit / 消失**：`PathInterpolator(0.3, 0, 0.8, 0.15)`，120–160ms。
- **Emphasized（导航/共享轴）**：`PathInterpolator(0.2, 0, 0, 1)`，220–300ms。
- **Press spring**：沿用现有 `PressBounce`：scale 0.94，stiffness 260，damping 12（约 0.37 阻尼比，轻微 playful），不新增第二套按压曲线。
- **Success spring**：`OvershootInterpolator(1.2)`，180ms，仅用于图标/小卡片。
- **禁止**：>400ms 的页面动画；全屏闪烁；列表逐项 stagger（会造成等待感）。

### 3.2 类别与使用场景

| 类别 | 场景 | 规范 |
| --- | --- | --- |
| Press | 按钮/卡片/导航项 | 现有 ripple + PressBounce；卡片补 ripple 和 0.97 scale |
| State change | 收藏、锁定、chip、开关 | 颜色 120ms；图标 180ms overshoot；文字直接换（不滚动） |
| Navigation | tab 切换 / push / pop | tab：fade-through 180ms；push/pop：shared-axis X 220ms；返回手势完全交给系统 |
| Content | 列表刷新、空态切换、数据加载 | 小容器 `TransitionManager` fade 160ms；大列表 Diff + item animator 180ms |
| Overlay | 对话框、操作浮层、snackbar | 系统 Material 默认（dialog fade/scale）；snackbar 150ms 滑入 |
| Delight | 成功/空态/猫猫 | ≤260ms，且可被 reduced-motion 关闭 |

### 3.3 导航过渡原则

1. **同级 tab 不横滑**（会暗示可左右滑动），用 FadeThrough；
2. **进入下一层用 SharedAxis X（前进方向）**，返回反向；
3. 转场时长固定，不按内容多少动态变化；
4. 转场期间不阻塞输入（Fragment 事务由系统 native 处理）；
5. 不与系统返回手势、predictive back 冲突（API27 平板无 predictive back；新系统交给系统）；
6. 旋转/重建后不重播转场。

### 3.4 无障碍与性能硬性要求

- **减少动态效果**：所有自定义动画在开始时检查 `ValueAnimator.areAnimatorsEnabled()` / 系统动画缩放；为 0 时直接终态（只允许 ≤100ms opacity）。
- 不改变点击区域（≥48dp）；动画不拦截触摸；TalkBack 焦点顺序与状态描述不因动画改变。
- 不闪烁（≤3 次/秒）；不使用大面积阴影/模糊动画（API27 平板 GPU 保守）。
- 每帧主线程 <16ms：避免在动画期间做网络/数据库/重布局；RecyclerView 只动画可见 item；DiffUtil 大列表放后台线程。
- 动画可中断：快速连点/返回时取消旧动画，避免叠帧。
- 首启引导是唯一允许的长动画，且必须可跳过、可被 reduced-motion 跳过。

---

## 4. 实施计划（待确认后执行）

**Phase 0（约 1–2h）**：新增 `Motion` 工具（时长/曲线常量 + reduced-motion 判断）；无 UI 行为变化。
**Phase 1 Essential（约 1–2 天）**：E1 导航转场 → E3 卡片按压/ripple → E2 DiffUtil 列表 → E5/E9 提交 loading/防重复 → E4 reduced-motion → E6 Snackbar → E7/E8 选中态。
**Phase 2 Recommended（约 1–2 天）**：R1/R2 刷新与空态过渡 → R3 计划模式切换 → R4 chips → R5 intro 跳过/首启判断 → R6/R7/R8/R9 视觉 token 收口 → R11 触觉。
**Phase 3 Optional（按剩余时间）**：O2 月历 morph、R12 成功动效、O4 数字滚动；O1 共享元素转场默认不做。

### 每项完成后的验证

1. `.\gradlew.bat testDebugUnitTest lintDebug checkContracts :app:assembleDebug` 全绿；
2. 模拟器 + 小米平板（USB，API27）各跑一遍：tab 切换、push/pop、返回手势、旋转、列表增删、网络失败、重复点击；
3. 系统设置动画缩放 = 0 / 1 / 2 各验证；
4. TalkBack 抽查关键路径；
5. `adb shell dumpsys gfxinfo <pkg>` 检查掉帧；
6. 关键页面截图前后对比，确认未改变配色/布局结构。

### 主要风险

- Fragment 转场可能影响状态恢复/backstack（风险低但必须回归旋转与返回）；
- DiffUtil 的稳定 id 必须唯一（跨来源 id 可能重复，需带 kind 前缀）；
- 动画期间数据刷新可能导致 RecyclerView “Inconsistency detected”（先 diff 再动画）；
- Snackbar 替换 Toast 时要注意错误信息不被自动消失掩盖（错误用更长时长/带重试）；
- 平板 API27 较老，避免过度使用 elevation/shadow 动画。

---

## 5. 需要你拍板的 5 个问题

1. **导航转场**：同意“tab fade-through + push/pop shared-axis X”这一套吗？（最稳、最接近 Material 规范）
2. **反馈方式**：把 8 处 Toast 统一改成 themed Snackbar，是否接受？
3. **触觉反馈**：是否允许在收藏/锁定/删除确认时使用系统轻触觉（默认关闭自定义振动）？
4. **首次引导**：是否允许“仅首次完整播放猫猫视频，后续启动缩短/可点跳过”？（演示时可能想保留完整版，可加开关）
5. **视觉 token 收口**：字体/间距/圆角统一属于纯视觉调整，是否允许修改现有 padding/字号（不改变布局结构与交互）？

确认后我按 **Phase 1 → Phase 2** 顺序逐项实施，每完成一组就回归构建、测试与真机检查，再向你汇报实际验证结果。


---

## 6. Implementation status — round 1（2026-11-26，已实施并验证）

已落地（Phase 1 核心 + 已确认的第 2–4 项决策）：

- **E1 导航转场**：AppNavigator 增加 setCustomAnimations：主 tab 用 fade-through（200/140ms），push/pop 用 shared-axis X（220/160ms）；系统动画缩放为 0 时不设置转场。
- **E2 列表连续性**：CatalogAdapter 改为 DiffUtil + stable id + 180ms DefaultItemAnimator，分页 append/删除/切换来源不再整表闪跳。
- **E3 卡片反馈**：菜谱卡片根布局加 selectableItemBackground ripple，并纳入 PressBounce（scale 0.94 既有 spring）；点击附带轻触觉。
- **E4 减少动态**：新增 Motion token（MICRO/SHORT/MEDIUM/LONG + enabled()）；PressBounce 在动画关闭时不再缩放；导航转场与 intro 均受控。
- **E6 统一反馈**：新增 themed Snackbar（Feedback），替换 8 处 Toast（保存/错误/邀请码复制/偏好/计划冲突等），错误使用深色底 + 长时长。
- **触觉（决策 3）**：新增 Haptics，已接入收藏、计划锁定（KEYBOARD_TAP），遵循系统设置。
- **首启引导（决策 4）**：SharedPreferences 记忆首启；仅首次播放完整猫猫视频，之后启动直接进入；首次可点击任意位置跳过（180ms 淡出）；动画关闭或已看过时跳过；验证：首次点击跳过后进入登录页，force-stop 重启无 intro。
- **导航选中（部分 E8）**：底部/侧栏选中使用 Material 默认 state 动画；转场统一由 E1 承担。

**验证**：	estDebugUnitTest（135 项）+ lintDebug + checkContracts + ssembleDebug 全绿；小米平板（MI PAD 4 PLUS，USB）安装新版，nimator_duration_scale=0 与 1 均无崩溃、无 FATAL/Fragment 不一致日志，首启跳过与重启跳过均验证通过。

**下一批（待继续）**：
- E5 提交按钮 loading 态与全局防重复封装；
- E7 完整状态过渡（收藏/锁定/来源 chip 的 120–180ms 动效覆盖所有入口）；
- E8 侧栏/底栏选中 indicator 的 scale 过渡；
- R1/R2 计划/食记/冰箱刷新与空态 crossfade；R3 个人/家庭 indicator 滑动；R4 ChipGroup 单选收口；
- R6/R7/R8/R9 视觉 token（对话框、字体、间距、圆角、图标）统一；
- R5 首启策略如需“演示模式强制完整播放”可加开关；O2 月历 morph 等 optional。


## 7. Implementation status — round 2（2026-11-26，已实施并验证）

- **E5/E9 提交 loading + 防重复**：新增 SubmitButton（保存时禁用、alpha 0.7、文案切到「处理中…」，恢复原文案），已接入个人菜谱、家庭菜谱、偏好保存、个人资料保存。
- **R4 ChipGroup 单选收口**：菜谱来源 / 分类 chip 改为 ChipGroup singleSelection + selectionRequired，删除手写取消选中循环，选中状态由 Material 管理（默认带状态动画），分类恢复逻辑同时修正。
- **R3 计划模式切换**：个人/家庭按钮的背景色改为 160ms ValueAnimator 过渡（reduced-motion 时直接切），替代瞬间换色。
- **决策 5 视觉 token（第一批）**：新增 civa_dimens.xml（spacing 4/8/12/16/20/24、radius 12/20/24/28、button height、touch target）；按钮样式改用 token；新增 CivaText.DialogTitle（22sp 手写体、居中），自绘资料/语言弹窗标题统一使用。
- 全量验证：135 项 Android 单测 + lint + checkContracts + assembleDebug 全绿；小米平板安装并启动正常，无 FATAL/Inconsistency 日志。

**仍待下一批**：E7 全入口状态动效、E8 侧栏/底栏 indicator 过渡、R1/R2 刷新/空态 crossfade、R6 全部对话框统一、R7/R8/R9 全局字体/间距/圆角/图标收口（本轮只建 token 与新组件），以及 Optional（月历 morph、数字滚动）。
