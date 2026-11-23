package edu.xjtlu.civa.feature.bd;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.CatalogRecipeIngredient;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** App-private offline recipe cards, not another network or household data contract. */
final class BdRecipeCatalog {
    enum Taste {
        ANY, YES, NO;
        boolean accepts(boolean value) { return this == ANY || (this == YES) == value; }
        static Taste restore(String choice, Boolean legacy) {
            if (choice != null) {
                try { return valueOf(choice); }
                catch (IllegalArgumentException invalid) { return ANY; }
            }
            return legacy == null ? ANY : legacy ? YES : NO;
        }
    }

    static final class Recipe {
        String id, name, image, sourceUrl;
        List<String> ingredients = new ArrayList<>();
        List<String> steps = new ArrayList<>();
        boolean spicy, meat;
        Integer minutes;
    }

    final List<Recipe> recipes;

    BdRecipeCatalog(Reader reader) {
        this(parse(reader));
    }

    private static List<Recipe> parse(Reader reader) {
        List<Recipe> parsed = new Gson().fromJson(reader, new TypeToken<List<Recipe>>() { }.getType());
        return parsed;
    }

    BdRecipeCatalog(List<Recipe> parsed) {
        if (parsed == null || parsed.isEmpty()) throw new IllegalArgumentException("Empty recipe catalog");
        Set<String> ids = new HashSet<>();
        for (Recipe r : parsed) {
            if (r.id == null || !ids.add(r.id) || r.name == null || r.name.trim().isEmpty()
                    || r.ingredients == null || r.ingredients.isEmpty() || r.steps == null || r.steps.isEmpty()) {
                throw new IllegalArgumentException("Invalid recipe catalog");
            }
        }
        recipes = Collections.unmodifiableList(parsed);
    }

    List<Recipe> matching(Taste spicy, Taste meat, boolean quickOnly) {
        List<Recipe> result = new ArrayList<>();
        for (Recipe r : recipes) {
            if (spicy.accepts(r.spicy) && meat.accepts(r.meat) && (!quickOnly || r.minutes != null && r.minutes <= 30)) result.add(r);
        }
        return result;
    }

    List<Recipe> recommendationPool(Taste spicy, Taste meat, boolean quickOnly) {
        // Every matching recipe participates, including cards with the default kitten plate.
        return matching(spicy, meat, quickOnly);
    }

    Recipe byId(String id) {
        for (Recipe recipe : recipes) if (recipe.id.equals(id)) return recipe;
        return null;
    }

    static Recipe draw(List<Recipe> pool, Set<String> seen, String previous, Random random) {
        if (pool.isEmpty()) return null;
        List<Recipe> fresh = new ArrayList<>();
        for (Recipe r : pool) if (!seen.contains(r.id) && !r.id.equals(previous)) fresh.add(r);
        if (fresh.isEmpty()) {
            seen.clear();
            for (Recipe r : pool) if (!r.id.equals(previous)) fresh.add(r);
        }
        Recipe selected = fresh.isEmpty() ? pool.get(0) : fresh.get(random.nextInt(fresh.size()));
        seen.add(selected.id);
        return selected;
    }

    /** Maps the server-side public catalogue into the existing prompt-card shape. */
    static List<Recipe> fromCatalog(List<CatalogRecipe> rows) {
        List<Recipe> result = new ArrayList<>();
        for (CatalogRecipe row : rows) {
            Recipe recipe = new Recipe();
            recipe.id = row.getId() == null ? null : row.getId().toString();
            recipe.name = row.getName();
            recipe.image = row.getImageKey() == null ? null : "asset:" + row.getImageKey();
            for (CatalogRecipeIngredient item : row.getIngredients()) recipe.ingredients.add(display(item));
            recipe.steps.addAll(row.getSteps());
            recipe.spicy = contains(row.getFlavors(), "辣") || safe(row.getName()).contains("辣")
                    || safe(row.getName()).contains("麻");
            recipe.meat = looksMeat(row.getName(), recipe.ingredients);
            recipe.minutes = row.getEstimatedMinutes();
            if (recipe.id != null && recipe.name != null && !recipe.ingredients.isEmpty() && !recipe.steps.isEmpty()) {
                result.add(recipe);
            }
        }
        return result;
    }

    private static String display(CatalogRecipeIngredient item) {
        List<String> parts = new ArrayList<>();
        if (item.getName() != null) parts.add(item.getName());
        if (item.getQuantity() != null && !item.getQuantity().isEmpty()) parts.add(item.getQuantity());
        if (item.getUnit() != null && !item.getUnit().isEmpty()) parts.add(item.getUnit());
        return String.join(" ", parts);
    }

    private static boolean contains(List<String> values, String needle) {
        for (String value : values) if (value != null && value.contains(needle)) return true;
        return false;
    }

    private static boolean looksMeat(String name, List<String> ingredients) {
        String text = safe(name) + " " + String.join(" ", ingredients);
        for (String word : new String[]{"肉", "鸡", "牛", "猪", "羊", "鱼", "虾", "蟹", "贝", "排骨", "火腿", "培根"}) {
            if (text.contains(word)) return true;
        }
        return false;
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
