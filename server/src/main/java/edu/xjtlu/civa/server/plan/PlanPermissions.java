package edu.xjtlu.civa.server.plan;

import edu.xjtlu.civa.server.api.model.PlanItem;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side plan diff. A member may only add recipes or increase the total servings of a recipe;
 * any removal or reduction requires the current household owner. Recipes are matched by id (the
 * persisted primary key is slot+recipe), so names cannot be used to bypass the rule, and totals are
 * summed in case a future request ever contains the same recipe twice.
 */
final class PlanPermissions {
    private PlanPermissions() { }

    static boolean needsOwner(List<PlanItem> before, List<PlanItem> after) {
        Map<UUID, BigDecimal> previous = totals(before);
        Map<UUID, BigDecimal> next = totals(after);
        for (Map.Entry<UUID, BigDecimal> entry : previous.entrySet()) {
            BigDecimal remaining = next.get(entry.getKey());
            if (remaining == null || remaining.compareTo(entry.getValue()) < 0) return true;
        }
        return false;
    }

    private static Map<UUID, BigDecimal> totals(List<PlanItem> items) {
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        if (items == null) return result;
        for (PlanItem item : items) {
            result.merge(item.getRecipeId(), new BigDecimal(item.getServings()), BigDecimal::add);
        }
        return result;
    }
}
