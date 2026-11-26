package edu.xjtlu.civa.feature.ac.plan;

import edu.xjtlu.civa.api.model.PlanItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pure plan-item editing. Every method returns a new list and never mutates the slot loaded from the
 * server, so an optimistic append cannot accidentally drop existing dishes.
 */
public final class PlanItemEditor {
    private PlanItemEditor() { }

    /** Appends a dish, or increases its servings when it is already in the meal. */
    public static List<PlanItem> append(List<PlanItem> current, UUID recipeId) {
        List<PlanItem> result = copy(current);
        for (PlanItem item : result) {
            if (recipeId.equals(item.getRecipeId())) {
                item.setServings(shift(item.getServings(), 1));
                return result;
            }
        }
        result.add(new PlanItem().recipeId(recipeId).servings("1"));
        return result;
    }

    /** Owner-only serving change; a result at or below zero removes the dish. */
    public static List<PlanItem> adjust(List<PlanItem> current, UUID recipeId, int delta) {
        List<PlanItem> result = copy(current);
        for (int index = 0; index < result.size(); index++) {
            PlanItem item = result.get(index);
            if (!recipeId.equals(item.getRecipeId())) continue;
            BigDecimal next = new BigDecimal(item.getServings()).add(BigDecimal.valueOf(delta));
            if (next.signum() <= 0) result.remove(index);
            else item.setServings(next.stripTrailingZeros().toPlainString());
            return result;
        }
        return result;
    }

    public static List<PlanItem> remove(List<PlanItem> current, UUID recipeId) {
        List<PlanItem> result = copy(current);
        result.removeIf(item -> recipeId.equals(item.getRecipeId()));
        return result;
    }

    /** Owner-only replacement; refuses to create a duplicate recipe row. */
    public static List<PlanItem> replace(List<PlanItem> current, UUID from, UUID to) {
        List<PlanItem> result = copy(current);
        boolean targetExists = result.stream().anyMatch(item -> to.equals(item.getRecipeId()));
        if (targetExists) return copy(current);
        for (PlanItem item : result) {
            if (from.equals(item.getRecipeId())) {
                item.setRecipeId(to);
                return result;
            }
        }
        return result;
    }

    public static List<PlanItem> copy(List<PlanItem> current) {
        List<PlanItem> result = new ArrayList<>();
        if (current == null) return result;
        for (PlanItem item : current) {
            result.add(new PlanItem().recipeId(item.getRecipeId()).servings(item.getServings()));
        }
        return result;
    }

    private static String shift(String servings, int delta) {
        return new BigDecimal(servings).add(BigDecimal.valueOf(delta))
                .stripTrailingZeros().toPlainString();
    }
}
