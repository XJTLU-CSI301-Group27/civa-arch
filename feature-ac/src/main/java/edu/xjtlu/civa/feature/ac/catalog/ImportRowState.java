package edu.xjtlu.civa.feature.ac.catalog;

import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.RecipeOnlyIngredient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Pure row decision for import: A/B default to TRACKED, C starts with no decision, and either type
 * can be chosen explicitly. Original text is kept separately from the canonical tracked values.
 */
final class ImportRowState {
    enum Mode { NONE, TRACKED, RECIPE_ONLY }

    enum Error {
        NONE, CHOOSE, INGREDIENT, QUANTITY, UNIT, DUPLICATE, NAME, AMOUNT, NOTE
    }

    private final IngredientImportParser.Line line;
    private Mode mode;
    private UUID trackedId;
    private String quantity;
    private String recipeName;
    private String amountText;
    private String note;

    ImportRowState(IngredientImportParser.Line line) {
        this.line = line;
        IngredientImportParser.Input source = line.source();
        if (line.ready()) {
            mode = Mode.TRACKED;
            trackedId = line.ingredientId();
            quantity = line.quantity();
        } else {
            mode = Mode.NONE;
        }
        recipeName = nameText(line);
        amountText = originalAmount(source);
        note = blankToNull(source.note());
    }

    Mode mode() { return mode; }
    void mode(Mode value) { mode = value == null ? Mode.NONE : value; }
    UUID trackedId() { return trackedId; }
    void trackedId(UUID value) { trackedId = value; }
    String quantity() { return quantity; }
    void quantity(String value) { quantity = value; }
    String recipeName() { return recipeName; }
    void recipeName(String value) { recipeName = value; }
    String amountText() { return amountText; }
    void amountText(String value) { amountText = value; }
    String note() { return note; }
    void note(String value) { note = value; }
    IngredientImportParser.Line line() { return line; }

    boolean isTracked() { return mode == Mode.TRACKED; }
    boolean isRecipeOnly() { return mode == Mode.RECIPE_ONLY; }
    boolean hasDecision() { return mode != Mode.NONE; }

    Error validate(Set<UUID> seen, List<Ingredient> dictionary) {
        if (mode == Mode.NONE) return Error.CHOOSE;
        if (mode == Mode.TRACKED) {
            Ingredient ingredient = find(dictionary, trackedId);
            if (ingredient == null || ingredient.getUnit() == null) return Error.INGREDIENT;
            BigDecimal value = IngredientImportParser.number(quantity);
            if (value == null || value.signum() <= 0) return Error.QUANTITY;
            if (seen != null && !seen.add(ingredient.getId())) return Error.DUPLICATE;
            return Error.NONE;
        }
        if (blank(recipeName)) return Error.NAME;
        if (codePoints(recipeName) > 120) return Error.NAME;
        if (amountText != null && codePoints(amountText) > 200) return Error.AMOUNT;
        if (note != null && codePoints(note) > 500) return Error.NOTE;
        return Error.NONE;
    }

    RecipeIngredient toTracked(List<Ingredient> dictionary) {
        Ingredient ingredient = find(dictionary, trackedId);
        if (ingredient == null) return null;
        BigDecimal value = IngredientImportParser.number(quantity);
        if (value == null || value.signum() <= 0) return null;
        return new RecipeIngredient().ingredientId(ingredient.getId())
                .quantity(IngredientImportParser.text(value))
                .unit(ingredient.getUnit()).trackStock(true);
    }

    RecipeOnlyIngredient toRecipeOnly() {
        if (blank(recipeName)) return null;
        return new RecipeOnlyIngredient().name(recipeName.trim())
                .amountText(blankToNull(amountText)).note(blankToNull(note));
    }

    /** Structured quantity fields take precedence; sourceText is not dumped into amountText. */
    static String originalAmount(IngredientImportParser.Input source) {
        if (source == null) return null;
        List<String> parts = new ArrayList<>();
        add(parts, source.quantityPrefix());
        add(parts, source.quantity());
        add(parts, source.unit());
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    static String nameText(IngredientImportParser.Line line) {
        String name = line.source() == null ? "" : trim(line.source().name());
        if (!name.isEmpty()) return name;
        return line.ingredientName() == null ? "" : line.ingredientName().trim();
    }

    private static Ingredient find(List<Ingredient> dictionary, UUID id) {
        if (id == null) return null;
        for (Ingredient ingredient : dictionary == null ? List.<Ingredient>of() : dictionary) {
            if (id.equals(ingredient.getId()) && ingredient.getUnit() != null) return ingredient;
        }
        return null;
    }

    private static void add(List<String> parts, String value) {
        if (value != null && !value.trim().isEmpty()) parts.add(value.trim());
    }

    private static int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
    private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }
}
