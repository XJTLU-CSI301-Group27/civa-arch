# 烘焙小猫 UI 验证记录（2026-11-25）

本次使用独立测试包和专用账号，避免在原应用真实账号上注册、退出或修改资料。
设备：API 37.1 模拟器；竖屏 1080×2400，宽屏 2400×1600。检查后恢复原始屏幕尺寸。

## 已执行

| 场景 | 实际结果 |
| --- | --- |
| 冷启动 + 欢迎页 | 录屏观察跑入、坐下、喵气泡、过渡到登录；没有底栏 |
| 登录、注册、家庭创建 | 使用真实本地后端成功，新注册账号显示自己的注册名称 |
| 退出 | 短登录入场动画正常；未出现长开场或底栏 |
| 语言弹窗 | 中文/英文按钮按要求不动作；X 关闭回到个人页 |
| 本地资料 | 上传测试图片圆形展示；LYFFAY 保存并重开一致；切换账号、重启后重新登录仍保留；取消编辑不覆盖保存值 |
| 账号隔离 | 第二测试账号显示注册名称 30127 和默认猫头像，没有继承 LYFFAY 和照片 |
| 菜谱详情 X | 有小叉叉；点后回到默认公共/全部列表；随后切换今天页面正常 |
| 首页猫 | 点击截图捕获喵气泡，随后消失；与 SoundPool 同一播放入口接线 |
| 宽屏 | 登录、家庭、个人页及编辑弹窗已观察；长家庭表单可滚动，登录/家庭没有侧栏 |
| 编译/单元测试/lint | BUILD SUCCESSFUL；120 项单元测试无失败；lint 无错误、85 警告 |
| 契约工具 | 17 项自测通过；check_contracts.py 输出 contracts consistent |

## 截图

下列截图来自独立测试包；名字、ID、照片均为测试数据。开场截图从录屏中取帧。

- [开场小猫和喵气泡](verification/screenshots/bakery-onboarding/intro-meow-video.png)
- [登录竖屏（最终字体）](verification/screenshots/bakery-onboarding/login-ready.png)
- [注册表单](verification/screenshots/bakery-onboarding/register-ready.png)
- [家庭表单](verification/screenshots/bakery-onboarding/household-ready.png)
- [语言选择弹窗](verification/screenshots/bakery-onboarding/language.png)
- [我的页面（最终字体）](verification/screenshots/bakery-onboarding/profile-final.png)
- [第二账号默认头像与注册名称](verification/screenshots/bakery-onboarding/profile-second-account.png)
- [已保存头像和名称的编辑弹窗](verification/screenshots/bakery-onboarding/profile-editor-saved.png)
- [菜谱详情关闭入口](verification/screenshots/bakery-onboarding/recipe-detail-close.png)
- [关闭后默认列表](verification/screenshots/bakery-onboarding/recipe-returned-list.png)
- [首页点击喵气泡](verification/screenshots/bakery-onboarding/today-meow.png)
- [登录宽屏](verification/screenshots/bakery-onboarding/login-wide.png)
- [家庭宽屏，可继续向下滚动](verification/screenshots/bakery-onboarding/household-wide.png)
- [个人页宽屏](verification/screenshots/bakery-onboarding/profile-wide.png)
- [编辑弹窗宽屏](verification/screenshots/bakery-onboarding/profile-editor-wide.png)

## 边界与未覆盖项

- 录屏不含应用音轨，已检查原 OGG 可解码且有非零音量，并检查声音/气泡共用播放事件；
  没有把这等同于人工确认真机外放听感。请在真机打开媒体音量后再听一次。
- 首次菜单加载出现一次网络失败，重新进入后接口、列表、详情与关闭流程正常；
  本轮没有修改网络错误处理。自动化中也出现过输入法组合文字、开场未结束即取控件、
  UIAutomator 无法等待空闲等测试脚本问题，调整等待/输入方式后继续验证。
- 反复切换脚本中途遇到 UIAutomator 取树失败；随后已独立确认今天页面存活及猫交互。
  不把中断的循环记录为“完整多轮通过”。之前 8 项异步回调回归单元测试仍包含在 120 项中。
- 未验证所有真实设备、超大系统字体、所有图片格式、后台/旋转恰逢图片解码等排列组合。
  平台 EXIF 和绘制分配建议作为 lint 警告保留，没有抑制。
- 本轮未改服务端业务，未重跑整个服务端集成套件；登录/注册/创建家庭使用正在运行的真实本地服务。
- 未做 GitHub push/PR 或远端 main 同步：指定目录是下载包，没有 Git 历史。

原始构建记录： [final-build.log](verification/bakery-onboarding/final-build.log)，
[contract-tests.log](verification/bakery-onboarding/contract-tests.log)。

## 正式包安装与数据保护

已确认 APK 包名 `edu.xjtlu.civa`，采用覆盖安装并启动，没有清空或卸载正式应用。
覆盖安装前后，原应用的 2 个既有本地排餐/资料文件 SHA-256 完全一致；独立测试包已移除，
测试图片/录屏临时文件已从设备移除，模拟器恢复为 1080×2400。后端和数据库继续运行。

- [正式应用最终登录截图](verification/screenshots/bakery-onboarding/production-login-ready.png)
- [正式 APK 摘要与安装核验](verification/bakery-onboarding/production-install.json)
