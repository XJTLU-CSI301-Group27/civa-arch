# 公共菜谱目录（recipe_catalog）

生成脚本：`scripts/merge_recipe_catalogs.py`（可重复运行，id 由规范化菜名派生）。

- 合并结果：**1307** 道（A 1072 + BD 235；重名去重 77 条，其中采用 BD 图片 38 张）
- 分类分布：家常菜 900, 小吃 94, 增肌 56, 营养 53, 减脂 52, 保持 51, 食疗 51, 烘焙 50
- 食材保留自由文本（展示用），不强制映射标准食材；只有家庭菜谱（后续阶段）引用标准食材 ID。
- 图片：BD 的 149 张图以 `imageKey=recipes/images/*.jpg` 引用客户端资源；A 无图，客户端用 emoji。
- 未知值（热量/营养/时长）保持 `null`，不写 0。
- 表结构：`server/src/main/resources/db/migration/V20261123201000__AC_recipe_catalog.sql`
- 种子数据：`V20261123201100__AC_recipe_catalog_seed.sql`（2794 KB）


## 2026-11-24：修复菜谱详情切回今天的闪退

### 用户报告与原因

验收标准：进入菜谱、点击任意一道菜的详情，再点击今天，应正常回到首页；正常返回、快速切换和页面重建也不应被旧请求影响。

实际crash日志同时出现RecipeCatalogFragment.onError中requireContext，以及onSuccess→render→getString的`Fragment ... not attached to a context`。离开详情时主导航清理返回栈，列表视图可能重建并再次发起请求，随后立即销毁。原onDestroyView虽然cancel请求，但取消本身仍会通过ApiCalls投递CANCELLED；已排队的成功回调也可能稍后到达。旧Fragment仍持有控件引用并访问已经分离的Context，因而闪退。仅检查isAdded或只取消请求不足以隔离同一Fragment的新旧视图。

### 本轮修改和接入

- RecipeViewCallbacks.java：feature-ac/catalog包私有、主线程使用的视图代数门禁；onViewCreated开启新代数，onDestroyView先失效，再cancel。仅当前存活视图所属的成功/失败回调能继续执行；旧代数回调在页面重建后也不能操作新页面。没有修改共享ApiCalls的错误或取消语义。
- RecipeCatalogFragment.java：公共/我的/家庭/想吃四类列表的4个回调全部绑定视图代数；销毁时清除搜索延时任务、取消请求、重置loading、解除RecyclerView适配器并释放控件和host引用。恢复视图时重新允许加载，避免旧请求遗留loading让列表永久不加载。
- RecipeCatalogDetailFragment.java：详情、食材字典、收藏状态及已有操作的11个回调绑定同一视图范围，关闭后不再渲染、Toast或触发过期界面动作。并发的当前视图请求仍正常交付。原服务端写操作没有被此门禁取消或伪造成功，只有过期UI回调被抑制。
- RecipeViewCallbacksTest.java：新增8项回归测试，覆盖活跃视图成功/错误、销毁后取消/迟到成功、重建后的旧成功/旧错误、详情与收藏并发、未创建视图时绑定、有效null响应。未增加依赖或公开类型，没有改公共接口、数据库、排餐格式、推荐、主题或后端配置。
- 本地解压项目中完成修复，civa-arch-main2未同步。本轮没有GitHub推送、分支整合或PR；原文件备份与完整差异在本机C:\Users\18192\Desktop\CSI301\tmp\civa-catalog-lifecycle。

### 实际验证

- 在工程根目录、独立JDK17运行`gradlew.bat :app:assembleDebug testDebugUnitTest lint --console=plain`，BUILD SUCCESSFUL；全Android工程112项单元测试、0失败、0错误（新增8项包含在内）。各模块lint报告均为0错误；feature-ac报告27项警告、app报告15项警告，未在本轮顺带清理无关警告。精确分模块汇总见本机verification/test-totals.json。
- 原模拟器安装修复APK时保留应用数据。设备当前为登录页，使用之前的本机合成测试账号，不要求或覆盖用户密码。实测5次不同菜品详情→今天（其中一次先系统返回到列表再切今天）、公共/我的/家庭/想吃来源切今天、6轮快速往返取消请求，以及2400×1600宽屏详情→今天；随后恢复原显示尺寸。全程同一应用进程存活、crash缓冲区没有新增Civa崩溃；前后排餐文件内容一致。
- 最终正常退出合成测试账号，留登录页供用户登录自己的账号；没有编辑、删除或收藏用户菜谱，没有写排餐、清空应用数据或调用营养AI。设备回归脚本先检查控件及进程状态，不将脚本等待超时当作新业务错误。没有新增Android仪器化测试，也没有运行服务端测试（服务端无改动）。
- 截图：[菜谱详情](verification/screenshots/catalog-lifecycle/detail.png)、[宽屏返回首页](verification/screenshots/catalog-lifecycle/today-wide.png)、[竖屏返回首页](verification/screenshots/catalog-lifecycle/today-final.png)。本机verification目录保留实际构建、设备日志、旧闪退摘要、差异及测试汇总。没有在界面中隐藏真实网络错误；当前页面收到的错误仍按原逻辑处理。
