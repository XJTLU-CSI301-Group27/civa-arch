# Batch 4B-1 分析：公共/个人菜谱导入家庭的食材数据保真

日期：2026-11-27（Asia/Shanghai）
基线：`2efacf2`（Batch 4A 关闭）
状态：**仅分析，未修改业务代码/契约/数据库**。

## 1. 结论摘要

当前导入流程**无法保真**，且不是单点问题：

1. 公共/个人菜谱的 `unit`、`quantityPrefix`、`note`、`sourceText` 在客户端转换时被丢弃；
2. 数量只取最后一个空格后的数字，否则**默认 1**；
3. 单位强制使用食材字典的规范单位，`trackStock` 强制 `true`；
4. 服务端/数据库**不允许** NULL 数量或未知单位，`quantity=0` 会被计算器当成“需要 0”，`trackStock=false` 会被当成“无需库存”，二者都不能表示“未知”；
5. 导入直接创建家庭菜谱，**没有用户核对/修正步骤**；
6. 编辑已有家庭菜谱时，食材行不显示单位，保存会重新解析，并把 `yieldServings`/`durationMinutes` 重置为 2/30。

因此，4B-2 不应继续“猜测后直接保存”。最小安全方案是：**严格解析 + 导入前用户确认/补齐；无法可靠转换的食材不得保存，直到用户给出有效数值**。这不需要修改 API/数据库。

## 2. 现有导入数据流

```
CatalogRecipeIngredient / PersonalRecipeIngredient
  { name, quantity?, quantityPrefix?, unit?, note?, sourceText? }
        │
        ▼
HouseholdRecipes.catalogIngredientLines / personalIngredientLines
  line = name + " " + quantity          ← unit/prefix/note/sourceText 丢弃
        │
        ▼
HouseholdRecipes.request(name, steps, lines, dictionary)
  - 名称：lower 包含匹配（非精确、未用 aliases）
  - quantity：最后一个空格后的数字；否则 "1"
  - unit：字典食材的规范单位（G/ML/COUNT）
  - trackStock：true
  - yieldServings：固定 "2"
  - durationMinutes：固定 30
  - tags：固定 "家庭菜谱"
        │
        ▼
CreateRecipeRequest { RecipeIngredient { ingredientId, quantity, unit, trackStock } }
        │
        ▼
HouseholdRecipeService.validate
  - ingredientId/quantity/unit/trackStock 非空
  - 不校验 quantity > 0；不校验 unit 与字典一致
        │
        ▼
recipe_ingredients
  quantity numeric(18,3) NOT NULL CHECK >= 0
  unit varchar(5) NOT NULL，FK (ingredient_id, unit) → ingredients
  track_stock boolean NOT NULL
        │
        ▼
InventoryCalculator / matchRecipes
  track_stock=false 的行被跳过
  required = quantity * servings / yield
  quantity=0 → required 0 → ENOUGH
        │
        ▼
RecipeStockPresenter / Batch 4A UI
```

关键入口：`RecipeCatalogDetailFragment.addToHousehold()`（约 `:602`）直接调用 `createRecipe`，没有 review 步骤；`HouseholdRecipeEditorFragment.submit()` 用于手动编辑。

## 3. 各字段真实类型与限制

| 层 | 字段 | 类型/约束 | 能否表示未知 |
| --- | --- | --- | --- |
| Catalog/Personal | `name` | string，必填 | 否（名称必须存在） |
| Catalog/Personal | `quantity` | string，可空 | 可空，但导入后无法保存 |
| Catalog/Personal | `quantityPrefix` | string，可空 | 可空 |
| Catalog/Personal | `unit` | string，可空（“克/勺/适量/个…”） | 可空，但导入后丢弃 |
| Catalog/Personal | `note` / `sourceText` | string，可空 | 可空，导入后丢弃 |
| Household `RecipeIngredient` | `ingredientId` | UUID，必填，必须已存在 | 否 |
| Household `RecipeIngredient` | `quantity` | string，正则 `0|[1-9]…`，必填 | **否** |
| Household `RecipeIngredient` | `unit` | 枚举 G/ML/COUNT，必填 | **否** |
| Household `RecipeIngredient` | `trackStock` | boolean，必填 | 否（false≠未知） |
| DB `recipe_ingredients` | `quantity` | numeric(18,3) NOT NULL，CHECK >=0 | 否 |
| DB `recipe_ingredients` | `unit` | varchar(5) NOT NULL，FK 到 `ingredients(id,unit)` | 否 |
| DB `recipe_ingredients` | `track_stock` | boolean NOT NULL | 否 |
| DB `recipe_ingredients` | 主键 | `(recipe_id, ingredient_id)`，同一菜谱不能重复食材 | 否 |
| DB `recipes` | `yield_servings` | numeric >0 | 否 |
| DB `recipes` | `duration_minutes` | 1–1440 | 否 |

服务端 `HouseholdRecipeService.validate` 只检查非空；`quantity=0` 能通过服务端与 DB，但计算器会把它当成“需要 0”，因此**不能用 0 表示未知**。单位不一致会被 DB 复合外键拒绝（服务端未给出友好 400，可能表现为 500）。

## 4. 信息丢失位置

| 位置 | 丢失/错误 |
| --- | --- |
| `catalogIngredientLines` / `personalIngredientLines` | 丢弃 `unit`、`quantityPrefix`、`note`、`sourceText`，只保留 name + quantity |
| `HouseholdRecipes.request` | 数量失败默认 `1`；单位用字典单位；`trackStock=true`；yield 固定 2、duration 固定 30；tag 固定 |
| 名称匹配 | `lower.contains` 包含匹配、只看 canonical name，不用 aliases；可能匹配错食材 |
| `CreateRecipeRequest` / `RecipeIngredient` | 没有原始文本、未知数量、单位文本字段 |
| 服务端 `validate` | 不校验 quantity > 0、不校验 unit 与字典一致（DB 才拒绝） |
| `recipe_ingredients` | quantity NOT NULL、unit 枚举 + FK；无法保存“适量/2 勺” |
| `HouseholdRecipeService.snapshot` | 个人快照只写规范 name/quantity/unit，原始文本不保留 |
| `HouseholdRecipeEditorFragment` | 编辑时食材行只显示“名称 数量”，不显示单位；保存重新解析 |
| `HouseholdRecipes.request`（编辑复用） | 编辑已有菜谱也把 yield/duration 重置为 2/30 |

## 5. 现有数据事实（开发库只读查询）

公共菜谱 `recipe_catalog` 共 9391 条食材：

| 单位 | 数量 |
| --- | --- |
| 克 | 3048 |
| （空） | 2019 |
| 勺 | 1337 |
| 适量 | 638 |
| 个 | 569 |
| 根/片/瓣/少许/毫升/小勺/颗/块/只/朵/小把… | 其余 |

- 2865 条 quantity 为 null（约 30%）；2019 条 unit 为 null。
- 个人菜谱 `user_recipes` 17 条食材，9 条 quantity/unit 为空，6 条 G，2 条 个。
- 现有 `家庭菜谱` tag 的 3 道菜均为 1G 食材（OurHome `FamilyTest2`、test `aaaa`/`bbbbb`）；**无法确认是否由旧导入产生**，`recipes` 没有来源 ID，tag 也被手动编辑器使用。
- 现有 `FamilyTest2` 的 `beef 1.000G` 可能是错误默认/换算结果，但原始数据已不可恢复。

## 6. 转换规则分类（保守）

| 输入示例 | 条件 | 类别 | 处理 |
| --- | --- | --- | --- |
| `2 个` | 字典食材单位为 COUNT | A 直接 | 2 COUNT |
| `200 克` | 食材单位为 G | A 直接 | 200 G |
| `100 ml` | 食材单位为 ML | A 直接 | 100 ML |
| `1.5 kg` | 食材单位为 G | B 换算 | 1500 G（×1000） |
| `1 L` | 食材单位为 ML | B 换算 | 1000 ML |
| `2 勺` | 任意 | C 不可靠 | 无标准 ml，禁止猜测；要求用户按规范单位填写 |
| `少许` / `适量` / `一小把` | 任意 | C | 无数值，要求用户补充 |
| 空 quantity | 任意 | C | 要求用户补充 |
| `0` / 负数 | 任意 | C（拒绝） | 不可保存；当前 0/负数会落到默认 1，4B-2 必须改为拒绝 |
| `2`（无单位） | 任意 | C | 不能假定克/个 |
| `2 个` | 食材单位为 G/ML | C | 个↔克无换算 |
| `200 克` | 食材单位为 ML/COUNT | C | 质量↔体积/个数无换算 |
| `2 个` | 食材单位为 COUNT | A | 直接 |
| `约 2 个` / `半` | 任意 | C（或先让用户确认） | 前缀/近似不是精确值 |
| 原始单位 = 字典单位 | 任意 | A | 直接保存 |
| 名称在字典中找不到 | 任意 | C | 不可保存；先解决食材映射 |

客户端**不换算 G↔ML↔COUNT**；只允许同维度、定义明确的质量/体积换算（kg→g、L→ml）。

## 7. 现有业务风险

1. **默认 1 掩盖缺失**：`适量`、`少许`、空数量、`2 勺` 都可能被保存为 1（字典单位），使库存判断错误。
2. **单位被静默替换**：`2 勺` 可能变成 2G（或字典单位），匹配结果失真。
3. **yield/duration 被重置**：导入与编辑都把份数设为 2、时长设为 30，影响 `required = quantity × servings / yield`。
4. **trackStock=false 不能表示未知**：会让整道菜“无需库存/ENOUGH”，比误报缺料更危险。
5. **无法区分“缺库存”与“食材数据不完整”**：Bad data 会显示为可制作/缺具体数量，而不是 UNKNOWN。
6. **名称包含匹配**：如字典有“鸡蛋”和“鸡”，`contains` 可能匹配错误；aliases 未使用。
7. **导入无核对**：用户无法在保存前发现/修正错误。
8. **历史数据不可追溯**：无 origin ID，原始自由文本未保存，不能批量修复。
9. **编辑可能进一步破坏**：编辑不显示单位，保存重置 yield/duration。
10. **重复食材可能插入失败**：`recipe_ingredients` 主键为 `(recipe_id, ingredient_id)`；源菜谱同名食材出现两次时，当前 request 会生成重复 ID，创建可能 500。4B-2 应合并或阻止重复行。
11. **当前 0/负数不是被拒绝，而是落回默认 1**：`request` 只在正数时采用尾数，否则 `quantity="1"`。这会掩盖非法值，4B-2 必须显式拒绝。

## 8. 最小安全修复方案（推荐 4B-2，客户端 only）

### 8.1 严格解析器

新增纯逻辑 `IngredientImportParser`（feature-ac）：

- 输入：`CatalogRecipeIngredient`/`PersonalRecipeIngredient`、字典；
- 输出 `ImportLine`：
  - `sourceText`（原始 name/quantity/unit/prefix/note）
  - `ingredient`（精确名称/别名匹配；找不到为 null）
  - `quantity`（原始文本）
  - `unit`（原始单位文本）
  - `convertedQuantity` / `convertedUnit`（仅 A/B）
  - `status A/B/C` + 原因。
- 名称匹配改为**精确名称/别名**，不使用 `contains`；找不到 → C（先解决食材）。
- 不猜测数量、不默认 1、不使用 0、不自动 `trackStock=false`。

### 8.2 导入前核对

在 `RecipeCatalogDetailFragment.addToHousehold` 中：

1. 拉取字典、解析所有行；
2. 打开**导入核对界面**（可复用编辑器样式）：
   - A：预填“名称 + 规范数量”；
   - B：预填换算后的规范数量，并显示“由 1.5 kg 换算为 1500 g”说明；
   - C：高亮“需要确认”，显示原始“2 勺 / 适量 / 2 个”，要求用户输入规范单位数值或取消；
3. 保存按钮仅当所有行都是 A/B 且可保存时启用；
4. 用户确认后才调用 `createRecipe`；
5. 全程不检查库存，冰箱为空也可导入。

如果继续使用 `HouseholdRecipeEditorFragment`，需要增加：
- 导入预填参数（name/steps/lines/原始提示）；
- 保存前严格校验：任何 C 行阻止保存并列出；
- 保留已有 `yieldServings`/`durationMinutes`，不再固定 2/30；
- 显示单位/原始文本提示（否则用户不知道“2”是克还是个）。

### 8.3 严格保存

`HouseholdRecipes.request` 改为：

- 只接受“已解析且 A/B”的行；
- quantity 必须是正数（拒绝 0/负数/空）；unit 必须等于字典单位；
- `trackStock=true`（自动导入不产生 false）；
- `yieldServings`/`durationMinutes` 使用源菜谱的有效值；源无效时让用户确认，不固定 2/30；
- 不保留原始自由文本（当前模型无法保存）。

### 8.4 历史数据

- 不批量覆盖；
- 对已有 `家庭菜谱` 不做自动标记（tag 不可靠）；
- 提供人工核对建议：按菜名在原公共/个人菜谱中比对，手动编辑数量；
- 修复编辑器的 yield/duration 重置，避免二次破坏；
- 新导入可在 tag/note 中记录来源（可选，需产品确认），但不解决旧数据。

## 9. 是否需要 API/数据库变更

**4B-2 推荐方案不需要**：只要保证“未确认的行不保存”，所有持久化行都是完整数值/规范单位。

如果产品要求“保留原始 2 勺/适量，并在库存匹配中显示 UNKNOWN”，则最小变更：

- `RecipeIngredient` 增加 `quantityKnown: boolean`（或 `sourceText`/`unitText`），
- `recipe_ingredients` 增加 `quantity_known bool NOT NULL DEFAULT true` + `source_text text`，
- `InventoryCalculator` 对 `quantityKnown=false` 返回 UNKNOWN，而不是 ENOUGH；
- Contract + IC + 迁移 + UI + 测试。

不建议把 `quantity` 直接改为 nullable：会影响做饭确认、分配、计算器和所有现有校验，范围过大。

## 10. 客户端修改范围（4B-2）

| 文件 | 改动 |
| --- | --- |
| `catalog/IngredientImportParser.java`（新增） | A/B/C 解析、单位换算、别名匹配 |
| `catalog/HouseholdRecipes.java` | 严格 request；保留 yield/duration；移除默认 1 |
| `catalog/RecipeCatalogDetailFragment.java` | 导入前核对/预填；不直接 create |
| `catalog/HouseholdRecipeEditorFragment.java` | 预填、C 行阻止保存、保留 yield/duration、单位提示 |
| `res/layout/*`、`ac_strings.xml` | 核对界面/提示 |
| `test/.../IngredientImportParserTest.java` | 转换/边界单测 |
| 可选 `HouseholdRecipeEditorTest` | 编辑保留 yield/duration |

无服务端/数据库/契约改动。

## 11. 测试矩阵

| 场景 | 期望 |
| --- | --- |
| `2 个` + COUNT | A，2 COUNT |
| `200 克` + G | A，200 G |
| `100 ml` + ML | A，100 ML |
| `1.5 kg` + G | B，1500 G |
| `2 勺` / `适量` / `少许` / 空 | C，阻止保存直到用户填写 |
| `2 个` + G 食材 | C，不换算 |
| `2` 无单位 | C |
| 名称找不到/别名 | C/正确匹配，不用 contains |
| 0/负数/非法小数 | 拒绝，不默认 1 |
| 冰箱为空导入 | 成功，不检查库存 |
| 导入后 4A 匹配 | 数量/单位正确，MISSING/ENOUGH 与真实一致 |
| 导入 C 行 | 不创建任何家庭菜谱 |
| 编辑已有菜谱 | yield/duration 不被重置；数量可改；无单位误改 |
| 历史数据 | 不批量覆盖 |
| 做饭/食记 | 库存规则不变 |
| Batch 3 权限 | 不受影响 |

## 12. 工作量与交付影响

| 工作 | 估算 |
| --- | --- |
| 严格解析器 + 单测 | 1 人日 |
| 导入核对/预填 UI | 2 人日 |
| 严格保存 + 编辑器保留字段 | 1 人日 |
| 历史数据/文案/回归 | 1 人日 |
| 实机与测试 | 1 人日 |
| **4B-2 合计** | **5–6 人日** |

距 12/07 约 10 天。建议拆成：
- **4B-2a（优先，2–3 天）**：解析器 + 阻止 C 行/默认 1 + 保留 yield/duration；即使 UI 仍是简单编辑器，也先杜绝新错误数据。
- **4B-2b（2–3 天）**：完整核对界面、别名/单位提示、历史人工核对指引。
- 不建议在 4B-2 做 API/DB 的“未知数量”字段；如必须保留原文，另立 4B-3。

## 13. Batch 4B-2 实施建议

1. 先做 `IngredientImportParser` + 严格 `request`（禁止默认 1/0/false），用单测锁死 A/B/C。
2. `addToHousehold` 不再直接创建；先解析，存在 C 时打开核对/编辑器，保存按钮禁用直到补齐。
3. A/B 行预填规范数量；B 显示换算说明；C 显示原始文本并要求用户填写。
4. 保留源 `yieldServings`/`durationMinutes`（有效时）；编辑器更新不得重置。
5. 不检查库存；不写库存；不改做饭/食记。
6. 历史数据只做人工核对说明，不批量迁移。
7. 若课程时间不足，至少完成 4B-2a，避免继续产生错误导入数据。

## 14. 需要确认的决策

1. C 类食材是否必须补齐后才能保存（建议是），还是允许用户显式选择“不计库存”（不建议，会退化为 ENOUGH）。
2. `kg/L` 是否允许确定性换算（建议允许）；`斤` 是否支持（建议暂不支持，归 C）。
3. 导入核对复用家庭菜谱编辑器，还是新建独立核对页（编辑器当前无单位/原文展示，独立核对页更清晰但工作量更大）。
4. 是否在新导入菜谱上记录来源 tag（如“导入”），用于后续人工核对；旧数据无法追溯。
5. 源菜谱 `servings`/`estimatedMinutes` 无效或缺失时，是否要求用户填写，而不是固定 2/30。
