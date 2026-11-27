# Civa V2 Final QA-2 报告：最终 Android 验收与交付准备

日期：2026-11-27（Asia/Shanghai）
基线：`30681f4ce81e05b6e5f38db7d595bd3deb1a9cba`
范围：Final QA-2；未进入 Phase C1/C2，未开始新功能。

## 1. 当前 HEAD 与 Commit

- QA-2 开始 HEAD：`30681f4`
- 本轮业务代码修改：无；仅测试报告提交。
- 当前工作区：报告提交前干净；本报告提交后另见 HEAD。

## 2. 做饭 UI — PASS

### 准备

- DemoHome `d8d967df-edc7-4559-aa2e-4518cf3b30f1`
- 库存批次 `15c3186a-6184-4378-8bc5-0c2ca1f1443f`：鸡蛋 5 COUNT
- 家庭菜谱 `278496f2-0a2a-4336-a6a4-16ce271a5ece` RG6Cook：
  - 鸡蛋 2 COUNT
  - 食盐 适量（RECIPE_ONLY）
  - 份数 2

### Android UI

1. 家庭菜谱详情 → “确认做饭”。
2. 弹出“确认做了这道菜”，显示份数 2。
3. 点击“确认做饭”。
4. UI 返回菜谱详情，未报错。

### DB

- 批次鸡蛋 5 → **3**
- CookingEvent `6b897a9e-db9e-42eb-b67c-3aa601c33c93`
- InventoryMovement `e8db3d71-e435-4a98-ae8d-834c355c615e`：
  - before 5
  - after 3
  - reason COOK
- 只有鸡蛋产生 movement；RECIPE_ONLY 食盐无 movement。

**PASS。** 成功提示为短时 UI 状态，dump 时已返回详情；DB/详情数量一致。

## 3. MealEntry UI — PASS

Android UI：

1. 进入“食记”。
2. 点击“记一餐”。
3. 输入“RG6Meal”，保存。

结果：

- MealEntry `9b8a9730-15d3-4943-8eed-0e20904a1c2e` 创建成功。
- 食记界面显示 RG6Meal。
- 库存批次仍 3。
- InventoryMovement 仍 1（只有做饭产生），未新增食记流水。

**PASS。** 未执行 UI 编辑/删除（可选）。

## 4. 计划 UI — PASS

库存缺少 beef，RG6Missing 在计划选择器中显示“缺 beef 0.5 克（按 1 份）”。

Android UI：

1. “今天” → “家庭计划”。
2. 早餐“添加”。
3. 选择 RG6Missing。
4. 返回计划界面，早餐显示 `RG6Missing ×1 · 由 UI Check 添加`。

DB：

- Plan slot `0ffdaf0e-14ad-4d14-860a-f0c881c8373b`，2026-11-27 BREAKFAST。
- Plan item recipe `960837b0-f4ba-44e8-aea2-ff4872965d71`，servings 1。
- InventoryMovement 仍 1，无新增扣减。

**PASS。**

## 5. 360dp / 横屏 / 英文 / 大字体 / 深色 / 长列表

| 项 | 结果 |
| --- | --- |
| 360dp 导入 Dialog | **PASS** |
| 360dp 选择食材文案 | **PASS**：完整显示“选择食材”，不再截断 |
| C→TRACKED 名称替换提示 | **PASS**：显示“原始‘食盐’将替换为规范食材‘beef’…” |
| 平板竖屏 | PASS（前轮） |
| 平板横屏 | **NOT RUN（仅基本渲染）** |
| 英文 | **NOT RUN** |
| 系统字体放大 | **NOT RUN（仅基本渲染）** |
| 深色模式 | **NOT RUN（仅基本渲染）** |
| 长食材列表滚动 | **NOT RUN** |

360dp 截图：
- `review/rg5-360.png`
- `review/rg5-360-warning.png`

横屏/字体/深色曾加载旧菜谱详情，但未执行关键操作，不能算 PASS。  
**P2 观察**：大字体+横屏下详情底部按钮视觉高度很紧凑，仍有布局风险。

## 6. 历史 trackStock=false — PASS（读取/保留），转换 NOT RUN

### 构造

隔离测试家庭 DemoHome 创建 `RG6Legacy`：
- 鸡蛋 2 COUNT，trackStock=true
- beef 1 G，trackStock=false（仅测试记录）

### UI

- 详情显示鸡蛋 2 个、beef 1 克。
- 编辑器显示：
  - 鸡蛋正常行；
  - beef 行“**旧数据：不参与库存**”；
  - beef 只提供“转为仅说明辅料”，无删除。
- 修改菜名为 RG6LegacyRenamed，保存。

DB：

- name 更新，version 2；
- 鸡蛋 trackStock=true 保留；
- beef trackStock=false 保留；
- 无自动转换。

### 服务端

- recipe match 只返回鸡蛋 ENTiGH；
- false beef 不出现在缺料/匹配中。

**读取/保留/匹配 PASS。**

**用户主动转换 RECIPE_ONLY：NOT RUN。**

## 7. 自动化结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **236 tests / 0 failures** |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| assembleDebug | BUILD SUCCESSFUL |
| lintDebug | BUILD SUCCESSFUL |
| checkContracts | BUILD SUCCESSFUL |
| `git diff --check` | 无空白错误 |

## 8. 缺陷与修复

- 无新增已确认 P0/P1 业务代码缺陷。
- 既有 fingerprint 完整性 P0 已在 `c935910` 修复。
- 360dp 文案截断已在 `3483913` 修复，本轮真机确认 PASS。
- C→TRACKED 名称替换提醒在 `7f6c1f0` 实现，本轮真机确认 PASS。
- 邀请 500 已确认是启动配置：必须使用 `civa.media-dir` / `CIVA_MEDIA_DIR`，不是 `civa.media.root`；正确配置后 201/加入成功。
- 横屏/英文/字体/深色完整交互仍有未验证风险，记录为交付限制，不视为已确认缺陷。

## 9. 环境与数据恢复

开始基线：

- recipes 8
- user_recipes 17
- inventory_batches 12
- inventory_movements 11
- cooking_events 5
- meal_entries 7
- plan_slots 5
- plan_items 9
- DemoHome 0 菜谱 / 0 批次 / 0 movements / 1 原有 plan slot

结束恢复：

- 删除 RG6Cook、RG6Missing、RG6Legacy 家庭菜谱、ingredients、personal snapshots。
- 删除测试 MealEntry、CookingEvent、InventoryMovement、InventoryBatch、测试 plan slot。
- DemoHome：0 菜谱 / 0 批次 / 0 movements / 1 原有 plan slot。
- OurHome：6 菜谱 / 12 批次 / 8 movements，未变。
- 总 recipes 8 / user_recipes 17 / inventory_batches 12 / inventory_movements 11 / cooking_events 5 / meal_entries 7 / plan_slots 5 / plan_items 9。
- 本地 pending_writes=0。
- 家庭成员关系未变。
- Pinyin IME 恢复，ADBKeyboard 卸载；屏幕 1200×1920/320dpi；字体 1.0，深色关闭。

## 10. 项目实际功能清单

- 家庭/账号登录、家庭邀请与成员。
- 家庭菜谱结构化编辑：
  - TRACKED 鸡蛋等；
  - RECIPE_ONLY 适量/少许；
  - 历史 false 读取。
- 公共/个人菜谱导入家庭，双类型核对。
- 冰箱库存批次与匹配。
- 家庭计划（今日、添加、锁定/权限）。
- 做饭预览/确认、库存扣减、库存不足拒绝。
- 个人食记创建、修改、删除（HTTP/DB 已验证；UI 创建已抽样）。
- 食记与做饭库存语义分离。
- 媒体/邀请配置：持久私有目录与邀请密钥。

## 11. 未实现/范围外

- Phase C1 家庭自定义食材。
- Phase C2 自定义食材图片。
- 推荐算法/自动菜单。
- 新数据库食材目录迁移。
- 全面 UI 视觉重设计。

## 12. APK 与服务端运行状态

- Debug APK：
  `D:\XJTLU\CSI301\code\civa-main\app\build\outputs\apk\debug\app-debug.apk`
- Server jar：
  `D:\XJTLU\CSI301\code\civa-main\server\build\libs\civa-server.jar`
- 服务端当前运行 B1；Flyway `20261127130000`。
- 启动必须使用 `civa.media-dir` / `CIVA_MEDIA_DIR`；当前 dev media 目录 `server/build/media`。

## 13. 交付文档准备

仓库已有：
- `README.md`
- `PROJECT_DOC.md`
- `ARCHITECTURE_BASELINE.md`
- `docs/` 交接/运行/验证文档
- 服务端 `scripts/start-local.ps1` / `start-native-local.ps1`

**待确认**：课程实际提交格式、截止时间、是否要求 release-signed APK、演示视频/PPT；仓库内未发现明确课程提交规范，不能猜测。

建议演示流程：

1. 登录/家庭。
2. 冰箱库存。
3. 家庭菜谱结构化编辑（TRACKED + RECIPE_ONLY）。
4. 公共/个人菜谱导入家庭。
5. 加入计划。
6. 确认做饭，库存扣减。
7. 个人食记创建（不扣库存）。
8. 历史 false 编辑器展示。

## 14. 最终发布建议

### 结论：**CONDITIONAL GO**

- Pending A1/A2/A3、权限、做饭/MealEntry HTTP/DB、做饭/MealEntry/计划 UI、360dp、名称替换提醒、历史 false 读取/保留均有实际证据。
- 无新增确认 P0/P1 业务缺陷。
- 剩余为未验证 UI 环境（横屏/英文/大字体/深色/长列表完整交互）和 false→RECIPE_ONLY 主动转换；属于已验证范围之外的限制，不是已确认缺陷。

### 条件

- 课程演示使用 debug APK 可以接受；如需 release 签名 APK，需另行构建/签名。
- 演示前建议在目标平板完成一次横屏/大字体快速冒烟。
- 服务端必须用 `CIVA_MEDIA_DIR` 启动并保留邀请密钥，否则邀请 500 会重现。
- 不实施 Phase C1/C2。

停止于 Final QA-2，不进入 Phase C 或新开发阶段。
