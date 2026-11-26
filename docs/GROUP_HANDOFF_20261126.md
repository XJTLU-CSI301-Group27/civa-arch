# 小组完整源码交接（2026-11-26）

本次为当前本地工程的完整源码快照，包含Android全部模块、后端、统一接口契约及生成规则、数据库迁移、测试、资源、启动脚本和交接记录。整体UI风格：可爱、温暖、烘焙小猫。没有上传GitHub、创建PR或整合最新main的声明。

## 阅读和协作顺序

1. AGENTS.md、PROJECT_DOC.md、OPEN_QUESTIONS.md。
2. docs/PRODUCT_RULES.md、DATA_CONTRACT.md、UI_RULES.md、INTEGRATION_RULES.md、TEST_PLAN.md。
3. docs/HANDOFF.md以及以下最新交接；修改自己负责的模块后，维护自己的组别MD并记录影响的共享文件、接口、迁移和实际验证。历史记录与最新规则冲突时，以最新用户决策和对应交接为准。

| 当前改动 | 交接入口 |
| --- | --- |
| 小米平板登录圆角、固定底部导航、三餐竖排、启动不弹键盘、今天标题字号恢复 | [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md) |
| 今天三餐按个人账号保存，创建/加入/退出家庭不再切换计划，旧草稿兼容 | [BD_PERSONAL_PLAN_HANDOFF.md](BD_PERSONAL_PLAN_HANDOFF.md) |
| 真机连接、实际安装地址核对、本机Studio调试地址与模拟器切换 | [EF_USB_TABLET_HANDOFF.md](EF_USB_TABLET_HANDOFF.md) |
| 家庭成员平等、永久邀请码、最后成员退出注销 | [EF_SHARED_FAMILY_HANDOFF.md](EF_SHARED_FAMILY_HANDOFF.md) |
| 修改家庭名与保存 | [EF_FAMILY_NAME_HANDOFF.md](EF_FAMILY_NAME_HANDOFF.md) |

## 接收者首次运行

1. 解压到新目录，在Android Studio打开 **civa-arch-main**（这一层包含settings.gradle），不是app子目录。不要覆盖自己已有数据库配置或开发分支。
2. 准备 **JDK17**、Android SDK与模拟器，打开Docker Desktop等待引擎就绪。
3. 本次私有小组ZIP内的.env已配置新环境使用的数据库密码与用户授权共享的DeepSeek Key；默认数据库为Docker的localhost:5432/civa。它不是发送者正在使用的本机数据库配置，不直接用来启动原生PostgreSQL。
4. 双击根目录 **start-civa.bat**，等待Backend ready；首次需要联网下载依赖/镜像并构建。再在Studio选择app与模拟器Run。新数据库须重新注册账号。
5. USB真机：电脑后端运行后连接并允许USB调试，双击 **start-civa-usb.bat**，它配置转发、真机地址并安装；维持USB与后端运行。转发丢失时可在项目PowerShell运行 `./scripts/start-usb-device.ps1 -CheckOnly`。
6. USB启动器会将本机local.properties的 `civa.debugApiBaseUrl` 设为 `http://127.0.0.1:8080/`，Studio后续Run沿用；切回模拟器时移除该键或改为 `http://10.0.2.2:8080/`，再Sync/Run。未设置本地键的其他电脑默认模拟器；显式CIVA_API_BASE_URL优先，release仍需显式HTTPS。

DeepSeek Key仅在包内后端.env，不在APK/Java源码。按用户授权提供给小组，请勿将带Key的.env推到公开GitHub。API实际可用仍取决于Key状态/余额、网络和后端；本次打包不额外调用收费API验证。家庭邀请码加密私钥与DeepSeek Key是不同用途，新的后端首次运行自动创建自己的私钥；组员后续须连同自己的数据库一起备份。

## 包含和排除

包含当前完整源码、gradle wrapper、后端迁移与文档/验证记录。排除个人SDK/IDE设置、构建缓存、原有数据库、会话账号、照片私有目录、旧家庭邀请码私钥和私有测试夹具。包内.env为本次授权生成，不能将源工程已有.env或整份个人配置当作公共代码上传。ZIP根目录PACKAGE_MANIFEST.json记录每个文件SHA-256，已逐项解压读取核对。

已在手机尺寸和MI_PAD_4_PLUS/API27验证记录中的场景；不宣称所有Android设备、系统字体、分屏或所有线上服务已完美验证。各轮实际测试命令、结果和限制见对应交接，不把历史测试说成打包时全部重跑。原开发机正在运行的后端/数据库、平板App和资料不会因打包改变。
