# EF / BD 位置与绿幕开场交接（2026-11-25，第四轮）

实际工程：`C:\Users\18192\Desktop\civa-arch-main\civa-arch-main`。
延续统一的可爱、温暖、烘焙小猫风格。本轮只完成用户要求的登录表单上移、今天页标题靠近猫、
以及更新桌面绿幕视频。第三轮的菜谱插画贴边方案继续保留。

## 当前行为与改动边界

1. 登录表单顶部 / 底部留白从 28dp / 116dp 改为 20dp / 180dp，
   在同尺寸的完整手机窗口中，标题、用户名、密码、登录和注册整组再上移约 36dp。
   仍使用可滚动表单；小窗口内容超出时可滚动，没有把输入框负向移出滚动内容。
   家庭页的内部表单留白与共享欢迎动画未修改。
2. 今天页标题容器向右平移 24dp，靠近右边的小猫。普通和 w600dp 布局同步修改。
   平移的是文字容器，保留文本测量宽度与自动字号，避免直接平移子文字被父容器裁掉。
   猫咪区域、点击范围、按压回弹、喵声和三餐卡片原尺寸均不变。
3. 开场资源替换为用户本次更新的绿幕视频，猫探头、升起、CIVA 字样依原帧序播放。
   去掉绿色外景并合成 `civa_welcome_brown` 的 #97745A；保留白色厨师帽、围裙和字样。
   输出裁剪比改变，AppIntroView 按 720:780 比例测量，避免拉伸。
   原视频音轨完全移除；沿用共享 MeowSound，在视频约 3 秒处播放并显示喵气泡。
   冷启动播放完整开场，退出账号只播放登录页短入场动画。

没有 API、公共方法、数据版本、账号隔离规则、权限、Gradle 依赖或服务端代码修改。
只在指定本地下载包修改；未推送 GitHub、未与最新 main 合并，也未修改 main2 等副本。

## 修改文件

| 源码 / 资源 | 最终 SHA-256 |
| --- | --- |
| `feature-ef/src/main/res/layout/ef_fragment_login.xml` | `d8f4c00e6030f8bc7a77cdbd43ab9bd4497930a132c1ab9c25b4aa3d71c84a1f` |
| `feature-bd/src/main/res/layout/bd_fragment_home.xml` | `5a7a33bcb6b22952a1cf8aa7ebc11db9750022b3d9b44af584e3e7ce1727a343` |
| `feature-bd/src/main/res/layout-w600dp/bd_fragment_home.xml` | `d829dae815ac2184d41a7c16816c98dbbbebcfd417c42b7af0fb108787a1a9ec` |
| `app/src/main/java/edu/xjtlu/civa/AppIntroView.java` | `e5e84c205ca1204ec2531f0a51fcc8ac940d89ad0a1b9898efabff42e0f5b372` |
| `app/src/main/res/raw/app_cat_intro.webm` | `6bb2892c33996b27d88ba53436f59a5aa47af469f9bd00e4fe206b25fbe19166` |

[源码差异](verification/green-intro/source-changes.diff)、
[修改前后哈希](verification/green-intro/file-hashes.json)均已保留。
本轮维护文档：本文件、EF_LAYOUT_POLISH.md 的最新指引、UI_RULES.md、TEST_PLAN.md、HANDOFF.md。

## 视频来源与重建参数

- 输入：用户桌面 `猫咪动画制作需求.mp4`；源 SHA-256：`03982ea5b00aa2ac683e6f62363d3b6f720aa0d9008b08b774ac55ba50dbb9fc`。
- 输入 720×960 / 24fps；输出裁剪 x=0、y=120、宽 720、高 780。
  此范围保留完整动画主体、桌面线与 CIVA，并留出上方纯背景供播放器取样。
- 视频帧使用 OpenCV HSV 绿幕范围 H 30–48 / S 35–180 / V 110–255，3×3、sigma 0.45
  小幅边缘羽化，合成 BGR(90,116,151)；不是对白色人物区域做填充。
- 输出 VP8 / WebM / yuv420p、169 帧、24fps、7.042 秒、1,003,779 字节；只有视频流，没有音轨。
  ffmpeg 编码参数：libvpx、crf 8、b:v 1400k、deadline good、cpu-used 4。
- 资源随 APK 打包，运行时不要求原桌面 MP4、Python、OpenCV 或 ffmpeg；没有新增 Android 依赖。
- MediaPlayer 现有后台暂停、恢复、释放与错误兜底沿用；采样视频左上角纯背景来消除设备 YUV 色差。
  后续更换视频必须保持该角无主体，并同步调整宽高比。保留 VP8，前轮模拟器曾有 H.264 解码失败。
- 桌面原视频未修改，发布记录前再次核对哈希一致。

## 实际验证

正式构建命令：`gradlew.bat :app:assembleDebug testDebugUnitTest lint --console=plain`。
BUILD SUCCESSFUL，120 项单元测试，0 失败 / 0 错误 / 0 跳过；lint 0 错误、81 警告。
未为留白和视频资源增加镜像实现的单元测试；界面和媒体由实际设备验证。

API 37.1 模拟器：手机 1080×2400，以及 2400×1600 宽窗口。
- 相比第三轮截图，手机用户名、密码、登录按钮均上移 94px（密度 2.625，约 36dp）。
- 今天标题从 x=53px 变为 116px（+63px = 24dp）；猫区域坐标与第三轮完全一致。
  两个窗口截图均确认完整标题可见、没有被容器裁剪，三餐卡片同时展示。
- 独立测试包通过真实后端登录、今天页猫咪点击、退出账号、宽屏登录输入检查。
  退出后没有长开场或底部导航；窗口测试后已恢复原尺寸。
- 测试包及正式包分别出现同进程 `video_started`、`video_completed`，没有 `video_fallback`。
  [实际录屏逐秒图](verification/screenshots/green-intro/intro-contact.png)确认猫与完整 CIVA 序列、喵气泡及登录衔接。
- 正式 `edu.xjtlu.civa` 已覆盖安装；安装前后 3 个已有排餐 / 照片 / 资料文件哈希一致。
  未清空或卸载正式应用；只移除本轮临时测试包。原本运行的模拟器保持开启。

过程中的失败与处理：测试脚本在实体键盘模式下用返回键收起键盘，实际退出了页面；
删除该测试误操作后继续验证。随后登录提示无法连接服务，使用已有
`scripts/start-native-local.ps1` 恢复同工程的开发后端与原数据库，再次真实登录通过。
这些不是用模拟登录代替成功；未改动账号、数据库密码或重置数据库。服务保持运行。

证据：[检查计数](verification/green-intro/check-results.json)、
[构建日志](verification/green-intro/final-build.log)、
[位置对照](verification/green-intro/comparison.json)、
[测试播放日志](verification/green-intro/player-check.log)、
[正式播放日志](verification/green-intro/production-player.log)、
[安装与保留数据记录](verification/green-intro/production-install.json)。

覆盖限制：没有重跑服务端全套集成测试；未穷举全部机型与超大字体。
录屏不含系统音频；保留并触发了既有喵声实现，真实设备扬声器响度需接手者按媒体音量试听。

## 截图

- [手机登录](verification/screenshots/green-intro/login-phone.png)
- [手机今天](verification/screenshots/green-intro/today-phone.png)
- [宽屏登录](verification/screenshots/green-intro/login-wide.png)
- [宽屏今天](verification/screenshots/green-intro/today-wide.png)
- [正式包登录](verification/screenshots/green-intro/production-login.png)
