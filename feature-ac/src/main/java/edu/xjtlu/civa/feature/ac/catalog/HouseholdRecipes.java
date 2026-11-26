package edu.xjtlu.civa.feature.ac.catalog;

import android.content.res.Resources;
import edu.xjtlu.civa.api.model.CatalogRecipeIngredient;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.PersonalRecipeIngredient;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.Unit;
import edu.xjtlu.civa.feature.ac.AcText;
import edu.xjtlu.civa.feature.ac.R;
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

    /** Localized ingredient summary for the detail screen. */
    static String display(Resources resources, Recipe recipe, Map<UUID, String> names) {
        List<String> parts = new ArrayList<>();
        for (RecipeIngredient item : recipe.getIngredients() == null ? List.<RecipeIngredient>of() : recipe.getIngredients()) {
            String name = names.getOrDefault(item.getIngredientId(),
                    resources.getString(R.string.ac_l10n_ingredient_fallback));
            parts.add(name + " " + item.getQuantity() + " "
                    + AcText.unit(resources, item.getUnit(), name));
        }
        return AcText.join(resources, parts);
    }

    /** Legacy text-only summary kept for JVM unit tests; the detail screen uses display(Resources, ...). */
    static String display(Recipe recipe, Map<UUID, String> names) {
        List<String> parts = new ArrayList<>();
        for (RecipeIngredient item : recipe.getIngredients() == null ? List.<RecipeIngredient>of() : recipe.getIngredients()) {
            String name = names.getOrDefault(item.getIngredientId(), "ingredient");
            parts.add(name + " " + item.getQuantity() + " " + item.getUnit().getValue());
        }
        return String.join(", ", parts);
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
        // i18n: data tag sent to the server; it is an identifier, not display text.
        request.setTags(new java.util.LinkedHashSet<>(List.of("家庭菜谱")));
        return request;
    }

    /** Unit guessed from free text so a missing dictionary entry can be registered instead of blocking. */
    static Unit inferUnit(String line) {
        String text = line == null ? "" : line.toLowerCase(Locale.ROOT);
        // i18n: matching keyword - free-text unit words only infer the canonical unit.
        if (text.contains("克") || text.contains("g")) return Unit.G;
        if (text.contains("毫升") || text.contains("ml") || text.contains("勺") || text.contains("杯")) return Unit.ML;
        if (text.contains("个") || text.contains("只") || text.contains("颗") || text.contains("片")
                || text.contains("枚") || text.contains("count")) return Unit.COUNT;
        return Unit.G;
    }

    /** Ingredient names that the dictionary cannot resolve yet, in "name" form for registration. */
    static List<String> unresolvedNames(java.util.List<String> lines, Map<String, Ingredient> dictionary) {
        List<String> result = new java.util.ArrayList<>();
        for (String line : lines) {
            String lower = line == null ? "" : line.trim().toLowerCase(Locale.ROOT);
            if (lower.isEmpty()) continue;
            boolean found = false;
            for (String name : dictionary.keySet()) {
                if (lower.equals(name) || lower.startsWith(name + " ") || lower.contains(name)) {
                    found = true;
                    break;
                }
            }
            if (!found) result.add(lower);
        }
        return result;
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
