package edu.xjtlu.civa.feature.bd;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Personal on-device planning drafts. These never represent a meal eaten or inventory used. */
final class BdDraft {
    static final class Dish {
        String id, recipeId, name, image, sourceUrl;
        BdNutrition nutrition;
        List<String> ingredients = new ArrayList<>();
        List<String> steps = new ArrayList<>();

        static Dish custom(String operationId, String name, String image) {
            if (name == null || name.trim().isEmpty() || name.codePointCount(0, name.length()) > 60) throw new IllegalArgumentException("Invalid dish name");
            Dish dish = new Dish();
            dish.id = operationId;
            dish.name = name.trim();
            dish.image = image;
            return dish;
        }

        static Dish recipe(String operationId, BdRecipeCatalog.Recipe recipe) {
            Dish dish = custom(operationId, recipe.name, recipe.image);
            dish.recipeId = recipe.id;
            dish.sourceUrl = recipe.sourceUrl;
            dish.ingredients = new ArrayList<>(recipe.ingredients);
            dish.steps = new ArrayList<>(recipe.steps);
            return dish;
        }
    }

    int version;
    String scope;
    Map<String, List<Dish>> meals;
    java.util.Set<String> migratedScopes = new java.util.LinkedHashSet<>();

    static BdDraft empty(String scope) {
        BdDraft data = new BdDraft();
        data.version = 1;
        data.scope = scope;
        data.meals = new LinkedHashMap<>();
        return data;
    }

    static String key(LocalDate date, String meal) {
        if (!meal.equals("BREAKFAST") && !meal.equals("LUNCH") && !meal.equals("DINNER")) throw new IllegalArgumentException("Invalid meal");
        return date.toString() + "/" + meal;
    }

    List<Dish> dishes(LocalDate date, String meal) {
        List<Dish> list = meals.get(key(date, meal));
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    BdDraft withDish(LocalDate date, String meal, Dish dish) {
        BdDraft copy = decode(new Gson().toJson(this), scope);
        for (List<Dish> list : copy.meals.values()) {
            for (Dish existing : list) if (existing.id.equals(dish.id)) return copy;
        }
        String key = key(date, meal);
        if (!copy.meals.containsKey(key)) copy.meals.put(key, new ArrayList<>());
        copy.meals.get(key).add(new Gson().fromJson(new Gson().toJson(dish), Dish.class));
        return copy;
    }

    Dish find(java.time.LocalDate date, String meal, String id) {
        for (Dish dish : dishes(date, meal)) if (dish.id.equals(id)) return dish;
        return null;
    }

    BdDraft withoutDish(java.time.LocalDate date, String meal, String id) {
        BdDraft copy = decode(new Gson().toJson(this), scope);
        String key = key(date, meal);
        List<Dish> rows = copy.meals.get(key);
        if (rows != null) { rows.removeIf(dish -> dish.id.equals(id)); if (rows.isEmpty()) copy.meals.remove(key); }
        return copy;
    }

    BdDraft withRecipe(java.time.LocalDate date, String meal, String id, String ingredients, String steps) {
        if (ingredients == null || steps == null || ingredients.trim().isEmpty() || steps.trim().isEmpty()
                || ingredients.length() > 8000 || steps.length() > 8000) throw new IllegalArgumentException("Invalid recipe text");
        BdDraft copy = decode(new Gson().toJson(this), scope);
        Dish dish = copy.find(date, meal, id);
        if (dish == null) throw new IllegalArgumentException("Missing dish");
        dish.ingredients = new ArrayList<>(java.util.Arrays.asList(ingredients.trim().split("\\R", -1)));
        dish.steps = new ArrayList<>(java.util.Arrays.asList(steps.trim().split("\\R", -1)));
        dish.sourceUrl = null; dish.nutrition = null;
        return copy;
    }

    BdDraft withNutrition(java.time.LocalDate date, String meal, String id, BdNutrition nutrition) {
        BdDraft copy = decode(new Gson().toJson(this), scope);
        Dish dish = copy.find(date, meal, id);
        if (dish == null || nutrition == null || !nutrition.validFor(dish)) throw new IllegalArgumentException("Stale nutrition estimate");
        dish.nutrition = new Gson().fromJson(new Gson().toJson(nutrition), BdNutrition.class);
        return copy;
    }

    boolean usesImage(String image) {
        for (List<Dish> rows : meals.values()) for (Dish dish : rows) if (java.util.Objects.equals(dish.image, image)) return true;
        return false;
    }

    static BdDraft decode(String json, String expectedScope) {
        BdDraft data = new Gson().fromJson(json, BdDraft.class);
        if (data == null || data.version != 1 || !expectedScope.equals(data.scope) || data.meals == null) throw new IllegalArgumentException("Invalid local draft");
        if (data.migratedScopes == null) data.migratedScopes = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, List<Dish>> entry : data.meals.entrySet()) {
            String[] parts = entry.getKey().split("/", -1);
            if (parts.length != 2 || !key(LocalDate.parse(parts[0]), parts[1]).equals(entry.getKey()) || entry.getValue() == null) throw new IllegalArgumentException("Invalid meal key");
            for (Dish dish : entry.getValue()) {
                if (dish == null || dish.id == null || dish.name == null || dish.name.trim().isEmpty() || dish.ingredients == null || dish.steps == null) throw new IllegalArgumentException("Invalid saved dish");
                if (dish.image != null && !dish.image.startsWith("asset:recipes/images/")
                        && !dish.image.startsWith("local:home-dish-photos/" + expectedScope + "/")) throw new IllegalArgumentException("Image outside draft owner");
            }
        }
        return data;
    }

    /** Copy an explicitly owned old scope once; current entries win and removed entries stay removed. */
    BdDraft importLegacy(BdDraft legacy, java.util.function.UnaryOperator<String> copyImage) {
        if (scope.equals(legacy.scope) || migratedScopes.contains(legacy.scope)) return this;
        BdDraft copy = decode(new Gson().toJson(this), scope);
        java.util.Set<String> existing = new java.util.HashSet<>();
        for (List<Dish> rows : copy.meals.values()) for (Dish dish : rows) existing.add(dish.id);
        for (Map.Entry<String, List<Dish>> entry : legacy.meals.entrySet()) {
            for (Dish dish : entry.getValue()) {
                if (!existing.add(dish.id)) continue;
                Dish imported = new Gson().fromJson(new Gson().toJson(dish), Dish.class);
                if (imported.image != null && imported.image.startsWith("local:")) imported.image = copyImage.apply(imported.image);
                copy.meals.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(imported);
            }
        }
        copy.migratedScopes.add(legacy.scope);
        return decode(new Gson().toJson(copy), scope);
    }

    static String scopeHash(String owner) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
