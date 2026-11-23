package edu.xjtlu.civa.feature.ac.catalog;

import edu.xjtlu.civa.api.model.CatalogRecipeIngredient;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.PersonalRecipeIngredient;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.Unit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Bridges free-text recipe rows and the canonical household recipe format. */
final class HouseholdRecipes {
    private HouseholdRecipes() { }

    static Map<UUID, String> names(List<Ingredient> dictionary) {
        Map<UUID, String> result = new LinkedHashMap<>();
        for (Ingredient item : dictionary) result.put(item.getId(), item.getName());
        return result;
    }

    static Map<String, Ingredient> byName(List<Ingredient> dictionary) {
        Map<String, Ingredient> result = new LinkedHashMap<>();
        for (Ingredient item : dictionary) result.put(item.getName().trim().toLowerCase(Locale.ROOT), item);
        return result;
    }

    static String display(Recipe recipe, Map<UUID, String> names) {
        List<String> parts = new ArrayList<>();
        for (RecipeIngredient item : recipe.getIngredients() == null ? List.<RecipeIngredient>of() : recipe.getIngredients()) {
            String name = names.getOrDefault(item.getIngredientId(), "食材");
            parts.add(name + " " + item.getQuantity() + " " + item.getUnit().getValue());
        }
        return String.join("，", parts);
    }

    /** Resolves "name quantity" lines against the dictionary; returns null when a name is unknown. */
    static CreateRecipeRequest request(String name, List<String> steps, List<String> freeLines,
                                       Map<String, Ingredient> dictionary) {
        List<RecipeIngredient> ingredients = new ArrayList<>();
        for (String line : freeLines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String lower = trimmed.toLowerCase(Locale.ROOT);
            Ingredient match = null;
            for (Map.Entry<String, Ingredient> entry : dictionary.entrySet()) {
                if (lower.equals(entry.getKey()) || lower.startsWith(entry.getKey() + " ") || lower.contains(entry.getKey())) {
                    match = entry.getValue();
                    break;
                }
            }
            if (match == null) return null;
            String quantity = "1";
            int space = trimmed.lastIndexOf(' ');
            if (space > 0) {
                String tail = trimmed.substring(space + 1).trim();
                if (tail.matches("^[0-9]+(\\.[0-9]{1,3})?$") && new java.math.BigDecimal(tail).signum() > 0) quantity = tail;
            }
            ingredients.add(new RecipeIngredient().ingredientId(match.getId()).quantity(quantity)
                    .unit(Unit.fromValue(match.getUnit().getValue())).trackStock(true));
        }
        if (ingredients.isEmpty()) return null;
        CreateRecipeRequest request = new CreateRecipeRequest().name(name).steps(steps).ingredients(ingredients)
                .yieldServings("2").durationMinutes(30);
        request.setTags(new java.util.LinkedHashSet<>(List.of("家庭菜谱")));
        return request;
    }

    /** Free-text names from a catalogue/personal recipe for the same conversion. */
    static List<String> catalogIngredientLines(List<CatalogRecipeIngredient> items) {
        List<String> lines = new ArrayList<>();
        for (CatalogRecipeIngredient item : items) {
            StringBuilder line = new StringBuilder(item.getName() == null ? "" : item.getName());
            if (item.getQuantity() != null && !item.getQuantity().isEmpty()) line.append(' ').append(item.getQuantity());
            lines.add(line.toString());
        }
        return lines;
    }

    static List<String> personalIngredientLines(List<PersonalRecipeIngredient> items) {
        List<String> lines = new ArrayList<>();
        for (PersonalRecipeIngredient item : items) {
            StringBuilder line = new StringBuilder(item.getName() == null ? "" : item.getName());
            if (item.getQuantity() != null && !item.getQuantity().isEmpty()) line.append(' ').append(item.getQuantity());
            lines.add(line.toString());
        }
        return lines;
    }
}
