package edu.xjtlu.civa.feature.ac.catalog;

import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.RecipeOnlyIngredient;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds one import request containing both TRACKED and RECIPE_ONLY rows, or null if invalid. */
final class ImportRequest {
    private ImportRequest() { }

    static CreateRecipeRequest build(String name, List<String> steps,
                                     List<RecipeIngredient> tracked,
                                     List<RecipeOnlyIngredient> recipeOnly,
                                     String yield, int duration) {
        if (name == null || name.trim().isEmpty() || steps == null || steps.isEmpty()) return null;
        if (name.trim().codePointCount(0, name.trim().length()) > 120) return null;
        if (duration < 1 || duration > 1440) return null;
        BigDecimal yieldValue = IngredientImportParser.number(yield);
        if (yieldValue == null || yieldValue.signum() <= 0) return null;
        List<RecipeIngredient> safeTracked = tracked == null ? List.of() : tracked;
        List<RecipeOnlyIngredient> safeOnly = recipeOnly == null ? List.of() : recipeOnly;
        if (safeTracked.isEmpty() && safeOnly.isEmpty()) return null;
        if (safeTracked.size() > 100 || safeOnly.size() > 100) return null;
        Set<UUID> ids = new HashSet<>();
        for (RecipeIngredient item : safeTracked) {
            if (item == null || item.getIngredientId() == null || item.getUnit() == null) return null;
            if (!ids.add(item.getIngredientId())) return null;
            BigDecimal quantity = IngredientImportParser.number(item.getQuantity());
            if (quantity == null || quantity.signum() <= 0) return null;
        }
        for (RecipeOnlyIngredient item : safeOnly) {
            if (item == null || item.getName() == null || item.getName().trim().isEmpty()
                    || item.getName().codePointCount(0, item.getName().length()) > 120) return null;
            if (item.getAmountText() != null
                    && item.getAmountText().codePointCount(0, item.getAmountText().length()) > 200) return null;
            if (item.getNote() != null
                    && item.getNote().codePointCount(0, item.getNote().length()) > 500) return null;
        }
        return new CreateRecipeRequest().name(name.trim()).steps(steps)
                .ingredients(safeTracked).recipeOnlyIngredients(safeOnly)
                .yieldServings(IngredientImportParser.text(yieldValue))
                .durationMinutes(duration)
                .tags(new LinkedHashSet<>(List.of("家庭菜谱")));
    }
}
