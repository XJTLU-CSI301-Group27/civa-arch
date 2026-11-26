# EF 烘焙小猫 UI 交接（2026-11-25）

> 第二轮更新：当前配色、插画、默认头像和视频开场请看 [EF_CAT_REFRESH_HANDOFF.md](EF_CAT_REFRESH_HANDOFF.md)。以下保留第一轮实现记录；与第二轮不同的视觉/时间线描述不再代表当前界面。

本次修改的实际工程是 `C:\Users\18192\Desktop\civa-arch-main\civa-arch-main`。
整体视觉继续使用项目既有的**可爱、温暖、烘焙小猫**风格；没有建立第二套主题或 Android 工程。
接手前请先读 `AGENTS.md`、`PRODUCT_RULES.md`、`DATA_CONTRACT.md`、`UI_RULES.md`、
`INTEGRATION_RULES.md` 和 `TEST_PLAN.md`。本文补充本轮 EF 页面、共享视觉组件及 AC 关闭入口的变更。

## 完成范围与验收入口

| 用户要求 | 实现与检查位置 |
| --- | --- |
| 登录/注册上深棕、下米棕，表单移到下半部，小猫扒住边缘打招呼 | `LoginFragment` + `ef_fragment_login.xml`；共享 `BakeryWelcomeLayout`，气泡“一起吃好每一天” |
| 6–7 秒纯色开场：小猫跑入、坐下、叫一声，出现“喵”气泡 | `AppIntroView`，总长 6600ms；使用真实本地 OGG 音效 |
| 开场后下方面板升起、小猫出现、表单渐显 | `BakeryWelcomeLayout.playEntrance()`，约 1100ms；原来的登录/注册事件不变 |
| 创建/加入家庭采用同一布局 | `HouseholdFragment` + `ef_fragment_household.xml`；气泡“和家人共享菜谱和冰箱吧”；原有家庭接口不变 |
| 菜谱详情左上角增加小叉叉，返回默认菜谱列表 | `RecipeCatalogDetailFragment` + `ac_fragment_catalog_detail.xml`；公共、个人、家庭共用这一入口 |
| 退出后回到有入场动画的登录页，不再播放长开场，也不显示底栏 | `MainActivity` 的页面装饰和导航显隐处理；没有新增 Activity |
| 我的页与编辑资料 | `ProfileFragment`、`ProfileEditDialog`；左侧悬浮头像、右侧小猫、用户名/ID、家庭卡片；点击头像使用系统文件选择器 |
| 首页小猫点击同时出现气泡与喵声 | 共享 `BakeryCatStage` 调用 `MeowSound`，保留原有按压回弹 |

所有新增交互文字使用带模块前缀的字符串资源。标题、欢迎区小标题、按钮和气泡复用
ZCOOL KuaiLe；长正文保留共享可读正文字体。叉叉图形较小，但保留约 44–48dp 的点击区域。

## 动画和音效的生命周期

- 冷启动开场：0–2000ms 从侧面跑入；2000–2850ms 坐下回弹；约 4350ms 播放喵声并出现气泡；
  5650ms 开始收起气泡；6250–6600ms 淡出，随后开始欢迎页入场。
- 开场仅由 `MainActivity` 创建，不由 Login 创建。退出登录只是切换 Login，因此只播放短入场。
- 保存开场起始时间、结束状态、已喵叫状态，页面重建不会重新播放完整开场或重复叫一声。
- `MeowSound` 使用一个 `SoundPool` 流，音频加载完再同步触发气泡。重复点击会替换上一声，
  页面离开时释放声音和回调；不修改系统音量，不需要联网或录音权限。
- 首页已有 PressBounce 的刚度、阻尼、缩放与阴影反馈未改动。

## 用户名与头像：本地显示资料契约

用户确认想显示注册时填写的名字，例如 `LYFFAY`，不是截断的用户 UUID。

1. 首次读取现有 `AuthRepository.refreshMe()`，优先 `User.displayName`，为空再用 `User.username`。
2. 编辑弹窗允许修改页面显示名称；保存后优先显示本地名称。**不修改登录账号、密码或服务端 User**。
   当前后端没有修改用户名/头像的接口，因此这些修改只保存在本机，不会同步给另一台设备。
3. 按已认证 `userId` 的 SHA-256 摘要隔离目录：`files/profiles/<scope>/profile.json`。
   元数据版本为 1：`version`、`scope`、`name`、`avatar`；`AtomicFile` 写入，校验版本和所属账号。
4. 名称 trim 后要求 1–60 个 Unicode 码点；登录标识不变。空值/过长名称不会保存。
5. 默认头像复用共享小猫图案。用户选择的图片在工作线程读取，解码尺寸限制约 640px，
   支持 EXIF 旋转归正，压缩为 JPEG 后复制到当前账号私有目录。不会长期依赖外部 URI 读取权限。
6. 暂存照片使用带账号 scope 的缓存文件；提交时验证路径和账号，防止保存到另一个用户资料中。
   当前编辑草稿通过 Fragment 状态保存；点叉叉关闭不提交名称或头像。
7. 元数据和头像没有上送服务器，不用于家庭共享，也不作为鉴权依据。后续接入真正的资料更新接口时，
   请新增并评审接口契约，再迁移本地覆盖值；不要直接改变 AuthRepository 的登录语义。

项目原有 `SessionStore` 是内存会话：杀进程后需要重新登录。这是现有行为，本轮未变更；
重新登录同一账号后，已保存的本地显示名称和头像仍然可用。

## 文件与模块边界

完整文件变更及 SHA-256 见 [EF_BAKERY_UI_CHANGELOG.md](EF_BAKERY_UI_CHANGELOG.md)。主要职责如下：

| 模块 | 文件/职责 |
| --- | --- |
| app | `AppIntroView.java` 开场；`MainActivity.java` 入场调度、系统栏与登录/家庭底栏隐藏；`app_bakery_strings.xml`、`app_intro_ids.xml` |
| core-ui | `ChefCatView.java` 原创代码绘制厨师猫；`BakeryWelcomeLayout.java` 共享两色欢迎布局；`MeowSound.java` 声音管理；`BakeryCatStage.java` 首页点击接线 |
| core-ui 资源 | `raw/civa_meow.ogg`、`civa_colors.xml`、`civa_styles.xml`、`civa_ui_ids.xml` |
| feature-ef | Login/Household XML 与 Fragment；Profile XML 与 Fragment；新增 `ProfileEditDialog.java`、`LocalProfileStore.java`、`ProfileIdentity.java`；配套 ef_* 图标/ID/文案资源 |
| feature-ef 测试 | `ProfileIdentityTest.java`：账号隔离、名称/Unicode 边界、头像文件名校验，共 8 项；build.gradle 仅添加已有版本目录中的 JUnit 测试依赖 |
| feature-ac | `catalog/RecipeCatalogDetailFragment.java`、`ac_fragment_catalog_detail.xml`、`ac_strings.xml`：详情小叉叉入口 |
| contracts | `public-surface.draft.json` 登记共享 UI 类和框架反射 Dialog 为 internalSymbols；未改现有对外方法签名 |

没有改生成 API、服务端接口、数据库结构、家庭数据规则、排餐/库存/营养逻辑。
`ProfileHost`、`LoginHost`、`HouseholdHost` 接口保持不变；没有 feature 互相依赖。
之前 `RecipeViewCallbacks` 防止离开详情后异步回调导致闪退的修复仍保留。

共享 `CivaText.Title` 补充 AppCompat 的 `fontFamily`，与原有 `android:fontFamily` 一起指定同一字体，
避免 Material 继承的字体覆盖可爱标题。新增 `CivaText.WelcomeSection` 和 `CivaWidget.WelcomeInput`
仍在 core-ui 定义，不在 feature 重复建立全局主题。

## 素材来源

- 新开场/欢迎区/个人卡片的厨师猫由 `ChefCatView` 使用 Canvas 原创绘制，复用项目色板，
  不是截图裁切或网络下载人物素材。
- 首页原有烤箱小猫图和默认猫头像继续复用已有资源，不覆盖原画。
- 喵声：**Meow**，作者 **IgnasD**，**CC0**；原始页面：
  <https://opengameart.org/content/meow>；原文件：
  <https://opengameart.org/sites/default/files/Meow.ogg>。
  原文件保存为 `core-ui/src/main/res/raw/civa_meow.ogg`，9787 字节，约 0.511 秒。
  未增加在线声音服务，后续更换音效时请同步维护作者和许可证记录。

## 验证结果

环境：JDK 17、项目 Gradle Wrapper 8.13、Android 模拟器 API 37.1。

```text
gradlew.bat :app:assembleDebug testDebugUnitTest lint --console=plain
BUILD SUCCESSFUL
120 tests, 0 failures, 0 errors
lint: 0 errors, 85 warnings
python -m unittest discover -s scripts -p 'test_*.py': 17 tests, OK
python scripts/check_contracts.py: contracts consistent
```

静态警告没有隐藏：app 22、core-ui 11、feature-ac 29、feature-bd 21、feature-ef 2。
feature-ef 的 2 项提示建议换用 AndroidX ExifInterface；此处选择平台接口以避免新增运行时依赖。
core-ui 警告为绘制/布局对象分配建议。构建还有 Gradle 9 兼容性提示；本项目仍用既有 8.13。

模拟器实测和截图索引见 [EF_BAKERY_UI_VERIFICATION.md](EF_BAKERY_UI_VERIFICATION.md)。
测试用独立临时包 `edu.xjtlu.civa.uiverify` 与专用本地测试账号进行，未清空原应用数据。
已覆盖安装并启动正式应用，原有 2 个本地文件安装前后校验一致；独立测试应用已移除。
正式包名仍是 `edu.xjtlu.civa`，测试 applicationId 覆盖脚本只保留在工作区临时目录，不进入工程。

音效已检查资源解码、音量波形及调用链；系统录屏本身不带应用声音，因此录屏不能替代真机听感检查。
测试时请打开设备媒体音量。静音/勿扰设置和不同设备的外放听感需要接手同学自行确认。

## 当前限制与后续接入

- 中文/英文按钮按本轮要求只展示，点击不切换语言；这不是完整国际化。
- 显示名称、头像为本地账号隔离保存；跨设备更新需要后端资料接口。
- 家庭设置仍进入原有创建/加入家庭页面，没有更改家庭管理业务。
- 登录需要现有本地后端运行。启动方式见 `LOCAL_RUN_HANDOFF.md`，无需改成假登录。
- 本目录是下载包，没有 Git 历史。本轮只修改指定本地工程，未推送 GitHub、未同步远端 main，
  也未修改 `civa-arch-main2`。小组集成前按仓库规则比较分支和最新 main，再跑同样检查。
- 没有为 UI 修改引入新的生产 API Key、数据库迁移或运行时依赖。
