package edu.xjtlu.civa.feature.ac.catalog;

import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.MatchLine;
import edu.xjtlu.civa.api.model.MatchStatus;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeMatch;
import edu.xjtlu.civa.api.model.Unit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the server's per-line match result into one safe recipe-level view.
 *
 * <p>The server returns one line per tracked recipe-ingredient row. For repeated rows of the same
 * ingredient + unit, {@code available} is the same shared stock balance, not a per-row amount, so
 * required amounts are summed first and the shared balance is subtracted once. Anything that cannot
 * be compared safely (unknown ingredient, unit mismatch, null values, inconsistent duplicate rows)
 * becomes UNKNOWN rather than a fabricated shortage.
 */
final class RecipeStockPresenter {
    enum State { ENOUGH, MISSING, UNKNOWN }

    record Missing(UUID ingredientId, String name, String quantity, Unit unit) { }

    record StockView(State state, String servings, boolean tracked, List<Missing> missing) {
        boolean noStockRequired() { return state == State.ENOUGH && !tracked; }

        static StockView unknown(String servings) {
            return new StockView(State.UNKNOWN, servings, false, List.of());
        }
    }

    private RecipeStockPresenter() { }

    static StockView present(Recipe recipe, RecipeMatch match, Map<UUID, Ingredient> dictionary,
                             String servings) {
        if (recipe == null || recipe.getId() == null || match == null || dictionary == null) {
            return StockView.unknown(servings);
        }
        if (match.getRecipeId() != null && !recipe.getId().equals(match.getRecipeId())) {
            return StockView.unknown(servings);
        }
        List<MatchLine> lines = match.getLines();
        if (lines == null) return StockView.unknown(servings);
        if (lines.isEmpty()) {
            return new StockView(State.ENOUGH, servings, false, List.of());
        }

        Map<UUID, Unit> unitsByIngredient = new LinkedHashMap<>();
        Map<Key, Group> groups = new LinkedHashMap<>();
        for (MatchLine line : lines) {
            if (line == null || line.getIngredientId() == null || line.getUnit() == null) {
                return StockView.unknown(servings);
            }
            if (line.getStatus() == null || line.getStatus() == MatchStatus.UNKNOWN) {
                return StockView.unknown(servings);
            }
            Ingredient ingredient = dictionary.get(line.getIngredientId());
            if (ingredient == null || ingredient.getUnit() == null
                    || ingredient.getName() == null || ingredient.getName().isBlank()) {
                return StockView.unknown(servings);
            }
            if (ingredient.getUnit() != line.getUnit()) return StockView.unknown(servings);
            Unit previousUnit = unitsByIngredient.putIfAbsent(line.getIngredientId(), line.getUnit());
            if (previousUnit != null && previousUnit != line.getUnit()) return StockView.unknown(servings);
            if (line.getRequired() == null || line.getAvailable() == null) {
                return StockView.unknown(servings);
            }
            BigDecimal required;
            BigDecimal available;
            try {
                required = new BigDecimal(line.getRequired());
                available = new BigDecimal(line.getAvailable());
            } catch (NumberFormatException invalid) {
                return StockView.unknown(servings);
            }
            if (required.signum() < 0 || available.signum() < 0) return StockView.unknown(servings);

            Key key = new Key(line.getIngredientId(), line.getUnit());
            Group group = groups.get(key);
            if (group == null) {
                groups.put(key, new Group(line.getIngredientId(), ingredient.getName(), required, available, line.getUnit()));
            } else {
                if (group.available.compareTo(available) != 0) {
                    // Two rows claim different balances for the same shared stock: not safe to show.
                    return StockView.unknown(servings);
                }
                group.required = group.required.add(required);
            }
        }

        List<Missing> missing = new ArrayList<>();
        for (Group group : groups.values()) {
            BigDecimal shortage = group.required.subtract(group.available);
            if (shortage.signum() > 0) {
                missing.add(new Missing(group.id, group.name,
                        shortage.stripTrailingZeros().toPlainString(), group.unit));
            }
        }
        if (!missing.isEmpty()) return new StockView(State.MISSING, servings, true, List.copyOf(missing));
        return new StockView(State.ENOUGH, servings, true, List.of());
    }

    /** Valid positive yield or 1 serving; the returned value is what the status is based on. */
    static String servingsFor(Recipe recipe) {
        if (recipe == null || recipe.getYieldServings() == null) return "1";
        try {
            BigDecimal value = new BigDecimal(recipe.getYieldServings());
            if (value.signum() > 0) return value.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return "1";
    }

    /** Stable page ordering: enough first, then missing, then unknown. */
    static int rank(StockView view) {
        if (view == null) return 2;
        return switch (view.state()) {
            case ENOUGH -> 0;
            case MISSING -> 1;
            case UNKNOWN -> 2;
        };
    }

    private record Key(UUID ingredientId, Unit unit) { }

    private static final class Group {
        final UUID id;
        final String name;
        final Unit unit;
        BigDecimal required;
        final BigDecimal available;

        Group(UUID id, String name, BigDecimal required, BigDecimal available, Unit unit) {
            this.id = id;
            this.name = name;
            this.required = required;
            this.available = available;
            this.unit = unit;
        }
    }
}
