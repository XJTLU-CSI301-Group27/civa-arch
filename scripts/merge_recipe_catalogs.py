"""Merge the A (1093) and BD (312) recipe catalogues into the server-side read-only catalogue.

The script is deterministic and re-runnable: ids are derived from the normalised name, duplicate
names keep the richer A entry (and adopt the BD image when A has none), and the generated SQL is
checked into the repository so every environment seeds the same data.

Inputs
  - A:  <a-repo>/feature-ac/src/main/assets/ac_recipes.json
  - BD: feature-bd/src/main/assets/recipes/catalog.json
Outputs
  - server/src/main/resources/db/migration/V20261123201000__AC_recipe_catalog.sql
  - server/src/main/resources/db/migration/V20261123201100__AC_recipe_catalog_seed.sql
  - docs/RECIPE_CATALOG.md (coverage report)
"""
import collections
import json
import pathlib
import re
import sys
import unicodedata
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent
A_JSON = pathlib.Path(r"D:\XJTLU\CSI301\civa-android-main(a)/feature-ac/src/main/assets/ac_recipes.json")
BD_JSON = ROOT / "feature-bd/src/main/assets/recipes/catalog.json"
DDL = ROOT / "server/src/main/resources/db/migration/V20261123201000__AC_recipe_catalog.sql"
SEED = ROOT / "server/src/main/resources/db/migration/V20261123201100__AC_recipe_catalog_seed.sql"
REPORT = ROOT / "docs/RECIPE_CATALOG.md"
NAMESPACE = uuid.UUID("6f0e3f2a-3a5c-4c1e-9c1a-0d3f0f8f7a10")

CUISINES = {"generic": "通用", "yue": "粤菜", "bei": "北方菜", "xiang": "湘菜", "chuan": "川菜",
            "hui": "徽菜", "dian": "滇菜", "suzhe": "苏浙菜", "xinjiang": "新疆菜", "min": "闽菜",
            "dongbei": "东北菜", "lu": "鲁菜", "qian": "黔菜", "jing": "京菜"}
CATEGORIES = {"home": "家常菜", "snack": "小吃", "fat-loss": "减脂", "nutrition": "营养",
              "muscle-gain": "增肌", "maintain": "保持", "therapy": "食疗", "bakery": "烘焙"}
MEALS = {"breakfast": "早餐", "lunch": "午餐", "dinner": "晚餐", "snack": "加餐", "brunch": "早午餐"}
FLAVOR_WORDS = ("辣", "麻", "酸", "甜", "咸", "鲜", "清淡")


def label(mapping, value):
    return mapping.get(value, value or "")


def normalise_name(name):
    text = unicodedata.normalize("NFKC", name or "").lower()
    return re.sub(r"[\s·・,，。.;；:：()（）\[\]【】\-—_/#'\"!?！？]+", "", text)


def derive_flavors(name, bd):
    if bd:
        result = []
        if "辣" in name or "麻" in name:
            result.append("辣")
        if not result:
            for word in FLAVOR_WORDS:
                if word in name:
                    result.append(word)
                    break
        return result
    return []


def clean_bd_ingredient(raw):
    text = re.sub(r"\s+", " ", raw or "").strip()
    text = re.sub(r"\s*份数\s*$", "", text).strip()
    return text


def strip_step_number(text):
    return re.sub(r"^\s*\d+\s*[.、)]\s*", "", text or "").strip()


def a_row(recipe):
    serving = recipe.get("servingSize") or {}
    serving_text = ""
    if serving.get("amount") is not None and serving.get("unit"):
        serving_text = f"{serving['amount']} {serving['unit']}"
    ingredients = []
    for item in recipe.get("ingredients") or []:
        ingredients.append({
            "name": item.get("name") or "",
            "quantity": str(item.get("quantity") or "") or None,
            "quantityPrefix": item.get("quantityPrefix") or None,
            "unit": item.get("unit") or None,
            "note": item.get("note") or None,
            "sourceText": None,
        })
    nutrition = recipe.get("nutrition") or {}
    per100 = recipe.get("per100g") or {}

    def num(value):
        return None if value is None else f"{value}"

    return {
        "id": str(uuid.uuid5(NAMESPACE, "A:" + recipe["id"])),
        "name": recipe["name"].strip(),
        "category": label(CATEGORIES, recipe.get("category")),
        "cuisine": label(CUISINES, recipe.get("cuisine")),
        "emoji": recipe.get("emoji") or "🍽️",
        "description": (recipe.get("desc") or "").strip(),
        "steps": [s for s in (recipe.get("steps") or []) if s],
        "ingredients": ingredients,
        "methods": recipe.get("methods") or [],
        "flavors": recipe.get("flavors") or [],
        "mealTypes": [label(MEALS, m) for m in (recipe.get("mealType") or [])],
        "estimatedMinutes": None,
        "servings": None,
        "servingSize": serving_text or None,
        "kcal": num(recipe.get("kcal")),
        "protein": num(nutrition.get("protein")),
        "carbs": num(nutrition.get("carbs")),
        "fat": num(nutrition.get("fat")),
        "proteinPer100g": num(per100.get("protein")),
        "carbsPer100g": num(per100.get("carbs")),
        "fatPer100g": num(per100.get("fat")),
        "imageKey": None,
        "source": "A",
        "sourceUrl": None,
    }


def bd_row(recipe):
    classification = recipe.get("classification") or {}
    image = recipe.get("image") or ""
    image_key = image[len("asset:"):] if image.startswith("asset:") else (image or None)
    return {
        "id": str(uuid.uuid5(NAMESPACE, "BD:" + recipe["id"])),
        "name": recipe["name"].strip(),
        "category": "家常菜",
        "cuisine": "通用",
        "emoji": "🍽️",
        "description": "",
        "steps": [strip_step_number(s) for s in (recipe.get("steps") or []) if s],
        "ingredients": [{"name": clean_bd_ingredient(raw), "quantity": None, "quantityPrefix": None,
                         "unit": None, "note": None, "sourceText": raw}
                        for raw in (recipe.get("ingredients") or []) if clean_bd_ingredient(raw)],
        "methods": [],
        "flavors": derive_flavors(recipe["name"], recipe),
        "mealTypes": ["午餐", "晚餐"],
        "estimatedMinutes": recipe.get("minutes"),
        "servings": None,
        "servingSize": None,
        "kcal": None, "protein": None, "carbs": None, "fat": None,
        "proteinPer100g": None, "carbsPer100g": None, "fatPer100g": None,
        "imageKey": image_key,
        "source": "BD",
        "sourceUrl": recipe.get("sourceUrl") or None,
    }


def search_text(row):
    parts = [row["name"], row["category"], row["cuisine"], row["description"], *row["steps"],
             *row["methods"], *row["flavors"], *row["mealTypes"]]
    parts += [item["name"] for item in row["ingredients"]]
    return " ".join(p for p in parts if p).lower()


def sql_text(value):
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def sql_array(values):
    if not values:
        return "'{}'::TEXT[]"
    return "ARRAY[" + ", ".join(sql_text(v) for v in values) + "]::TEXT[]"


def sql_jsonb(values):
    return sql_text(json.dumps(values, ensure_ascii=False)) + "::jsonb"


def main():
    a_recipes = json.loads(A_JSON.read_text(encoding="utf-8"))["recipes"]
    bd_recipes = json.loads(BD_JSON.read_text(encoding="utf-8"))

    merged = {}
    stats = collections.Counter()
    for recipe in a_recipes:
        key = normalise_name(recipe["name"])
        merged[key] = a_row(recipe)
        stats["a"] += 1
    duplicates = 0
    images_adopted = 0
    for recipe in bd_recipes:
        key = normalise_name(recipe["name"])
        row = bd_row(recipe)
        if key in merged:
            duplicates += 1
            existing = merged[key]
            if not existing["imageKey"] and row["imageKey"]:
                existing["imageKey"] = row["imageKey"]
                existing["sourceUrl"] = existing["sourceUrl"] or row["sourceUrl"]
                images_adopted += 1
            continue
        merged[key] = row
        stats["bd"] += 1

    rows = sorted(merged.values(), key=lambda r: (r["name"], r["id"]))
    print(f"merged rows: {len(rows)} (A {stats['a']}, BD {stats['bd']}, duplicates {duplicates}, "
          f"images adopted {images_adopted})")

    DDL.write_text("""-- Read-only public recipe catalogue merged from the A group's 1093 recipes and the BD group's
-- 312 cards. Generated by scripts/merge_recipe_catalogs.py; do not hand-edit.
CREATE TABLE recipe_catalog (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL CHECK (length(trim(name)) > 0),
    category VARCHAR(40) NOT NULL DEFAULT '',
    cuisine VARCHAR(40) NOT NULL DEFAULT '',
    emoji VARCHAR(16),
    description TEXT NOT NULL DEFAULT '',
    steps JSONB NOT NULL CHECK (jsonb_typeof(steps) = 'array'),
    ingredients JSONB NOT NULL CHECK (jsonb_typeof(ingredients) = 'array'),
    methods TEXT[] NOT NULL DEFAULT '{}',
    flavors TEXT[] NOT NULL DEFAULT '{}',
    meal_types TEXT[] NOT NULL DEFAULT '{}',
    estimated_minutes INTEGER CHECK (estimated_minutes IS NULL OR estimated_minutes > 0),
    servings NUMERIC(18,3) CHECK (servings IS NULL OR servings > 0),
    serving_size VARCHAR(40),
    kcal NUMERIC(18,3) CHECK (kcal IS NULL OR kcal >= 0),
    protein NUMERIC(18,3) CHECK (protein IS NULL OR protein >= 0),
    carbs NUMERIC(18,3) CHECK (carbs IS NULL OR carbs >= 0),
    fat NUMERIC(18,3) CHECK (fat IS NULL OR fat >= 0),
    protein_per100g NUMERIC(18,3) CHECK (protein_per100g IS NULL OR protein_per100g >= 0),
    carbs_per100g NUMERIC(18,3) CHECK (carbs_per100g IS NULL OR carbs_per100g >= 0),
    fat_per100g NUMERIC(18,3) CHECK (fat_per100g IS NULL OR fat_per100g >= 0),
    image_key VARCHAR(200),
    source VARCHAR(8) NOT NULL CHECK (source IN ('A', 'BD')),
    source_url VARCHAR(500),
    search_text TEXT NOT NULL DEFAULT ''
);

CREATE INDEX recipe_catalog_category_idx ON recipe_catalog(category);
CREATE INDEX recipe_catalog_name_idx ON recipe_catalog(name);
""", encoding="utf-8")

    header = ("INSERT INTO recipe_catalog (id, name, category, cuisine, emoji, description, steps, ingredients,"
              " methods, flavors, meal_types, estimated_minutes, servings, serving_size, kcal, protein, carbs, fat,"
              " protein_per100g, carbs_per100g, fat_per100g, image_key, source, source_url, search_text) VALUES\n")
    chunks = []
    for start in range(0, len(rows), 60):
        values = []
        for row in rows[start:start + 60]:
            values.append("(" + ", ".join([
                sql_text(row["id"]), sql_text(row["name"]), sql_text(row["category"]), sql_text(row["cuisine"]),
                sql_text(row["emoji"]), sql_text(row["description"]), sql_jsonb(row["steps"]),
                sql_jsonb(row["ingredients"]), sql_array(row["methods"]), sql_array(row["flavors"]),
                sql_array(row["mealTypes"]), "NULL" if row["estimatedMinutes"] is None else str(row["estimatedMinutes"]),
                sql_text(row["servings"]), sql_text(row["servingSize"]), sql_text(row["kcal"]),
                sql_text(row["protein"]), sql_text(row["carbs"]), sql_text(row["fat"]),
                sql_text(row["proteinPer100g"]), sql_text(row["carbsPer100g"]), sql_text(row["fatPer100g"]),
                sql_text(row["imageKey"]), sql_text(row["source"]), sql_text(row["sourceUrl"]),
                sql_text(search_text(row)),
            ]) + ")")
        chunks.append(header + ",\n".join(values) + ";\n")
    SEED.write_text("-- Generated by scripts/merge_recipe_catalogs.py; do not hand-edit.\n\n" + "\n".join(chunks),
                    encoding="utf-8")

    categories = collections.Counter(row["category"] for row in rows)
    sources = collections.Counter(row["source"] for row in rows)
    REPORT.write_text(f"""# 公共菜谱目录（recipe_catalog）

生成脚本：`scripts/merge_recipe_catalogs.py`（可重复运行，id 由规范化菜名派生）。

- 合并结果：**{len(rows)}** 道（A {sources['A']} + BD {sources['BD']}；重名去重 {duplicates} 条，其中采用 BD 图片 {images_adopted} 张）
- 分类分布：{", ".join(f"{k} {v}" for k, v in categories.most_common())}
- 食材保留自由文本（展示用），不强制映射标准食材；只有家庭菜谱（后续阶段）引用标准食材 ID。
- 图片：BD 的 149 张图以 `imageKey=recipes/images/*.jpg` 引用客户端资源；A 无图，客户端用 emoji。
- 未知值（热量/营养/时长）保持 `null`，不写 0。
- 表结构：`server/src/main/resources/db/migration/V20261123201000__AC_recipe_catalog.sql`
- 种子数据：`V20261123201100__AC_recipe_catalog_seed.sql`（{SEED.stat().st_size // 1024} KB）
""", encoding="utf-8")
    print("wrote DDL, seed", SEED.stat().st_size // 1024, "KB, report; categories:", dict(categories))


if __name__ == "__main__":
    sys.exit(main())
