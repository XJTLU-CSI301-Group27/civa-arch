# Phase A2 变更记录：结构化编辑器闭环与交互稳定性

日期：2026-11-27（Asia/Shanghai）
基线：`904ea61`（A1）
范围：仅 A2；未修改服务端、数据库、Flyway、OpenAPI。

## 1. 实现内容

- 可搜索食材选择：
  - 新增 `IngredientSearch`（规范名 + aliases，大小写不敏感）；
  - 新增 `IngredientPickerDialog`（搜索框 + 列表，必须显式选择）；
  - TRACKED 行从 Spinner 改为可点击选择字段；
  - 选择后显示规范名称/单位；未选中/字典缺失有明确文案。
- 字典失败：
  - `bindState` 显示字典加载失败提示；
  - 禁止新增 TRACKED 行；
  - 已有 TRACKED 行保留，不静默替换。
- 保存稳定性：
  - `IngredientEditorViewModel` 增加 `saving/error/saved` LiveData；
  - 旋转后新 Fragment 观察同一状态，避免旧回调丢失；
  - 创建使用 ViewModel 中稳定的 `createKey`（服务端已有 Idempotency-Key 支持），失败重试不会生成第二个幂等键；
  - `saving` 阻止重复点击；错误不显示虚假成功。
- 转换确认：
  - TRACKED/LEGACY ↔ RECIPE_ONLY 转换前显示确认对话框；
  - 取消不改变类型。
- 已有 A1 结构：state 模型、双状态保留、历史 false 只读、payload null/[] 语义。

## 2. 修改文件

**新增**
- `IngredientSearch.java`
- `IngredientPickerDialog.java`
- `IngredientSearchTest.java`

**修改**
- `HouseholdRecipeEditorFragment.java`
- `IngredientEditorViewModel.java`
- `TrackedIngredientAdapter.java`
- `ac_fragment_household_recipe_editor.xml`
- `ac_item_ingredient_tracked.xml`
- 中英文 `ac_strings.xml`
- 本变更记录

A1 的 `IngredientEditorState`、`RecipeOnlyIngredientAdapter` 未重写，仅沿用。

## 3. 搜索选择器实现

- 行名称为 `TextView` 样式字段，点击打开 `IngredientPickerDialog`。
- Dialog：
  - `EditText` 搜索；
  - `IngredientSearch.filter` 按 canonical name/aliases 过滤；
  - `ListView` 只展示过滤结果；
  - 点击某一项才返回具体 `Ingredient`，因此搜索结果与实际选中 ID 一致；
  - 无匹配显示“没有匹配的食材；可改用仅说明辅料”；
  - 空字典显示“食材字典加载失败”。
- 选中后：
  - state 保存 ingredientId；
  - 单位由字典决定；
  - 行显示规范名称 + 单位。
- 历史 ingredientId 不在当前字典：
  - 行显示“字典中找不到该食材”；
  - 不自动替换/删除；
  - 用户可显式打开选择器重新选择，取消则保留原 ID。
- 未匹配名称不能成为 TRACKED；不会自动转 RECIPE_ONLY。

## 4. 保存与生命周期

- `createKey` 在 ViewModel 初始化时生成一次，创建请求复用它；服务端现有幂等键语义保证响应丢失后的重试不会重复创建。
- `saving` LiveData：
  - 提交中禁用保存；
  - 旋转后新 Fragment 仍处于禁用状态；
  - 错误回调设置 error + saving=false，新的 Fragment 也能收到并提示；
  - 成功设置 saved，新的 Fragment 收到后 pop。
- `vm.error` / `vm.saved` 是单次 UI 事件；观察者消费后清空。
- 更新仍使用 `expectedVersion`；版本冲突不会覆盖他人修改。
- 服务端 `createRecipe` 幂等语义未修改。

## 5. 真实 UI 验证结果

基线设备 MI PAD 4 PLUS（1200×1920 / 320dpi），OurHome：

- 结构化编辑器显示两个区域。
- 添加 TRACKED 行，点击名称字段打开搜索 Dialog：
  - 列表显示 `aaaa/bbbbb/beef/...` 及单位；
  - 选择 `beef` 后行显示 `beef`，单位自动“克”；
  - 数量 3 输入正常。
- 添加 RECIPE_ONLY 行，名称/用量/备注字段显示正常。
- 转换按钮弹出确认对话框：
  - 标题“转换食材类型”；
  - 说明保留内容、按新类型提交；
  - 取消后行类型不变。
- 字典搜索/选择器已在设备打开验证；搜索过滤本身由 `IngredientSearchTest` 覆盖。
- 360dp/160dpi 编辑器布局此前 A1 已验证；本轮选择器为弹窗，未在 360dp 下逐项走查。
- API 创建的混合菜谱（beef 2 G + salt/to taste）在编辑器加载、只改名保存后，DB 保留 recipe-only 与 tracked 数量（A1 已记录；A2 未重复该 API seed）。

### 未完成的 P0 端到端

**未完成**：从空白 UI 完整输入并保存 TRACKED/RECIPE_ONLY/混合三种菜谱。当前测试设备只有 Pinyin IME，`adb input text` 对普通文本输入不稳定；已验证添加行、打开选择器、选择食材、输入数字、转换确认等交互，但未完成“菜名/步骤/辅料文本输入 + 保存”的自动化全流程。不能宣称三种 UI 创建场景已通过。

服务端 B1 的 create/read/仅 recipe-only/匹配/快照路径有 105 项 HTTP/DB 测试；A1 的 API seed + UI 编辑/保存 also passed。

## 6. 历史 false 行

- 保持 A1 行为：`LEGACY_UNTRACKED` 只读；
- 显示“旧数据：不参与库存”；
- 删除隐藏，只能显式“转为仅说明辅料”；
- 转换前新增确认对话框；
- 不转换时保存其他字段会原样保留 trackStock=false；
- 本轮无真机 false 行样本；状态/B1 测试覆盖。

## 7. B1 字段保存/保留策略

- 未修改 recipe-only：`null` 保留；
- 显式清空：`[]`；
- 修改：完整列表替换；
- 创建：当前列表；
- 只改菜名/TRACKED：仍 null；
- 仅 RECIPE_ONLY：`ingredients=[]` + 列表；
- 仅 TRACKED：列表为空。
- 稳定 `createKey` 不改变 payload 语义。

## 8. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest -PdevStackOff` | 通过（新增 `IngredientSearchTest` 3 项） |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **214 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

## 9. 未执行/未完成

- 空白 UI 创建 TRACKED、RECIPE_ONLY、混合三种菜谱的完整 E2E（设备 IME 自动化限制）。
- 搜索 Dialog 在 360dp/字体放大/深色/英文下的逐项布局走查。
- 保存期间旋转、网络失败、版本冲突的设备故障注入；代码使用 LiveData/幂等键，未实测。
- 历史 false 行真机转换走查。
- 大量行滚动的逐行核对/仪器化 RecyclerView 测试。
- 转换确认后的真机保存结果。
- 仅 RECIPE_ONLY 菜谱 UI 创建。

## 10. 剩余风险

- `createKey` 在提交失败后若用户修改表单再重试，仍复用同一 key；服务端可能返回幂等冲突。最小建议：用户修改表单后生成新 key，或失败后重置 key；A2 未实现。
- LiveData saved/error 事件在极端重复回调下可能重复消费/重复 toast；当前单次请求。
- 选择器无当前项高亮、无最近使用；搜索只做子串。
- 字典失败时已有 TRACKED 行仍显示 missing，但保存保留 ID；用户无法在无字典时改选。
- 两个 wrap_content RecyclerView 长列表性能。
- P0 空白 UI 创建未完成，A2 不能宣称完整闭环。

## 11. Phase A 是否满足关闭条件

**不满足完整关闭条件**。A2 的组件、选择器、保存路径安全性、转换确认已实现，但用户要求的三个空白 UI 创建 E2E 未完成，且网络/旋转故障注入未实测。建议：
- 验收 A2 代码与已完成交互；
- 或补做 UI 创建 E2E（需要可用的文本输入/人工操作）后再关闭 Phase A。

## 12. Phase D 必要前置工作

1. 完成 A2 的空白 UI 创建/混合/仅 recipe-only E2E。
2. 明确导入 Dialog 的每行处置（TRACKED/RECIPE_ONLY）与新编辑器状态复用。
3. 详情页展示 recipe-only（当前 A2 未验证）；
4. 导入后 match/preview 回归；
5. 失败重试/幂等/版本冲突的 UI 策略。

## 13. 测试数据清理

- 未创建新的 A2 业务菜谱；A1Probe/A1Probe2 与快照已在此前清理。
- OurHome 当前仍为原 6 道菜谱（Family repro、FamilyTest2、Tomato Egg、TomatoEgg、was、联动测试菜）。
- 库存/计划/做饭数据未改动。
- App 当前处于未保存的新建编辑器状态，未提交数据。
