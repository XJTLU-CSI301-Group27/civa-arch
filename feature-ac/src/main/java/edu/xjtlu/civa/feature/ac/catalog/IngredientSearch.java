package edu.xjtlu.civa.feature.ac.catalog;

import edu.xjtlu.civa.api.model.Ingredient;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure, testable dictionary search over canonical names and aliases. */
final class IngredientSearch {
    private IngredientSearch() { }

    static List<Ingredient> filter(List<Ingredient> dictionary, String query) {
        List<Ingredient> result = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (dictionary == null) return result;
        for (Ingredient ingredient : dictionary) {
            if (ingredient == null) continue;
            if (needle.isEmpty() || matches(ingredient, needle)) result.add(ingredient);
        }
        return result;
    }

    private static boolean matches(Ingredient ingredient, String needle) {
        if (contains(ingredient.getName(), needle)) return true;
        if (ingredient.getAliases() != null) {
            for (String alias : ingredient.getAliases()) if (contains(alias, needle)) return true;
        }
        return false;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
