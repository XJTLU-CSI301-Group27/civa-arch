package edu.xjtlu.civa.feature.bd;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
        List<Recipe> parsed = new Gson().fromJson(reader, new TypeToken<List<Recipe>>() { }.getType());
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
}
