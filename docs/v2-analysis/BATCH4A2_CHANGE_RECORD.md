# Batch 4A-2 变更记录：家庭计划选菜库存状态

日期：2026-11-27（Asia/Shanghai）
基线：`ba8d2b5`（Batch 4A-1）
范围：仅 4A-2；未修改服务端、数据库、OpenAPI Contract、导入转换、做饭预览、个人计划。

## 1. 实现内容

- 复用 `RecipeStockPresenter` 与新增的 `RecipeStockText`（4A-1 的卡片文案抽为共用工具，避免两套规则）。
- 家庭计划选菜按 **本次新增 1 份** 调用 `matchRecipes`，不复用菜谱列表的 `yieldServings` 结果。
- 选菜对话框立即打开，菜谱立即可选；库存状态异步填充，未完成显示“检查库存…”，失败显示“无法判断”。
- 状态文案：
  - `可制作（按 1 份）`
  - `缺 土豆 1 克（按 1 份）`（最多 2 项 + “等 N 项”）
  - `无需库存`
  - `无法判断`
- 对话框顶部增加说明：“库存估算按本次 1 份计算。”
- `ENOUGH / MISSING / UNKNOWN` 均不阻止选择；`addDish` 不含任何库存校验。
- 选菜列表顺序固定，异步结果只更新文本，不重排、不改变已选中位置。

## 2. 匹配 API 请求流程

1. `PlanFragment.loadRecipes` 成功加载家庭菜谱后启动 `startStock`，不阻塞计划槽加载。
2. 若字典为空：`GET /ingredients` 分页（每页 100，直到 total；不完整则降级 UNKNOWN）。
3. `POST /households/{id}/recipe-matches`：
   - 每批最多 100 个 `MatchEntry`；
   - 每道菜固定 `servings="1"`；
   - 结果按 `recipeId` 映射，`RecipeStockPresenter.present(..., "1")`。
4. 任一批失败：该批全部 UNKNOWN；对话框仍可选。
5. 不写库存、不预留、不触发做饭。

## 3. UI 与排序

- 使用自定义 `AlertDialog`（说明 TextView + `ListView` + `ArrayAdapter`），而不是 `setItems`：
  - 打开时先显示“检查库存…”，匹配完成后 `notifyDataSetChanged` 更新文案；
  - 列表顺序固定，不因异步排序改变用户点击目标。
- 未对计划选菜做 ENOUGH 优先排序（Part 3 允许不排序，优先稳定）；菜谱列表页的排序保持 4A-1 行为。
- 对话框固定高度 300dp，可滚动；取消按钮保持。
- 缺料/UNKNOWN 仍可点击并正常调用原有 `addDish` → `replacePlanSlot`。

## 4. 生命周期与异步安全

- `loadGeneration` 继续用于计划 household/recipes/slots；
- 新增 `stockToken` + 当前 home/source 校验 + `listView != null`：
  - 切换家庭、Tab、刷新、旋转/销毁视图都会使旧 stock 响应失效；
  - 旧家庭/旧页面的 match 不会写入当前 `stockViews`；
  - 对话框关闭后 `refreshPickerLabels` 不更新 UI；
  - 重新打开对话框使用当前 `stockViews`（可能已更新）。
- 选中项使用固定 `pickerRecipes` 的 position，不依赖实时排序。

## 5. 权限与业务一致性

- 未改 `addDish`、`PlanItemEditor`、`sendSlot` 或 Batch 3 的权限分支。
- 普通成员仍只能追加/增份；房主管理/锁定逻辑未变；锁定餐次仍由既有 UI + 服务端 409 限制。
- 缺料/UNKNOWN 不增加提交限制；不自动扣库存；个人食记/做饭确认未改。

## 6. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest -PdevStackOff` | 通过（Presenter 18 项，新增 1 项 servings=1） |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **180 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（契约未变） |
| `server: .\gradlew.bat test --rerun-tasks` | **98 tests / 0 failures**（4A-1 后运行；4A-2 无服务端改动） |
| `git diff --check` | 无空白错误 |

## 7. 实机验证

设备 MI PAD 4 PLUS（1200×1920 / 320dpi），OurHome，11-27 午餐：

- 打开「添加」：立即出现菜谱列表 + 说明“库存估算按本次 1 份计算。”；
- 状态：
  - `FamilyTest2 · 可制作（按 1 份）`
  - `TomatoEgg · 可制作（按 1 份）`
  - `Tomato Egg · 可制作（按 1 份）`
  - `联动测试菜 · 可制作（按 1 份）`
  - `Family repro · 缺 土豆 1 克（按 1 份）`
- 选择缺料的 `Family repro` 成功加入计划，行显示 `FamilyTest2 ×1、Family repro ×1`；
- 房主管理删除 `Family repro` 后恢复 `FamilyTest2 ×1`；
- 再次选择已存在的 `FamilyTest2`，行变为 `×2`（Batch 3C-1 增份规则），房主减份恢复 `×1`；
- 测试后计划内容恢复，`inventory_movements` 无新增（未扣库存）。
- 截图：`review/plan-picker.png`。

## 8. 未执行

- 匹配从“检查库存…”到实际状态的逐帧截图（最终状态已验证）。
- 断网/超时/部分批次失败下的对话框降级（代码路径存在，未做故障注入）。
- 家庭菜谱 >100 时的分批（代码已实现，当前 5 道）。
- 字典 >1 页（当前 36 条）。
- 打开不同餐次/切换家庭时旧响应的故障注入（代际代码已加）。
- 英文界面逐页人工检查（文案已补齐）。
- 普通成员账号的选菜实机走查（本次以房主账号验证；权限分支未改，服务端 98 项回归通过）。

## 9. 剩余风险

- 选菜状态基于 1 份估算，与菜谱列表的 `yieldServings` 数字可能不同；文案已说明。
- 库存状态是页面加载时的快照；做饭/库存变化后需重新进入/刷新计划才会更新。
- 选菜列表未按 ENOUGH 排序（按稳定性优先），可制作菜谱不一定在顶部。
- 状态文案较长时会被列表项单行截断（仍可点击，完整信息不在对话框内展开）。
- `RecipeStockPresenter` 为跨包复用改为 public；不改变算法。

## 10. Batch 4A 关闭结论

4A-1（家庭菜谱列表）+ 4A-2（计划选菜）均完成，满足“零服务端改动接入库存匹配、缺料可选、状态安全降级”的目标。建议 **Batch 4A 关闭**。

## 11. Batch 4B 建议优先修复

1. **导入转换保真**：保留公共/个人菜谱的自由文本单位与数量（`勺/适量/2 个`），避免匹配数字错误；必要时在导入 UI 标注“按换算估算”。
2. 服务端顶层 `status`、`ingredientName`、`expired` 字段（需 Contract + IC），减少客户端推导与字典请求。
3. 单位不一致语义：服务端返回 UNKNOWN 而非 MISSING(0)。
4. 做饭预览缺料明细 UI（当前只提示“缺食材”）。
5. 个人计划/本地 `PlannedDish` 的库存匹配（需先有结构化数据，风险较高）。
