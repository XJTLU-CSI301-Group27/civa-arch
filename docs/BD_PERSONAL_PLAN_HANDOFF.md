# BD 今天个人排餐与家庭解耦（2026-11-26）

用户明确：家庭只影响菜谱的“家庭”范围与冰箱，“今天”的早餐、午餐、晚餐属于个人，创建、加入或退出家庭都不能让计划消失。本轮把今天的本地草稿按账号独立保存，并兼容旧数据；保持可爱烘焙小猫UI。

## 原因和最终行为

旧 `BdHomeFragment` 把 `account:<userId>/<householdId>` 交给ViewModel，草稿文件及照片目录使用它的SHA-256。家庭变化触发owner重置并读取另一份空草稿；不是服务器删除了三餐。

现在owner固定为 `account:<userId>`；同一个账号换家庭不重置日期、菜品或加菜流程。不同账号仍严格隔离；退出登录不会把计划公开给其他账号。本轮不修改家庭菜谱、冰箱归属，也不把个人计划同步成家庭共享计划。

## 源码清单

| 文件 | 改动 |
| --- | --- |
| feature-bd/.../BdHomeFragment.java | owner只含账号；只向迁移器提供同账号当前家庭与旧null家庭两个已知旧scope。 |
| feature-bd/.../BdHomeViewModel.java | 按个人owner初始化，异步加载时执行兼容迁移，保留已有generation保护。 |
| feature-bd/.../BdDraft.java | 可选 migratedScopes；按添加操作ID合并旧草稿，当前记录优先，保留日期/餐次/菜谱/营养；迁移后删除不被旧文件复活。 |
| feature-bd/.../BdDraftStore.java | 只迁移明确给出的归属scope；原子保存、保留旧文件；把旧照片复制到个人目录，保留旧照片并限制文件名及大小。 |
| feature-ef/src/main/res/values/ef_strings.xml | 家庭卡片说明改为“家庭成员共享菜谱与冰箱”。 |
| feature-bd/src/test/.../BdPersonalDraftMigrationTest.java | 新增5项合并、重复/删除、照片、失败重试、账号隔离及旧v1兼容测试。 |

以上为私有实现及显示文案，无新公开方法、依赖、OpenAPI、后端/数据库变更或生成代码修改。完整路径、前后哈希及源码差异见下方审计。

## 旧数据与兼容

- JSON仍为version1，新增可选 `migratedScopes`（已导入scope集合）。缺失字段按空集合读取；拒绝未知version和scope不符文件，不放宽照片归属校验。
- 初次加载合并同账号当前家庭及旧 `/null` 文件；不扫描、猜测或直接导入所有哈希文件，避免串账号。合并按操作ID去重，个人目标已存在的记录优先。已迁移来源登记在目标文件内；后续移除菜品后重启不会重新导入它。
- 旧照片先按原scope验证，只允许单个安全文件名，复制到新scope目录后再提交新草稿；失败不标记迁移完成，可重试。所有旧源文件/照片保留；不改用户原有菜谱快照与日期。
- 历史家庭ID只有哈希文件名时无法从客户端安全反推。本次针对已连接平板，**只读**本机开发库的用户/成员历史，重新计算旧owner哈希，确认3个文件均属于同一账号；私下备份后合并到该账号的新个人草稿。实际恢复3道计划菜；旧文件、个人资料与照片核对未改，未修改正常库的家庭或成员记录。私有备份、账号ID和内容未放进组内文档。
- 其他设备若有已离开且无法识别归属的历史旧文件，本版会保留它们，但不承诺自动恢复未知历史家庭。应在可核实归属后按此安全恢复方式处理；不要把所有草稿拼给当前登录者。
- 未给用户提交登录信息；更新重启后需重新登录原账号查看恢复后的计划。暂不提供跨设备同步，卸载/清空数据仍会删除本机草稿。

## 实际验证

- 真机MI_PAD_4_PLUS/API27，独立edu.xjtlu.civa.uiverify + civa_family_test，正常账号/家庭未用于写入测试。
- 种入旧“未加入家庭”的三餐+照片，登录后按原日期/餐次恢复，复制照片字节一致。
- 同账号创建家庭、退出、加入另一个家庭，三餐及照片/操作ID保持完全相同；另一账号看不到这些菜，再登录原账号仍保持相同。
- JDK17实际命令：`gradlew.bat -PCIVA_API_BASE_URL=http://127.0.0.1:8080/ -PdevStackOff :app:assembleDebug testDebugUnitTest lint --console=plain`。129项单元测试，0失败/错误/跳过；lint 0错误、79警告。`python scripts/check_contracts.py`通过。未重跑服务器业务全套或线上DeepSeek，本轮无服务器代码变更。
- 正式App保留数据覆盖安装，个人目标草稿安装后读回确认；未卸载或清空原App。测试包、测试服务及临时转发已清理，原8080/数据库55433/USB8080保持运行，平板亮屏设置恢复。
- 测试准备曾遇到tar恢复文件属主权限及stdin EOF等待；改用设备shell管道和no-same-owner，并修正验证照片时遗漏files/路径。从已确认检查点继续后完成实际流程，不把测试准备失败当成功或业务失败。

本地下载目录没有Git历史；本轮没有GitHub push、PR、最新main整合或团队契约冻结声明。旧ZIP未更新，重新交接需重新打包。

[完整源码差异](verification/personal-plans/source-changes.diff) · [文件审计](verification/personal-plans/source-audit.json) · [自动检查](verification/personal-plans/checks.json) · [真实家庭切换验证](verification/personal-plans/flow-result.json) · [恢复及覆盖安装](verification/personal-plans/production-install.json) · [清理](verification/personal-plans/cleanup.json)
