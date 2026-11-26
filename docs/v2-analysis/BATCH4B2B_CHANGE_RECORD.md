# Batch 4B-2b 变更记录：导入体验完善与数据保真回归

日期：2026-11-27（Asia/Shanghai）
基线：`123f476`（4B-2a）
范围：仅 4B-2b；未修改服务端、数据库、OpenAPI、Flyway、InventoryCalculator、RecipeStockPresenter 匹配语义、Batch 3 权限。

## 1. 本轮修改

| 文件 | 改动 |
| --- | --- |
| `ImportReviewDialog` | 保存前逐行标记错误、显示未通过行数；`confirmed` 防止重复提交；修正后原因实时更新 |
| `HouseholdRecipeEditorFragment` | 新增可编辑份数/时间字段；新建预填 2/30；编辑预填原值；保存校验 |
| `ac_fragment_household_recipe_editor.xml` | 份数/时间输入行 |
| `ac_strings.xml` / `values-en` | 份数/时间/未通过行数文案 |
| `HouseholdRecipesTest` | 无效份数/时间拒绝测试 |

没有修改已验收的 4B-2a 解析规则；没有重复实现匹配逻辑。

## 2. P0 实测结果

### 2.1 公共菜谱 A/B/C 导入

构造可清理的公共菜谱 `ImportProbePublic`：

- 鸡蛋 2 个（A，COUNT）
- 可乐 1 L（B → 1000 ML）
- beef 1.5 kg（B → 1500 G）
- 盐 适量（C）

核对界面：

- 鸡蛋 2 个 → “已识别”；
- 可乐 1 L → “已换算为 1000 毫升”；
- beef 1.5 kg → “已换算为 1500 克”；
- 盐 → “缺少数量，请填写正数”；
- 份数/时间预填 **2 / 20**。

未修正盐直接导入：无家庭菜谱创建（DB 计数 0）。修正盐为 5 克后导入成功，数据库结果：

```
鸡蛋 2 COUNT
可乐 1000 ML
beef 1500 G
盐 5 G
yieldServings=2, durationMinutes=20
```

生成的菜谱 ID 与创建响应一致；导入后 `matchRecipes` 返回鸡蛋 ENOUGH、可乐/beef/盐 MISSING，符合实际库存。

### 2.2 B 类真实换算

公共 probe 中：

- `1.5 kg` + beef(G) → **1500 G** 成功落库；
- `1 L` + 可乐(ML) → **1000 ML** 成功落库。

不是“单位不匹配”的替代测试；核对页与 DB 均确认换算值。

### 2.3 空冰箱导入

临时把 `verify_1209` 的活动成员关系切到空库存的 `DemoHome`（测试后恢复 OurHome）：

- 库存批次数 0；
- 导入个人菜谱 `我的番茄炒蛋`，C 行番茄改为字典土豆 2 G，鸡蛋 3 COUNT，份数 2、时间 30；
- 创建成功；
- `inventory_movements` 新增 0；
- 未在该空家庭继续查询 `matchRecipes`（测试菜谱随后清理），因此未把空库存匹配结果作为实测结论；按现有计算器，空库存应为 MISSING/UNKNOWN，而不是 ENOUGH。
- DemoHome 测试菜谱/快照已清理，成员关系已恢复。

未在空家庭中继续执行“加入计划”步骤；计划路径未改动。

### 2.4 数据一致性

公共导入前后逐字段核对：

| 字段 | 导入后 |
| --- | --- |
| ingredientId | 与字典一致 |
| quantity | 2 / 1000 / 1500 / 5 |
| unit | COUNT / ML / G / G |
| trackStock | true |
| yieldServings | 2 |
| durationMinutes | 20 |
| 家庭菜谱 ID | 创建响应 ID，无二次转换 |

核对后 `verifiedRequest` 再次校验，没有静默默认或换算。

## 3. 导入 UI 完善

- 保存前逐行校验：
  - 未选食材 → 具体原因变红；
  - 数量为空/非法 → 对应行原因；
  - 重复 ingredient → 对应行“重复食材”；
  - 底部显示“还有 N 行食材需要修正”。
- `confirmed` 标志防止同一对话框重复点击创建。
- `updateReason` 在用户修正食材/数量后清除旧错误原因。
- `ScrollView` 支持长列表滚动（公共 4 行已验证；超长列表未人工走查）。
- 原始文本、规范食材、规范数量/单位、原因均显示。
- 取消不产生菜谱；校验失败不 POST。
- 360dp 手机尺寸检查：标题、原始文本、食材/数量、取消/导入按钮均可见可点；Spinner 文案在窄屏会省略（“请选择食…”），不影响操作。
- 未做复杂批量删除/恢复。

## 4. 手工菜谱份数/时间

- 编辑器新增可见、可编辑的“份数”“时间（分钟）”：
  - 新建预填 2/30（初始建议值）；
  - 编辑已有菜谱预填原值；
  - 保存校验份数为正数、时间 1–1440；
  - 只改菜名不会重置份数/时间/食材。
- 实机编辑 `Family repro`：初始显示 2/20；改为 25 保存后 DB 为 25；已恢复为 20。
- 新手工菜谱创建时的 2/30 是可见可编辑建议值，不再是不可见的硬编码覆盖。

## 5. 历史数据处理

- 不自动覆盖历史食材。
- 不使用 tag 推断可靠来源（`家庭菜谱` 也可能来自手工创建）。
- 现有编辑器可修改数量并显示单位；保存后版本递增，Batch 4A 重新进入页面会按新数据重新匹配。
- 无法从历史记录恢复原始自由文本/数量；未虚构原始值。
- 未新增阻塞式历史提示；4B-1 已记录来源不可追溯的限制。

## 6. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest -PdevStackOff` | 通过 |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **201 tests / 0 failures**（新增无效份数/时间测试） |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `server: .\gradlew.bat test --rerun-tasks` | **98 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

## 7. 未完成/未执行

- 空家庭导入后“加入计划”的实机步骤（计划/权限代码未改）。
- 旋转、返回、切换页面时创建重复的故障注入（已有 `confirmed` 防重复点击；无自动重放）。
- 网络超时/失败后的 UI 恢复（错误只 toast，不显示虚假成功；核对内容会关闭）。
- 长食材列表（>10 行）滚动与字体放大逐项走查；360dp 已检查。
- 真机别名冲突、重复食材的逐项点按（单测覆盖）。
- 历史错误菜谱的人工核对流程/提示。
- 完整英文界面走查。

## 8. 剩余风险

- 导入核对对话框为程序化布局，窄屏 Spinner 文案会截断；长列表体验仍可优化。
- 网络失败时对话框已关闭，用户需重新进入核对；不会产生虚假成功。
- 新手工菜谱初始 2/30 是建议值，不是从来源推导；用户可修改。
- 历史错误数据无法自动恢复；只能人工修正。
- 空家庭测试中 DemoHome 的匹配快照未作为最终业务数据保留（已清理）。

## 9. Batch 4B 关闭建议

4B-1/2a/2b 的核心目标已达到：新导入不再默认 1/0/false，C 类必须修正，A/B 有严格换算，公共/个人路径可用，空冰箱不阻止导入，编辑保留已有属性，导入后匹配正确。

**建议关闭 Batch 4B，进入最终交付回归阶段**；上述未执行项列入最终回归清单，不构成阻塞缺陷。

## 10. 进入最终回归前的重点

1. 空家庭导入后加入计划/权限/锁定的端到端。
2. 旋转/返回/重复提交/网络失败。
3. 长列表、字体放大、窄屏 Spinner。
4. 历史菜谱人工修改后的库存状态刷新。
5. 既有做饭、食记、计划扣库存不变的完整回归。
