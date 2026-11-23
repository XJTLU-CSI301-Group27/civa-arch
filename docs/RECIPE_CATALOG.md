# 公共菜谱目录（recipe_catalog）

生成脚本：`scripts/merge_recipe_catalogs.py`（可重复运行，id 由规范化菜名派生）。

- 合并结果：**1307** 道（A 1072 + BD 235；重名去重 77 条，其中采用 BD 图片 38 张）
- 分类分布：家常菜 900, 小吃 94, 增肌 56, 营养 53, 减脂 52, 保持 51, 食疗 51, 烘焙 50
- 食材保留自由文本（展示用），不强制映射标准食材；只有家庭菜谱（后续阶段）引用标准食材 ID。
- 图片：BD 的 149 张图以 `imageKey=recipes/images/*.jpg` 引用客户端资源；A 无图，客户端用 emoji。
- 未知值（热量/营养/时长）保持 `null`，不写 0。
- 表结构：`server/src/main/resources/db/migration/V20261123201000__AC_recipe_catalog.sql`
- 种子数据：`V20261123201100__AC_recipe_catalog_seed.sql`（2794 KB）
