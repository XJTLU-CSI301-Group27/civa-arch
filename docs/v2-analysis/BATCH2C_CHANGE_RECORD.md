# Batch 2C 修改记录（拆分"确认做饭"与"记录用餐"）

日期：2026-11-27　分支：`refactor/core-ui-and-bd-modules`　基线：`53f1748`（Batch 2B 验收）
范围：仅 Android 客户端 `feature-ac`。无服务端、契约、数据库、依赖变更。

---

## 1. 目标与业务规则

修复 Batch 2B 验收中确认的高风险链路：计划菜「吃了」会先创建 CookingEvent 并扣库存，再创建 MealEntry；第二步失败后计划行仍显示"未记录"，再次点击会重复做饭、重复扣减。

本批把两个动作彻底拆开：

| 概念 | 由谁创建 | 是否影响库存 |
| --- | --- | --- |
| MealPlan | 「今天」页的个人计划（本地草稿） | 否 |
| CookingEvent | 菜谱详情页「做饭」→ 预览 → 确认 | 是（唯一扣减入口） |
| MealEntry | 食记「记一餐」/ 计划行「记录用餐」 | 否 |
| InventoryMovement | 仅由确认做饭产生 | — |

---

## 2. 修改前后对比

### 修改前（问题链路）

```
计划行「吃了」
  → 按名称查家庭菜谱
  → previewCooking → confirmCooking（扣库存，创建 CookingEvent）
  → 成功后再创建 MealEntry；失败则计划行仍为"未记录"
  → 再次点击「吃了」→ 新的一次确认做饭 → 重复扣库存
```

### 修改后

```
计划行
  ├─「记录用餐」→ 打开食记输入（预填菜名/餐次/日期）→ 保存 → 只创建 MealEntry
  └─「做饭」    → 按名称精确匹配家庭菜谱
                    ├─ 命中 → 跳转菜谱详情（复用既有预览/确认做饭/扣库存流程）
                    └─ 未命中 → 明确弹窗"没有对应的家庭菜谱"，可跳转菜谱页
```

- 记录用餐不再调用任何做饭/库存接口。
- 只有用户在菜谱详情页主动点击「做饭」并确认，才会扣库存。
- 允许"只记录用餐"（吃剩菜、外食）与"只做饭不记录"两种合法状态。
- 菜名匹配仅用于**导航**，匹配失败会明确提示，不会静默扣库存。

---

## 3. 代码修改

### 3.1 `DiaryFragment`

**删除**（已确认无其他调用方）：

- `confirmPlannedDish`、`loadRecipeAndPreview`、`confirmPlannedCook`
- `showShortageDialog`、`offerRecordOnly`
- `sendCooking`、`retrySubmission` 的做饭分支、`promptNextUnconfirmed` 的做饭分支
- 库存食材名称缓存：`ingredientNames`、`ingredientNamesLoaded`、`ensureIngredientNames`、`loadIngredientNames`
- 不再使用的 `isoNow` 与相关 import

**新增**：

- `planRow` 增加第二个操作按钮：`做饭` + `记录用餐`（沿用既有 outlined button 样式，未改页面结构）。
- `openPlannedMeal(dish)`：取 `PlannedMeals.mealRequest(...)` 的默认值，打开既有食记编辑对话框（预填菜名与餐次，用户可改份量/热量/备注后保存）。
- `openPlannedCooking(dish)`：按名称精确匹配家庭菜谱；命中则 `host.navigate(RECIPE_DETAIL, "h:" + id)` 进入既有的做饭流程；未命中弹窗提示。
- `promptMissingRecipe(dish)`：标题/正文说明缺少家庭菜谱，提供「去菜谱」与「取消」。
- `editEntry(entry, prefill, presetName, presetMeal)` 重载：仅用于预填，不代表已做饭或已吃过。

**保留**：

- Batch 2B 的 `IdempotentAction` / `RecordSubmissionViewModel` / `PendingWriteStore` 食记幂等机制，全部保留。
- 食记编辑、删除、月历、日/月汇总、做饭历史、从烹饪记录预填（`cookingEventId` 关联）不变。

### 3.2 新增 `PlannedMeals`（纯函数，可单测）

- `mealRequest(dish, date)`：构造计划菜对应的食记请求，`cookingEventId = null`。
- `householdRecipe(items, name)`：仅接受去空格、忽略大小写的**完全同名**匹配；不猜测、不模糊匹配。
- `mealType(meal)`：无效餐次回退 `LUNCH`，不抛异常。

### 3.3 未改动

- `RecipeCatalogDetailFragment` 的做饭流程（唯一扣库存入口）。
- 服务端、OpenAPI、数据库、Gradle、主题与布局资源。

---

## 4. 测试（真实执行）

### 4.1 自动化

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat :feature-ac:testDebugUnitTest` | **53 tests, 0 failures**（新增 5 个） |
| `.\gradlew.bat testDebugUnitTest` | **151 tests, 0 failures**（Batch 2B 为 146） |
| `.\gradlew.bat :app:assembleDebug -PdevStackOff` | BUILD SUCCESSFUL |
| `.\gradlew.bat lintDebug -PdevStackOff` | BUILD SUCCESSFUL，0 errors |
| `.\gradlew.bat checkContracts` | `contracts consistent` / `No validation issues detected` |

新增 `PlannedMealsTest`：

1. 计划菜食记请求不携带 `cookingEventId`；
2. 非法餐次回退 `LUNCH`；
3. 家庭菜谱仅接受完全同名（含去空格）；
4. 不同名/空名/空列表一律返回 null（不猜测）；
5. 餐次解析与回退。

### 4.2 真机验证（MI PAD 4 PLUS / API 27，后端 8080）

安装当前构建后实测：

1. 进入食记 → 选择 2026-11-26：计划行显示 **「做饭」与「记录用餐」两个按钮**。
2. 点「记录用餐」：对话框预填 **餐次=早餐、菜名=拔丝土豆、份量=1**，顶部提示"只记录你吃了什么，不会扣减冰箱"。
   截图：[b2c-plan-record.png](../../../review/b2c-plan-record.png)
3. 点「做饭」（该菜没有对应家庭菜谱）：弹出 **"没有对应的家庭菜谱"**，说明需先创建/加入，提供「去菜谱」。
   截图：[b2c-plan-cook.png](../../../review/b2c-plan-cook.png)
4. 端到端：点「记录用餐」→ 保存，随后直接查询服务端：

```
BEFORE  date=2026-11-26 meals=2 cookingEvents=2 movements=2
AFTER   date=2026-11-26 meals=3 cookingEvents=2 movements=2     ← 只有食记 +1
CLEANED date=2026-11-26 meals=2 cookingEvents=2 movements=2     ← 测试数据已删除
```

  这直接验证：计划菜「记录用餐」只创建 MealEntry，不创建 CookingEvent、不产生库存流水。
5. `adb logcat` 无 `FATAL EXCEPTION`，页面保持 resumed。

### 4.3 未执行 / 需手工确认

| 场景 | 状态 | 说明 |
| --- | --- | --- |
| 「做饭」命中菜谱后跳转详情并完成扣库存 | **未实测** | 该计划菜无对应家庭菜谱；跳转代码简单，且详情页做饭流程在 Batch 2A 有服务端集成测试。手工步骤：在菜谱创建同名家庭菜谱 → 回食记点「做饭」→ 应进入详情并可预览/确认扣库存。 |
| 只做饭不记录用餐 | **未实测** | 服务端允许（`cooking_events` 与 `meal_entries` 独立）；手工步骤：详情页做饭后不记食记，确认计划行仍显示"记录用餐"。 |
| 连点「记录用餐」/旋转/超时 | **未实测** | 沿用了 Batch 2B 已验证的食记幂等状态机（单测覆盖）。 |
| 编辑/删除食记不影响库存 | 上轮 API 实测通过 | 本批未改服务端与该路径。 |

---

## 5. Git Diff 摘要

```
feature-ac/.../diary/DiaryFragment.java     | 303 ++++-----------------
feature-ac/src/main/res/values/ac_strings.xml      |   5 +
feature-ac/src/main/res/values-en/ac_strings.xml   |   5 +
feature-ac/.../diary/PlannedMeals.java      |  新增（纯函数）
feature-ac/src/test/.../PlannedMealsTest.java | 新增（5 个用例）
```

净删除约 180 行业务代码，新增约 60 行（含两个按钮与提示流程）。

---

## 6. 剩余风险

| 风险 | 等级 | 说明 |
| --- | --- | --- |
| 菜名不一致导致「做饭」无法直达 | 中低 | 精确同名匹配，改名/菜谱未创建时会提示；不会误扣库存。用户可去菜谱手动做饭。 |
| 计划行「做饭」跳转依赖 `"h:"` 前缀 | 低 | 该前缀同时被 `RecipeCard` 与 `AppNavigator` 使用，属既有约定；若未来统一改造需一并更新。 |
| 记录用餐的默认份量为 1 | 低 | 用户可在对话框修改；不做自动估算。 |
| 遗留未使用字符串（`ac_diary_plan_eaten` 等） | 极低 | 仅资源冗余，不影响功能；可在收尾时清理。 |
| 计划行两个按钮在极窄屏可能拥挤 | 低 | 按钮为 wrap_content + 4dp 间距；需在手机尺寸模拟器上确认（本批只在平板验证）。 |

---

## 7. 结论

- 计划菜「记录用餐」与「确认做饭」已完全分离。
- 记录用餐只写 MealEntry，不再触发作饭或库存扣减；重复扣减的风险路径已从代码中移除。
- 扣库存仍只在菜谱详情页的确认做饭流程中发生。
- Batch 2B 的幂等机制未被破坏。
- 可进入 Batch 3（家庭权限），但建议先在窄屏设备上补一次计划行按钮布局检查。
