# Batch 4B-2a 变更记录：严格食材解析、数据保真与安全导入

日期：2026-11-27（Asia/Shanghai）
基线：`f8f9694`（4B-1 分析）
范围：仅 4B-2a；未修改服务端、数据库、OpenAPI、InventoryCalculator、`RecipeStockPresenter` 库存匹配语义。

## 1. 实现内容

- 新增纯逻辑 `IngredientImportParser`：
  - 精确规范名/别名匹配，不使用 contains；
  - 别名冲突 → 需确认；
  - A 直接、B 确定性换算、C 需人工确认；
  - 保留 name/quantity/unit/quantityPrefix/note/sourceText；
  - 不默认 1/0、不 `trackStock=false`；
  - BigDecimal、最多 3 位小数、数据库范围校验。
- 新增 `ImportReviewDialog`：
  - 展示原始食材、规范食材下拉、系统数量/单位、修正原因；
  - A/B 预填；C 必须选择食材并填正数；
  - 可移除重复行；
  - 份数/时间可确认/修改；
  - 全部通过后才回调创建；取消不产生任何数据。
- `HouseholdRecipes`：
  - `request(...)` 不再默认 1，quantity 必须为正数；
  - 新增 `request(..., yield, duration)` 与 `verifiedRequest(...)`；
  - 拒绝重复 ingredientId、单位不一致、非正数、超范围、未确认 yield/duration。
- `RecipeCatalogDetailFragment.addToHousehold`：
  - 不再直接创建；先解析并打开核对；
  - 字典分页加载；
  - 创建成功后导航到家庭菜谱详情。
- `HouseholdRecipeEditorFragment`：
  - 编辑已有菜谱保留原 yield/duration；
  - 显示食材单位；
  - 保存严格校验，不再默认 1；
  - 新手工菜谱仍沿用 2 份/30 分钟的旧默认（未新增份数/时间输入，属 4B-2b 范畴）。

## 2. 解析规则

| 输入 | 条件 | 结果 |
| --- | --- | --- |
| `2 个` | 食材单位 COUNT | A，2 COUNT |
| `200 克` | G | A，200 G |
| `100 ml` | ML | A，100 ML |
| `1.5 kg` | G | B，1500 G |
| `1 L` | ML | B，1000 ML |
| `2 勺` / `少许` / `适量` / `一小把` | 任意 | C |
| 空数量 / 无单位 / 约数前缀 | 任意 | C |
| `2 个` + G 食材 | 任意 | C（不换算） |
| 0 / 负数 / 非法小数 / 超范围 | 任意 | 拒绝 |
| 精确名称/别名 | 字典唯一命中 | 可解析 |
| `鸡蛋液`（字典仅“鸡蛋”） | contains 不匹配 | C NO_INGREDIENT |
| 一个别名对应多个食材 | — | C AMBIGUOUS |
| 同一 ingredient 重复 | — | C DUPLICATE，禁止静默覆盖 |

- `sourceText` 可恢复 `面粉 200g` 这类缺失字段；仅当去掉测量值后的 base 精确命中字典时采用。
- 同名但 `2 勺` 等无法确定单位时，不猜测。
- 0/负数/超过 `numeric(18,3)` 范围不保存。

## 3. 最小核对流程

1. 用户点“加入家庭”。
2. 客户端加载字典（分页）并严格解析源食材。
3. 打开核对 Dialog：
   - 原始文本；
   - 规范食材 Spinner；
   - 数量输入；
   - 规范单位；
   - 修正原因/已识别/已换算说明；
4. A/B 预填；C 高亮并要求修正；重复行可移除。
5. 用户确认份数与时间。
6. 全部有效后 `verifiedRequest` 再校验一次，才 POST `createRecipe`。
7. 取消或校验失败不创建任何菜谱，也不写库存。

无库存检查；冰箱为空不会阻止导入（代码路径未调用库存接口）。

## 4. 编辑已有菜谱兼容

- `Family repro` 编辑保存后：`yield=2`、`duration=20` 保持不变（旧版本会重置为 2/30）。
- 食材 `土豆 2 G` 保持不变。
- 编辑器新增单位提示与“单位：土豆：克”展示。
- 保存使用严格数量解析；旧菜谱中合法的数量/单位不被自动修改。
- 不批量覆盖历史菜谱。

## 5. 测试与结果

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest -PdevStackOff` | 通过（新增 `IngredientImportParserTest` 及 strict request 测试） |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **200 tests / 0 failures** |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat checkContracts` | BUILD SUCCESSFUL（contracts consistent） |
| `server: .\gradlew.bat test --rerun-tasks` | **98 tests / 0 failures** |
| `git diff --check` | 无空白错误 |

覆盖：A/B/C 示例、kg/L、勺/适量/少许/空/无单位、0/负数/非法/超范围、精确名/别名、contains 不匹配、别名冲突、单位不匹配、重复食材、sourceText 恢复、C 阻止创建、strict request、编辑器保留时间/单位。

## 6. 实机验证

设备 MI PAD 4 PLUS（1200×1920 / 320dpi），OurHome：

- 个人菜谱 `我的番茄炒蛋`（番茄 2 个、鸡蛋 3 个）：
  - 核对界面：`番茄` 无字典项 → C，显示“字典中没有这个食材”；`鸡蛋 3 个` → A 预填；
  - 不修正直接导入：显示“请修正标出的食材、份数和时间”，数据库无新菜谱；
  - 将番茄改为字典 `土豆`、数量 2，份数 2、时间 30：成功创建；
  - API `matchRecipes` 返回：土豆 required 2 / available 0 / MISSING；鸡蛋 required 3 / available 6 / ENOUGH；吻合；
  - 测试后清理导入菜谱及快照，原个人菜谱保留。
- 取消核对：未创建 `ImportBProbe`，无库存写入。
- `ImportBProbe`（milk 1 L）核对界面预填份数 2、时间 10；因字典 `milk` 单位为 G，`1 L` 被正确判为 C（单位不匹配），未强行换算。
- 编辑 `Family repro`：单位显示“土豆：克”，保存后 yield=2、duration=20、土豆 2 G 均保持。

## 7. 未执行

- 真机 B 类成功换算（`1.5 kg→1500 G` / `1 L→1000 ML` 仅单测覆盖；B probe 用了 G 单位食材，验证的是单位不匹配）。
- 真机公共菜谱导入（本次用个人菜谱；代码路径同一函数）。
- 真机空冰箱家庭导入（代码无库存检查；未构造空家庭）。
- 真机别名冲突/重复食材核对 UI（单测覆盖解析与 strict request）。
- 设备上的完整英文界面走查。
- 新手工菜谱份数/时间输入（仍默认 2/30；4B-2b）。

## 8. 剩余风险

- 核对 Dialog 为程序化 UI，未复用编辑器全部视觉；长食材列表滚动/极长文本仍需 4B-2b 优化。
- 单位只有规范单位；`2 勺` 用户必须自行换算成克/毫升/个，没有单位换算辅助。
- 新手工菜谱仍默认 2 份/30 分钟；编辑已有菜谱已修复。
- 历史错误导入数据未被批量修改；只保证新导入不再产生默认 1/错误单位。
- 源菜谱图片、tags 未保留（原有行为）。
- 未真机验证空冰箱，但导入路径无库存检查。
- 失败导入不清理用户可能已创建的字典食材（正常流程不注册新食材；仅手工编辑器注册）。

## 9. 4B-2b 必要工作

1. 核对页视觉优化/长列表体验；可选“全部移除/恢复”。
2. 手工菜谱创建增加明确份数/时间字段，移除 2/30 默认。
3. 历史数据人工核对指引/来源提示；不批量覆盖。
4. 真机公共菜谱、B 换算、空冰箱、别名冲突、成员权限回归。
5. 可选：为核对页增加“按规范单位换算帮助”（勺/杯等仅提示，不自动计算）。
