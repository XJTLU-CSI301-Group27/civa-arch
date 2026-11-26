# EF / AC 菜谱入口与家庭流程交接（2026-11-25）

> 本文的家主限制 / 一次性邀请规则已被用户后续需求覆盖；当前实现与迁移见 [EF_SHARED_FAMILY_HANDOFF.md](EF_SHARED_FAMILY_HANDOFF.md)。以下保留历史结果，不作为现行规则。

> 最新规则见 [EF_EMPTY_INVITATION_HANDOFF.md](EF_EMPTY_INVITATION_HANDOFF.md)：中间添加入口已移除，首次家庭流程改为返回今天；邀请码生成与重放有新候选实现及审批状态。本文保留上一轮历史与哈希。

实际工程：`C:\Users\18192\Desktop\civa-arch-main\civa-arch-main`。
本轮沿用可爱、温暖、烘焙小猫的统一 UI，修改菜谱顶部和家庭设置流程。
原开场视频、今天页排餐、头像 / 昵称与登录凭据功能保留。只修改本地指定工程，没有 GitHub 推送或最新 main 整合。

## 完成的页面行为

- 菜谱页“＋ 新建菜谱”移至标题右侧，采用共享奶油底、焦糖文字和细边胶囊按钮。
  仅在“我的 / 家庭”显示；其他来源使用 INVISIBLE 保留标题行尺寸，切换栏不会被挤占。
  空的个人 / 家庭菜谱显示温暖的空态与“＋ 添加菜谱”入口。
- “想吃”隐藏搜索与分类后，额外保留 32dp 插画下方空间，让卡片避开向下延伸的猫咪和餐盘。
  保留页级列表裁剪与局部插画溢出规则，不能把整个页面设成不裁剪。
- 切换来源 / 搜索时取消旧请求并更新回调代次，防止上一来源较慢的响应覆盖当前列表。
  原 RecipeViewCallbacks 的视图销毁保护继续保留。
- 家庭页左上角添加小叉叉（48dp 触摸范围），首次设置可跳过到“我的”；从“我的”进入也返回“我的”。
  系统返回遵循相同规则；操作请求处理期间关闭 / 提交 / 退出按钮禁用，避免不确定的重复操作。
- 首次登录进入家庭页时，导航以替换登录页的方式进入，避免叉叉关闭后旧登录页 onResume 又自动跳回家庭页。
  从其他页面进入家庭设置仍保留原次级页面路径。登录成功时收起输入法；返回我的 / 创建加入后到今天时校准导航选中项。
- 尚未加入家庭的个人页显示“还尚未加入家庭哦”，按钮为“加入家庭”；隐藏已加入说明。
  已加入时继续显示家庭 ID、共享说明和“家庭设置”。getMe 刷新后再次绘制卡片，避免状态过期。
- 有家庭时，加入按钮下方显示“退出家庭”，带退出确认。普通成员退出成功后回到我的，立即恢复未加入状态，随后可用有效邀请码加入另一家庭。
  **家主限制按用户确认保留**：显示先处理家主身份的提示，不退出、不转让、不解散家庭。
  现有版本没有家主转让入口，这点在提示中明确说明。
- 邀请码字段改为可见的字母数字输入，避免拼音组合或自动纠正改写邀请码；服务端继续按既有规范归一化和校验。

## 接口与数据边界

1. 复用既有 `DELETE /api/v1/households/{householdId}/members/{userId}?expectedVersion=...`
   （removeMember）契约，补齐原来的 501 实现。OpenAPI、生成客户端、数据库结构与迁移均未改动。
2. 服务端 HouseholdMembershipService 在事务中锁定家庭与成员记录：成员只能退出自己，家主可移除普通成员；
   不能移除家主；非成员拒绝访问；expectedVersion 不匹配返回 VERSION_CONFLICT。
   退出只把该 membership 置为 inactive、版本 +1；不删除家庭、个人菜谱、共享菜谱、库存或历史。
3. core-data 新增公共辅助方法 `AuthRepository.leaveHousehold(ApiCallback<Void>)`，已登记 public-surface.draft.json。
   EF 不传用户 ID 或令牌；核心层读取当前成员版本（含分页）后提交退出。
   服务端确认后通过现有 SessionStore 家庭撤销路径清除家庭作用域缓存、取消旧代次请求，保留登录与个人作用域。
4. 网络结果未知时读取 getMe 对账；不能仅本地清空家庭当作成功。旧账号延迟响应不能清空新账号家庭。
5. create / join / invitation 格式继续复用现有接口。**生成邀请码接口仍是原有 501，本轮没有实现它**；
   加入流程需要已经有效的邀请码。测试邀请码仅在隔离测试库中为随机测试家庭预置，实际加入 / 退出走真实 HTTP 接口。

## 源码 / 资源变更清单（18 个文件）

| 文件 | 最终 SHA-256 |
| --- | --- |
| `feature-ac/src/main/res/layout/ac_fragment_catalog.xml` | `6d393ae048bee98e0221b98838b78119bfe58a034bfd76d6c3e60b6ca9e15bef` |
| `feature-ac/src/main/res/values/ac_strings.xml` | `188b015a6348d07155d215412881f533893d5953010698783b4aa2b6e4ed3bf5` |
| `feature-ac/src/main/java/edu/xjtlu/civa/feature/ac/catalog/RecipeCatalogFragment.java` | `b6769b05df2717cb79a3b36982fcbdc8cf83b852f9b2936a06b845d7f6d5b752` |
| `feature-ef/src/main/res/layout/ef_fragment_household.xml` | `5ec23d0ee6ba42e53490c761bc7ded17abaccca74677d9b7997d7d8153b48664` |
| `feature-ef/src/main/res/values/ef_strings.xml` | `36633e07d1736d77d20801fb1b77f6bc9d9c4141980168e33006a87406796a28` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/HouseholdFragment.java` | `acb43cc0626d7de1a39fca618ffcfebea127b56e794812739dd7ce139973e213` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/HouseholdViewModel.java` | `dff09f4654758a4dc0896134517989bdc24226a850e58a62b8563165b9ea34f2` |
| `feature-ef/src/main/java/edu/xjtlu/civa/feature/ef/ProfileFragment.java` | `2119eb1b008b7d37025210ed34cfae066473a2656cfa3faa7b9f513c3c9b99f7` |
| `app/src/main/java/edu/xjtlu/civa/MainActivity.java` | `3a196effb1868363b432d851773a5a6b2a1a5d1bb20e49375fd040edc75864ce` |
| `core-data/src/main/java/edu/xjtlu/civa/core/data/AuthRepository.java` | `bb6d33b7032062035d559f39ffa47eeba2de8212e74e7689ba45beaacc13c5d9` |
| `server/src/main/java/edu/xjtlu/civa/server/household/HouseholdConfiguration.java` | `8c236d9e450e73c065ab83539001fc2fb1e913abc3d18b9aea331b33726cb942` |
| `server/src/main/java/edu/xjtlu/civa/server/household/HouseholdController.java` | `83cd990bc21d4ee1853df730df2b3f04c182d32b0c7fac81e208d740a0f1fb36` |
| `server/src/main/java/edu/xjtlu/civa/server/household/HouseholdErrors.java` | `600920039bafca6240d18a82911ca22e0279b52cc99df3195f364a970a1a7538` |
| `contracts/public-surface.draft.json` | `cfa08d36f41ca7ebed8a1dfc45c24119d9872bcc03a9ae43cde7393d8dd10701` |
| `server/src/main/java/edu/xjtlu/civa/server/household/HouseholdMembershipService.java` | `95c8174a6e18278812268e4f48f5d8817b331829b332dd12a96916331a9b47a3` |
| `core-data/src/test/java/edu/xjtlu/civa/core/data/HouseholdLeaveTest.java` | `3cebd2066195de630a847e1a1c85afae69ae5428ce9e33b0a08e9134c4e257c0` |
| `server/src/test/java/edu/xjtlu/civa/server/household/HouseholdLeaveIntegrationTest.java` | `007382bfdd85194a80b501f3f060571c8f0993c5f62f2beb4f75b5fca3afb9d6` |
| `app/src/main/java/edu/xjtlu/civa/AppNavigator.java` | `b893cfa1bae0a0177553631f9b41aeccce1d86ed3856025b4530a1db2488a92a` |

[完整差异](verification/family-catalog/source-changes.diff)、
[修改前后摘要](verification/family-catalog/source-audit.json)用于小组逐文件审阅。
没有增加 Android 或服务端运行依赖。

## 验证与安装

- Android：`gradlew.bat :app:assembleDebug testDebugUnitTest lint --console=plain`。
  BUILD SUCCESSFUL；124 项单元测试，0 失败 / 错误 / 跳过；lint 0 错误、83 警告。
  新增 HouseholdLeaveTest 的 4 个检查：确认后保留登录并拦截旧家庭回调、家主失败不清状态、
  延迟退出不影响另一个账号、丢失 DELETE 响应后通过 getMe 对账。
- 服务端：在独立 `civa_family_test` 数据库执行 `test --tests '*HouseholdLeaveIntegrationTest' --tests '*HouseholdHttpIntegrationTest' --tests '*HouseholdReadIntegrationTest' bootJar --console=plain`
  （采用本机现有 Maven 镜像）。3 个集成测试方法通过，覆盖家主限制、普通成员越权、陌生人访问、
  版本冲突、自助退出、退出后的权限失效、重复退出不重复变更、保留家主与重新建立家庭。
  测试库保护检查实际执行，未用开发库执行集成测试。
- 契约工具：`python scripts/check_contracts.py` 成功；
  `python -m unittest discover -s scripts -p 'test_contract*.py'`，16 项通过。
- API 37.1 模拟器：手机 1080×2400、宽窗口 2400×1600。
  独立测试包连接 8081 测试后端；通过真实注册接口创建的新账号登录后首次跳过、
  我的往返、普通成员加入 A / 退出 / 未加入卡片 / 加入 B、家主拒绝退出，并检查真实服务最终状态。
  菜谱的个人 / 家庭新建入口打开正确编辑器；想吃显示两道收藏且不遮猫；宽屏列表与家庭表单滚动可访问。
- 正式包 `edu.xjtlu.civa` 已覆盖安装，安装前后 4 个既有本地排餐 / 照片 / 资料文件摘要一致。
  没有清空或卸载正式应用；只移除临时测试包。模拟器尺寸恢复，原本运行的模拟器保留。
- 本机正常开发后端 8080 已更新为新 JAR 并保持运行；仅重启该工程自己登记的进程，原数据库未重置。
  隔离的 8081 测试服务在收尾时停止；隔离测试库保留以便追溯。

过程记录：首次新测试的响应样例缺字段 / 测试用户名含不允许字符，修正测试夹具后重跑通过；
联调发现并修复首次跳过的登录页回跳、邀请码被输入法改写。
宽窗口表单需要滚动，首次脚本未滚动就寻找退出按钮；改为实际滚动验证。
宽屏输入法展开时的自动化提交没有完成，最终家主限制在手机尺寸验证；不把该中断记为宽屏键盘验证通过。
未跑整个服务端全量测试、全部设备或超大系统字体。现有 lint 警告保留；本轮新增两条布局性能建议，未隐藏或禁用检查。

原始记录：[Android 构建](verification/family-catalog/final-build.log)、
[家庭服务端检查](verification/family-catalog/server-test.log)、
[检查计数](verification/family-catalog/check-results.json)、
[设备流程结果](verification/family-catalog/flow-result.json)、
[家主及服务状态验证](verification/family-catalog/owner-check.log)、
[安装保留数据记录](verification/family-catalog/production-install.json)。
测试账号凭据、令牌、邀请码明文和本机 .env 不包含在交接材料中。

## 截图

- [我的菜谱](verification/screenshots/family-catalog/recipes-mine-phone.png)
- [家庭菜谱](verification/screenshots/family-catalog/recipes-family-phone.png)
- [想吃与完整小猫](verification/screenshots/family-catalog/recipes-favorites-phone.png)
- [宽屏我的菜谱](verification/screenshots/family-catalog/recipes-mine-wide.png)
- [宽屏想吃](verification/screenshots/family-catalog/recipes-favorites-wide.png)
- [家庭设置与退出](verification/screenshots/family-catalog/household-leave-phone.png)
- [宽屏家庭表单，已滚动](verification/screenshots/family-catalog/household-wide.png)
- [首次跳过后的我的](verification/screenshots/family-catalog/profile-no-household.png)
- [退出后的我的](verification/screenshots/family-catalog/profile-after-leave.png)
- [家主限制](verification/screenshots/family-catalog/owner-cannot-leave-phone.png)
