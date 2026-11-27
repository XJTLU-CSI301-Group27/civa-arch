# Phase A1 变更记录：结构化食材编辑器基础组件与状态

日期：2026-11-27（Asia/Shanghai）
基线：`940ff07`（Phase B1）
范围：仅 A1；未实施 A2/D/C，未修改服务端/数据库/OpenAPI。

## 1. 实现内容

- 新增纯 Java `IngredientEditorState`：
  - 稳定 long row id；
  - TRACKED / RECIPE_ONLY / LEGACY_UNTRACKED；
  - 添加/删除/编辑/显式转换；
  - 双状态保留；
  - 校验、重复 ID、历史 false 行；
  - `recipeOnlyDirty` / update payload 语义。
- 新增 `IngredientEditorViewModel`：跨旋转保留 state/dictionary/existing/imageId。
- 新增 `TrackedIngredientAdapter`（Spinner 选择字典食材）和 `RecipeOnlyIngredientAdapter`：
  - ViewHolder 不持有业务数据；
  - TextWatcher 绑定/解绑；
  - 稳定 ID；
  - 行内错误。
- 重写 `HouseholdRecipeEditorFragment` 食材区域：
  - 库存食材区；
  - 仅说明辅料区；
  - 添加/删除/转换；
  - 保存时发出 tracked + recipe-only 请求。
- 新增行布局和中英文文案。

## 2. 编辑状态模型

`IngredientEditorState.Row` 同时保存：

```
id
kind (TRACKED / RECIPE_ONLY / LEGACY_UNTRACKED)
ingredientId, trackedName, quantity, unit
recipeName, amountText, note
```

- TRACKED 有效数据来自 ingredientId/quantity/unit。
- RECIPE_ONLY 有效数据来自 recipeName/amountText/note。
- 切换只改变 kind，保留两侧字段。
- TRACKED → RECIPE_ONLY 时，若自由文本为空，用 `quantity + unit` 作为建议文本。
- RECIPE_ONLY → TRACKED 时，保留自由文本，但必须重新选择字典食材并填写正数数量。

### 保存 payload

- Create：当前 recipe-only 列表（空则空）。
- Update：
  - `recipeOnlyDirty=false` → `recipeOnlyIngredients=null`（保留服务端已有）；
  - 用户改动 → 非空数组替换，全部删除 → `[]` 清空。
- 仅修改菜名/时间/步骤/TRACKED 行不会设置 dirty。
- 历史 false 行进入 TRACKED payload 且 `trackStock=false`，不自动转换。
- 显式“转为仅说明辅料”才把 false 行移出 tracked、写入 recipe-only，并设置 dirty。

## 3. RecyclerView 实现

- 两个 `RecyclerView`（库存食材 / 仅说明辅料），外层现有 ScrollView，`nestedScrollingEnabled=false`。
- 行 Adapter 使用稳定 `row.id`；业务数据从 `IngredientEditorState` 读取。
- 每次 bind 先移除旧 TextWatcher，再设置文本/选择器，再绑定新 watcher；`binding` 标志阻止程序化 setText 触发回写。
- TRACKED 名称使用 Spinner（字典 canonical name），避免 AutoCompleteTextView 输入/IME 状态问题；单位随选择更新。
- 删除/转换后重新提交行列表；普通文本编辑不触发整表刷新，避免打断光标。
- 旋转：ViewModel 保留 state；实机旋转后行、名称、数量仍在。

## 4. 历史 `trackStock=false`

- 加载为 `LEGACY_UNTRACKED`。
- 在“库存食材”区只读展示：
  - 食材选择器禁用；
  - 数量禁用；
  - 显示“旧数据：不参与库存”；
  - 删除按钮隐藏；
  - 只提供“转为仅说明辅料”显式操作。
- 不转换时，保存其他字段会把原 false 行原样放回 `ingredients`（trackStock=false）。
- 转换后从 TRACKED payload 移除，作为 RECIPE_ONLY 保存；原 quantity/unit 变成建议文本。

## 5. B1 字段保存/保留策略

| 场景 | 行为 |
| --- | --- |
| 旧客户端更新，无字段 | 服务端 B1 保留 |
| 编辑器只改菜名 | `recipeOnlyPayloadForUpdate()` 返回 null，保留 |
| 只改 TRACKED | null，保留 |
| 添加/编辑/删除辅料 | 发送完整列表；空列表清空 |
| 新建菜谱 | 发送当前 recipe-only 列表 |
| 仅 RECIPE_ONLY 菜谱 | ingredients=[] + recipe-only 列表 |
| 仅 TRACKED 菜谱 | recipe-only=[] |
| 两类都空 | 客户端阻止保存 |

## 6. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest -PdevStackOff` | 通过（新增 `IngredientEditorStateTest` 10 项） |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **211 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

状态测试覆盖：添加/编辑/删除两类行、双向转换、双状态保留、仅辅料/混合/空、重复 ingredient、数量/单位、历史 false、payload null/[]、标题长度/备注、稳定 ID。

## 7. UI 验证

设备 MI PAD 4 PLUS：

- 新建家庭菜谱编辑器：
  - 显示“库存食材 / 仅说明辅料”两个区域；
  - 添加库存食材行后 Spinner 可选择 `beef`，单位自动显示“克”；
  - 数量输入正常；
  - 添加仅说明辅料行，名称/用量/备注字段正常；
  - 保存/取消可达。
- 已有 A1Probe（通过 API 创建：beef 2 G + salt 适量）：
  - 编辑器正确显示 tracked 行与 recipe-only 行；
  - 仅修改菜名为 A1Probe2 后保存；
  - DB 仍为 `beef 2 G` 且 `recipe_only_ingredients=[{"name":"salt","amountText":"to taste"}]`；
  - version 1→2，证明未清空辅料。
- 360dp/160dpi 手机尺寸：
  - 库存行名称/删除、数量/单位、转换按钮均可见；
  - 布局无横向溢出；仅说明区在下方可滚动。
- 旋转（user_rotation=1）：
  - 名称、tracked 行、数量 2 仍保留（ViewModel state）。
- 测试菜谱/快照已清理；OurHome 原有菜谱不变。

截图：`review/a1-phone-editor.png`、`review/a1-spinner.png`。

## 8. 未执行测试

- 通过 UI 从空白表单完成“新建并保存”全流程；本轮新建数据通过 API 创建，UI 重点验证加载/编辑/只改名保留。
- 行回收压力（滚动大量行后逐行核对）与仪器化 RecyclerView 测试。
- 历史 false 行的真机只读/转换走查（状态与 B1 HTTP 测试覆盖）。
- 字体放大、深色模式、英文界面逐页走查。
- 保存中旋转/网络失败/重复点击的故障注入。
- 仅 RECIPE_ONLY 菜谱的 UI 创建保存。

## 9. 剩余风险

- 外层 ScrollView + 两个 wrap_content RecyclerView 会一次性测量所有行，长列表（接近 100 行）性能不如单 RecyclerView；A2 可改为 header/footer 单一 RecyclerView。
- Spinner 没有搜索；字典较大时选择效率一般；需求允许“名称选择器”，搜索可 A2 增强。
- 旧字典缺失名称的历史 tracked 行显示空选择器，但保存仍保留 ingredientId；需要 A2 明确提示。
- 状态校验错误目前每行只显示首个错误；完整字段级错误可在 A2 增强。
- 转换没有二次确认；按钮是显式入口，但误触风险存在。
- 仅通过 API 创建验证 recipe-only-only 的服务端路径；UI 创建路径仍待走查。

## 10. Phase A2 明确清单

1. 单一 RecyclerView/header 或 RecyclerView 嵌套滚动性能优化。
2. 完整 UI 创建/编辑/删除/转换 E2E 和仪器化行复用测试。
3. 字典名称搜索/空字典/缺失名称提示。
4. 行级字段错误完整展示、滚动定位首个错误。
5. 保存中旋转、网络失败重试、重复点击保护。
6. 历史 false 行转换确认与文案。
7. 字体放大/深色/英文/360dp 全量走查。
8. 仅 RECIPE_ONLY 菜谱 UI 创建。
9. 与 Phase D 导入处置的接口边界（不在 A2 实现导入）。
