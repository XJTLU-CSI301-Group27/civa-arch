# 第二轮插画/开场验证（2026-11-25）

## 实际执行与结果

```text
gradlew.bat :app:assembleDebug testDebugUnitTest lint --console=plain
BUILD SUCCESSFUL
120 unit tests; 0 failures; 0 errors
lint: 0 errors, 81 warnings
```

静态警告保留：app 22、core-ui 11、feature-ac 29、feature-bd 17、feature-ef 2。
本轮未隐藏警告，未新增运行时依赖。构建仍提示未来 Gradle 9 不兼容；项目使用原 8.13 Wrapper/JDK 17。

API 37.1 模拟器中，使用独立临时包和现有专用测试账号进行：

- 冷启动：实际视频启动和完整播放结束均有同进程日志，无 video_fallback；CIVA 动作/片尾可见。
- 处理后的 WebM 为 620×540、24fps、169 帧、7.042 秒；只有 VP8 视频流，没有原音频流。
- 登录标题/删除说明文字、纸纹、小猫上露位置；登录/家庭页面没有底栏；登录到真实后端成功。
- 今天页、菜谱页、我的页和默认头像均已逐张检查；删两句说明后今天页没有测量闪退。
- 语言选择标题和大字号按钮已截图；中文/英文保持占位不动作，叉叉关闭。
- 家庭页面共享新欢迎样式，原家庭入口可打开；编辑资料仍可打开并展示新默认猫头像。
- 退出后只有短登录入场，无视频；已录制短入场并逐帧观察向上出现的猫。
- 2400×1600 宽窗口下检查登录、菜谱、个人卡和语言弹窗；测试结束恢复为原 1080×2400。
- 正式包名确认是 edu.xjtlu.civa；覆盖安装后实际视频再次完整播放。
  安装前后原有本地排餐/照片/资料文件哈希相同；没有清空/卸载原应用。
  已移除本轮临时测试包及测试录屏文件。详情见 production-install.json。

## 验证中遇到的问题及处理

- 首次安装时模拟器未启动；只启动已有 AVD，没有重置数据。
- 初始 H.264 编码在此模拟器报 c2.goldfish 解码/缓冲错误，触发了备用插画。
  这次尝试不算视频验证通过；最终换用 VP8，增加明确的播放开始/完成/备用日志，
  并分别在测试包和正式包确认视频真正播放完成。
- 视频背景与原生纯色存在少量解码 RGB 偏差；最终设定共享基准棕色，并复用 16×16 取样图同步开场/系统栏背板。
  最终正式包截图的画面外部和视频内部 4 个背景点均为 RGB(151,117,86)，
  [背景一致性检查](verification/cat-refresh/background-check.json)已通过。

## 证据

- [intro-video-early](verification/screenshots/cat-refresh/intro-video-early.png)
- [intro-video-meow](verification/screenshots/cat-refresh/intro-video-meow.png)
- [intro-video-civa](verification/screenshots/cat-refresh/intro-video-civa.png)
- [login-ready](verification/screenshots/cat-refresh/login-ready.png)
- [login-after-logout](verification/screenshots/cat-refresh/login-after-logout.png)
- [today-ready](verification/screenshots/cat-refresh/today-ready.png)
- [recipes-ready](verification/screenshots/cat-refresh/recipes-ready.png)
- [profile-ready](verification/screenshots/cat-refresh/profile-ready.png)
- [language-ready](verification/screenshots/cat-refresh/language-ready.png)
- [avatar-default](verification/screenshots/cat-refresh/avatar-default.png)
- [household-ready](verification/screenshots/cat-refresh/household-ready.png)
- [login-wide](verification/screenshots/cat-refresh/login-wide.png)
- [recipes-wide](verification/screenshots/cat-refresh/recipes-wide.png)
- [profile-wide](verification/screenshots/cat-refresh/profile-wide.png)
- [language-wide](verification/screenshots/cat-refresh/language-wide.png)
- [production-intro](verification/screenshots/cat-refresh/production-intro.png)
- [production-login](verification/screenshots/cat-refresh/production-login.png)

日志：[正式构建](verification/cat-refresh/final-build.log)、
[测试包播放](verification/cat-refresh/player-check.log)、
[正式包播放](verification/cat-refresh/production-player.log)、
[安装与数据保护](verification/cat-refresh/production-install.json)。

## 尚未覆盖

- 真机外放听感、所有厂商播放器以及所有系统字体倍率未逐一测试。声音资源仍是已验证的本地喵声；
  系统录屏不带应用音轨，不用录屏代替听感验证。
- 没有在本轮重跑服务端全套测试或创建远端分支：服务端/接口无修改，且此处是无 Git 历史的下载包。
- 语言切换仍未实现；名称/头像继续本地按账号保存，跨设备同步不在本轮范围。

测试环境清理：本轮临时在后台启动的 Medium_Phone 模拟器已正常关闭，未重置 AVD；正式应用安装和本地数据均保留。回到 Android Studio 运行 app 即可正常打开可见模拟器。既有本地后端与数据库未停止。
