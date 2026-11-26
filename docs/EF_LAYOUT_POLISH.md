# EF 布局微调交接（2026-11-25，第三轮）

实际工程仍为 `C:\Users\18192\Desktop\civa-arch-main\civa-arch-main`。
本轮只修正用户三张截图指出的位置/大小，延续可爱、温暖的烘焙小猫风格。
视频开场、声音、资料存储、默认头像、菜谱和排餐功能沿用前两轮实现。

## 当前布局

1. 登录页气泡从 16sp 放大到 19sp，横向位置改为依小猫位置计算，向猫的脸部靠近并稍微下移。
   改动位于共享 BakeryWelcomeLayout，因此家庭页气泡也同步采用较大字号和贴近小猫的位置。
2. 登录的标题、用户名、密码、登录/注册按钮整体约上移 48dp：保留 ScrollView 和居中表单，
   调整内部顶部/底部留白到 28dp/116dp。没有用负位移把输入框移到滚动范围外；
   小窗口或键盘打开后仍可滚动。家庭表单自身留白不变。
3. 今天页改为左标题、右小猫，普通/宽屏布局同时调整。给小猫区域增加 12dp 内边距，
   图片随容器缩小；保留原点击区域、按压回弹、喵气泡、声音与三餐卡片布局。
4. 菜谱小猫由 84×88dp 容器放大到 112×110dp，在标题区右下对齐，并向搜索栏上沿靠近。
   为它保留右侧 116dp 空间，来源入口仍可横向滚动。图像自带透明边缘，因此有 20dp 下移补偿；
   仅标题区及“标题+搜索”组合容器放开内部裁剪；页面外层保持裁剪，防止餐盘被截断的同时，
   也防止菜谱卡片越出列表区域盖住分页文字。

这只是显示布局变更：没有 API、公共签名、用户数据格式、权限、依赖或服务端改动。
旧 RecipeViewCallbacks 闪退修复和本地头像/昵称覆盖行为未变。

## 文件清单

| 修改文件 | 最终 SHA-256 |
| --- | --- |
| `core-ui/src/main/java/edu/xjtlu/civa/core/ui/BakeryWelcomeLayout.java` | `d6858b929f29f94ddf7470b8bb84ce740c8bbf07006adc874e58657ca726e2b4` |
| `feature-ef/src/main/res/layout/ef_fragment_login.xml` | `d38e4d4aa5cb56da0fac08a4c9e04d4f6b9b457f79dbf0a951854e267dc47b56` |
| `feature-bd/src/main/res/layout/bd_fragment_home.xml` | `5393840bd444e2d2a97adb8eac431a588382ab3d369c09430f63edba20da57df` |
| `feature-bd/src/main/res/layout-w600dp/bd_fragment_home.xml` | `bcfcc9b0ffd8dfb6fe2f9d274a1208e3480a04d48b12deb0cc302193e5f2ba2e` |
| `feature-ac/src/main/res/layout/ac_fragment_catalog.xml` | `c4db16cc7e8042edb498eebee2f7b734805656067ef09a541d56c0310218fdd3` |

[完整差异](verification/layout-polish/source-changes.diff)和
[修改前后哈希](verification/layout-polish/file-hashes.json)可供小组逐文件审阅。
没有 Git 历史的本地下载包不代表已推送远端或与最新 main 合并；本轮未修改其他工程副本。

## 验证

正式命令：`gradlew.bat :app:assembleDebug testDebugUnitTest lint --console=plain`。
结果：BUILD SUCCESSFUL；120 项单元测试，0 失败、0 错误；lint 0 错误、81 警告。

API 37.1 模拟器：1080×2400 手机尺寸，以及 2400×1600 宽窗口。
独立临时测试包里验证了真实登录、退出返回登录；确认两种布局均为标题在猫左侧。
静态截图已逐张观察，未新增镜像实现的单元测试。

贴边检查先发现容器裁剪了下移的餐盘，导致搜索栏上方仍留空。
全页放开裁剪又会让列表卡片越出边界，因此最终用局部“标题+搜索”组合容器处理插画溢出，
保持列表和分页原有的显示边界。以截图中实际可见的深色餐盘轮廓测量，最终间隙为：
- recipes-phone: 0 px。
- recipes-wide: 0 px。

原始测量：[pan-spacing.json](verification/layout-polish/pan-spacing.json)。
正式包已覆盖安装，安装前后 3 个既有本地排餐/照片/资料文件哈希一致；未清空或卸载正式应用。

独立测试包已移除；模拟器尺寸已恢复。安装记录见
[production-install.json](verification/layout-polish/production-install.json)。

## 截图

- [login-phone](verification/screenshots/layout-polish/login-phone.png)
- [today-phone](verification/screenshots/layout-polish/today-phone.png)
- [recipes-phone](verification/screenshots/layout-polish/recipes-phone.png)
- [login-wide](verification/screenshots/layout-polish/login-wide.png)
- [today-wide](verification/screenshots/layout-polish/today-wide.png)
- [recipes-wide](verification/screenshots/layout-polish/recipes-wide.png)
- [production-login](verification/screenshots/layout-polish/production-login.png)

## 接手提醒

- `BakeryWelcomeLayout` 的气泡是共享组件；调整它会同时影响登录和家庭欢迎页。
- 菜谱插画要保留局部组合容器不裁剪的设置和透明边缘补偿；页面外层仍需裁剪列表。
- 本轮未遍历所有机型或超大字体；来源行在窄屏横向滚动，长登录/注册表单仍通过 ScrollView 访问。
- 只做本地 UI 调整和验证，没有 GitHub 推送；后端全套集成测试未重跑（本轮没有服务端更改）。

测试结束：本轮临时启动的后台模拟器已正常关闭，未重置 AVD；正式包和用户本地数据保留。Android Studio 运行 app 可正常打开可见模拟器，既有后端与数据库未停止。


> 后续第四轮位置与新版绿幕动画见 [EF_GREEN_INTRO_HANDOFF.md](EF_GREEN_INTRO_HANDOFF.md)；本文保留第三轮历史值。
