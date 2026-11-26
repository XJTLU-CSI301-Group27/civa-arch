# 小米平板 USB 网络调试交接（2026-11-25）

用户在小米平板注册时显示无法连接服务。实际检测设备为 MI_PAD_4_PLUS / Android API27，电脑后端8080与数据库55433正常，构建仍使用模拟器专用10.0.2.2。

本轮通过 USB 配置 adb reverse tcp:8080 tcp:8080，以 -PCIVA_API_BASE_URL=http://127.0.0.1:8080/ -PdevStackOff :app:assembleDebug 构建真机调试包，adb install -r 覆盖更新成功并打开。没有卸载、清空应用、提交用户账号密码或创建测试账号；没有改动业务源码、API、数据库、DeepSeek配置或默认模拟器地址。

## 下次连接步骤

1. 先启动已配置的电脑后端（项目的 start-civa.bat；本机原生数据库环境继续用 start-civa-local.bat）。
2. USB连接平板并允许USB调试，然后双击项目根目录 start-civa-usb.bat。
3. 脚本会配置转发，使用JDK17构建localhost真机版本、保留数据覆盖安装并打开App。保持数据线和电脑后端运行。
4. 重新插线或重启后转发可能失效，再运行脚本即可。不要直接用默认Android Studio Run覆盖成10.0.2.2模拟器版本后期待真机直接连接。

多个真机时可传 -Serial；单独 -CheckOnly 仅检查后端、设备并设置转发，不重建或安装。脚本从当前SDK配置寻找ADB，不写死个人设备序列号；检测JDK17，不关闭别的进程。安装失败不自动卸载应用。

## 实际验证与限制

- 真机安装更新成功；USB反向转发存在。
- 从平板自身curl访问电脑后端：GET /api/v1/me 返回401（未提交凭据，说明服务可达），POST注册空JSON返回400（参数校验，未创建账号）。这证明登录/注册网络通道可达，不把401/400说成账号登录成功。
- 首次netcat诊断因工具的输入/EOF返回空输出，后改curl；首次curl注册诊断因ADB远端参数引号导致415，修正Content-Type引用后得到预期400。不是注册业务失败或新网络故障。
- 新启动脚本已用 -CheckOnly 在当前连接设备验证。脚本所执行的构建/安装/转发步骤也已逐项实际执行。
- 未替用户提交注册/登录表单，未验证DeepSeek线上调用，不宣称已完成用户账号登录。未运行局域网/公网/无线离线使用验证。
- 默认模拟器/正式HTTPS构建规则不变。本轮没有重新打包前面的交接ZIP；脚本在当前工程中，原ZIP已有手动真机配置说明（LOCAL_RUN_HANDOFF）。下次制作交接快照应带本脚本。

[真机连接记录](verification/usb-tablet/connection-check.json) · [构建日志](verification/usb-tablet/build.log) · [变更审计](verification/usb-tablet/changes.json)


## 后续界面适配（2026-11-26）

最新平板布局已更新，网络/USB使用方式沿用本文。阅读 [EF_TABLET_LAYOUT_HANDOFF.md](EF_TABLET_LAYOUT_HANDOFF.md)；覆盖登录圆角、底部导航、三餐竖排。旧ZIP仍须重新制作才包含最新修改。


## 2026-11-26 再次无法登录的现场恢复

用户再次遇到网络提示。实测ADB设备在线、正常后端8080/数据库55433监听，但 `adb reverse --list` 为空。恢复 `adb -s <serial> reverse tcp:8080 tcp:8080` 后，从平板curl访问 `/api/v1/me` 返回401（未提交身份凭据的预期响应），证明服务链路已恢复；没有代用户提交账号、没有清空App、没有修改源码或服务器配置。不把连通性验证说成用户登录成功。

USB转发为调试连接状态，重连/ADB重启后需要恢复；项目 `scripts/start-usb-device.ps1 -CheckOnly` 可只检查后端并重设转发，不重建或安装。默认 `start-civa-usb.bat` 包含构建安装过程，适合代码更新后使用。当前本地USB调试仍依赖电脑后端及连接；不是DeepSeek密钥问题。现场记录：[reconnect-20261126.json](verification/usb-tablet/reconnect-20261126.json)。


## 真正影响登录的安装地址及防复发修复（2026-11-26，当前）

单独恢复USB转发后用户仍无法登录。进一步从平板拉取当前实际安装的APK，发现SHA256为254af791388a50906a2fb549d687288b1de6f1f6a841dbf0e0564263e0cbc918，DEX包含 `http://10.0.2.2:8080/`；此前已验证的真机包为b4780894e5c30557b79a455f85a1c7317e1d84822ea3c5b656ee6633e2ca3626，实际地址为 `http://127.0.0.1:8080/`。当前转发/后端正常，但模拟器专用地址在真机不适用。安装来源未确定，不擅自归因为用户做了哪一步。此前curl401只证明设备通道可达，不能证明当前App配置正确。

已把已验证真机包 `adb install -r` 覆盖回平板，再次拉取安装包核对SHA256完全一致，排餐/照片/资料文件前后哈希一致；没有清空或卸载原App、没有代提交账号。用户随后明确确认“已经能登录”。

### 防止Android Studio Run重新构建成模拟器地址

- `app/build.gradle` 增加**本机debug配置**：地址优先级为显式 `-PCIVA_API_BASE_URL` → `local.properties` 的 `civa.debugApiBaseUrl` → 原模拟器默认 `http://10.0.2.2:8080/`。所有地址复用现有origin校验（以/结尾、无凭据/query/fragment）。
- 只在这台电脑的gitignored `local.properties` 写入 `civa.debugApiBaseUrl=http://127.0.0.1:8080/`。SDK路径保留；不要把个人local.properties发给组员。不修改其他项目/全局Gradle配置。
- `scripts/start-usb-device.ps1` 的正常构建安装流程也会记录该本地debug目标，使后续Studio Run沿用。`-CheckOnly`仍只检查及恢复转发，不写此配置、不构建或安装。本次脚本语法已检查，普通无参数Gradle构建已验证；没有再运行完整USB脚本打断用户已成功登录的会话。
- 正式release不读取该debug本地值；仍要求显式HTTPS参数。QA的显式8081覆盖仍优先。
- 本机以后切回模拟器时，从local.properties移除该键或设为 `http://10.0.2.2:8080/`，再Sync/Run。其他组员没有该键时仍保留原模拟器默认行为。真机USB转发本身重连后仍可能需要重设；本轮没有做公网部署或后台常驻重连服务。

### 实际验证与交接范围

`gradlew.bat -PdevStackOff :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain`通过（没有显式API地址参数）；实际APK包含127.0.0.1:8080。另执行generateDebugBuildConfig验证命令行8081优先、移除本地键后回落10.0.2.2、恢复后回到127.0.0.1；release无显式地址失败符合预期，显式HTTPS校验通过，最后重新构建本机正确目标。没有依赖变更/API/数据库/会话规则修改；仅2个实现文件加本机忽略配置。完整app单测、lint计数见checks.json，不冒充本轮重跑所有模块测试。

本轮无GitHub推送/main整合；旧ZIP未更新。组员整合时带上2个实现文件及本文，按各自设备配置本地键。

[源码审计](verification/usb-address/source-audit.json) · [完整差异](verification/usb-address/source-changes.diff) · [配置验证](verification/usb-address/config-results.json) · [测试结果](verification/usb-address/checks.json) · [实际安装与资料保留](verification/usb-address/restore-result.json) · [用户确认能登录](verification/usb-address/user-confirmed.json)
