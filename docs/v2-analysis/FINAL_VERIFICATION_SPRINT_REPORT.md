# Civa V2 Final Verification Sprint 报告

日期：2026-11-27（Asia/Shanghai）
基线：`15a59610e8cc3b94b3758e2aa82c3c0d8bdbfc97`
范围：Final Verification Sprint；未进入 Phase C / 自动 Final QA-2。

## Gate A — Pending 安全

### A1 手工 UNKNOWN → 尝试导入 — PASS

1. DemoHome 新建 `RG5Manual`（RECIPE_ONLY 盐/少许）。
2. 停止后端，保存进入 UNKNOWN：
   - key `5641aeea-9375-45e5-bfbd-cdbb3fc04566`
   - userId `db23f805...`
   - householdId `d8d967df...`
   - source=MANUAL
   - pending 数量=1
3. 打开个人菜谱 `RG5Source` → 加入家庭。
4. 导入被阻止，提示“已有待确认创建请求…不要直接再次导入”。
5. 未发起新导入；pending 仍为同一 key/payload。
6. 恢复后端，在新建家庭编辑器恢复原 pending：
   - 原 key 不变
   - 手动重试成功
   - DB 仅创建 `RG5Manual` `e2996e90-7bce-416d-8bce-d7cd56707e5d`
7. pending=0。

PASS。

### A2 导入 UNKNOWN → 尝试手工创建 — PASS

1. 从个人菜谱 `RG5Source` 导入，停止后端，点击导入进入 UNKNOWN：
   - key `0fee0316-fe09-4d2d-af66-f2443852aaf1`
   - source=IMPORT
   - payload RG5Source，recipeOnly 1
   - pending=1
2. 打开“新建家庭菜谱”：
   - 编辑器恢复同一 IMPORT pending；
   - 显示“上次创建结果不确定”；
   - 保存禁用；
   - 未生成新 key，未覆盖原 payload。
3. 恢复后端，点击“重试原请求”：
   - 原 key 不变；
   - DB 仅创建 `RG5Source` `53333ddc-6b73-41b2-9b38-cf49b4a6a413`；
   - pending=0。

PASS。

### A3 损坏 Pending UI（fingerprint 不匹配）— PASS

使用隔离本地 Room DB 注入一条 fingerprint=`deadbeef` 的记录（未破坏真实用户 pending）。

安装最新 APK 后，打开新建家庭编辑器：

- UI 显示**“待确认创建记录损坏，暂时不能创建；请核对本地数据后再试。”**
- 保存按钮 `enabled=false`
- 无“重试原请求”按钮
- 未发送任何创建请求
- DB 中 pending 仍为原 key `584c091b-...`
- DB 未创建 RG5Corrupt
- 未生成新的 key
- 原损坏记录未被删除或覆盖

PASS（损坏记录由本地隔离测试 DB 注入；非正式用户记录）。

### Gate A 结论

- A1 PASS
- A2 PASS
- A3 PASS
- 未发现新的 Pending 数据安全缺陷。

## Gate B — Android UI / 兼容

### B1 三条核心 UI

**做饭 UI：NOT RUN。** 本轮做饭通过 HTTP/DB 验证：
- 5→4、Movement、CookingEvent、重复确认、不足库存、无部分扣减。
- 未在 Android UI 执行预览/确认。

**MealEntry UI：NOT RUN。** 创建/修改/删除已通过 HTTP/DB，库存不变。

**计划 UI：NOT RUN。** 空冰箱计划此前通过 API；本轮权限通过 HTTP/DB；未用计划 UI 添加缺料菜谱。

### B2 设备与布局

- 360dp 手机：**PASS（关键导入页面）**
  - 安装最新 APK；
  - “选择食材”完整显示，不再截断；
  - 类型选择、数量、取消/导入按钮可见；
  - screenshot `review/rg5-360.png`。
- C→TRACKED 名称替换提醒：**PASS（真机）**
  - 来源“食盐 适量 调味”，显式选 beef；
  - 行内显示：
    “原始‘食盐’将替换为规范食材‘beef’，请确认这是你要的选择。”
  - screenshot `review/rg5-360-warning.png`；
  - 未自动替换、未禁止选择、按钮仍可触达。
- 平板横屏：NOT RUN。
- 英文界面：NOT RUN。
- 字体放大：NOT RUN。
- 深色模式：NOT RUN。
- 长食材列表滚动：NOT RUN。
- 平板竖屏：前轮 PASS。

### B3 历史 trackStock=false

当前开发库无样本；**NOT RUN**。未在隔离环境构造旧模型样本，不能宣称 PASS。

### Gate B 结论

- 360dp 与名称替换提醒：PASS
- 做饭/MealEntry/计划 UI：NOT RUN
- 横屏/英文/字体/深色/长列表/历史 false：NOT RUN

## Gate C — 自动化与部署约束

| 命令 | 结果 |
| --- | --- |
| `.\gradlew.bat testDebugUnitTest -PdevStackOff` | **236 tests / 0 failures** |
| `server: .\gradlew.bat test --rerun-tasks` | **105 tests / 0 failures** |
| assembleDebug | BUILD SUCCESSFUL |
| lintDebug | BUILD SUCCESSFUL |
| checkContracts | BUILD SUCCESSFUL |
| `git diff --check` | 无空白错误 |

### 部署约束（已确认）

- 服务端必须使用 `civa.media-dir` / `CIVA_MEDIA_DIR`。
- `--civa.media.root` 不被应用配置读取，会导致：
  - 业务媒体目录与邀请密钥目录不一致；
  - 邀请解密 `AEADBadTagException` → HTTP 500。
- 当前邀请密钥文件必须与创建 invitation 时一致；**不得重新生成或覆盖密钥绕过解密失败**。
- 本轮用 `--civa.media-dir=server/build/media` 重启后邀请 201、成员加入 201、权限 PASS。
- 未修改业务代码/DB/OpenAPI；属于启动配置问题。

## 实际发现的问题

### P0

- 无新增已确认 P0 代码缺陷。
- 既有 fingerprint 完整性 P0 已在 `c935910` 修复。

### P1

- 360dp 文案截断：`3483913` 修复并在本轮真机确认 PASS。
- C→TRACKED 名称替换提醒：`7f6c1f0` 代码 + 本轮 360dp 真机确认 PASS。
- 邀请 500：真实根因是启动配置 `media.root` vs `media-dir`；正确配置后 PASS，无代码修复。
- 做饭/MealEntry/计划 UI、横屏/英文/字体/深色/长列表、历史 false：**NOT RUN**，未确认缺陷。

### P2

- 360dp 页面仍较紧凑，但关键操作可用。

## 测试数据清理

- DemoHome：0 菜谱、0 inventory batches、0 movements、0 cooking events、1 条原有 plan slot。
- OurHome：6 菜谱、12 批次、8 movements，未变。
- 总 recipes 8 / user_recipes 17 / inventory_batches 12 / inventory_movements 11 / cooking_events 5 / meal_entries 7 / plan_slots 5。
- 删除 RG5 测试 recipes/sources/snapshots、测试 cooking/meal、测试计划 slot、QA 成员/账号。
- 本地 `pending_writes=0`。
- 家庭成员关系未变。
- Pinyin IME 恢复，ADBKeyboard 卸载；屏幕 1200×1920/320dpi。

## 最终裁决

### 是否可通过 Final QA-1？

**CONDITIONAL GO。**

理由：
- Pending 安全 A1/A2/A3 真机/隔离 DB PASS；
- Owner/Member 邀请与权限 PASS；
- 做饭/库存/MealEntry HTTP/DB PASS；
- 六类 codec PASS；
- 360dp 与名称替换提醒 PASS；
- 无新增确认 P0/P1 代码缺陷。

### 仍存在的风险

- 做饭、MealEntry、计划 UI 未真机抽样；
- 平板横屏、英文、字体放大、深色、长列表未验证；
- 历史 trackStock=false 无样本；
- 这些不是已确认缺陷，但属于最终演示前的 UI 风险。

### 建议

- 允许进入 **Final QA-2 的条件式收尾**，仅做上述 UI 冒烟与历史样本，不新增业务功能。
- 若课程演示前无法完成这些 UI 冒烟，则按 CONDITIONAL GO 提交，并在交付说明中列明未验证项。
- 不建议启动 Phase C1/C2。

停止于 Final Verification Sprint，不自动进入 Phase C 或 Final QA-2。
