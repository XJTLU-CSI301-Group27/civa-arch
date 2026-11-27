package edu.xjtlu.civa.feature.ac.catalog;

import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.RecipeOnlyIngredient;
import edu.xjtlu.civa.api.model.Unit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure, recycler-independent editor state. Stable row ids own all business data; adapters only
 * render and forward edits. Existing recipe-only rows are not sent back unless the user changed
 * them, so old clients cannot accidentally clear descriptive ingredients.
 */
final class IngredientEditorState {
    enum Kind { TRACKED, RECIPE_ONLY, LEGACY_UNTRACKED }

    enum Field { EMPTY, LIMIT, INGREDIENT, QUANTITY, UNIT, DUPLICATE, RECIPE_NAME, RECIPE_AMOUNT, RECIPE_NOTE }

    record RowError(long rowId, Field field) { }

    record Validation(List<RowError> errors) {
        boolean valid() { return errors.isEmpty(); }

        RowError first() { return errors.isEmpty() ? null : errors.get(0); }
    }

    static final class Row {
        final long id;
        Kind kind;
        UUID ingredientId;
        String trackedName = "";
        String quantity = "";
        Unit unit;
        String recipeName = "";
        String amountText = "";
        String note = "";

        Row(long id, Kind kind) {
            this.id = id;
            this.kind = kind;
        }

        boolean trackedRow() { return kind != Kind.RECIPE_ONLY; }

        boolean legacy() { return kind == Kind.LEGACY_UNTRACKED; }
    }

    private final LinkedHashMap<Long, Row> rows = new LinkedHashMap<>();
    private final Map<UUID, Ingredient> byId = new HashMap<>();
    private final Map<String, Ingredient> byName = new HashMap<>();
    private long nextId = 1;
    private final boolean hadExisting;
    private boolean recipeOnlyDirty;

    private IngredientEditorState(boolean hadExisting, List<Ingredient> dictionary) {
        this.hadExisting = hadExisting;
        if (dictionary != null) {
            for (Ingredient item : dictionary) {
                if (item == null || item.getId() == null) continue;
                byId.put(item.getId(), item);
                addName(item.getName(), item);
                if (item.getAliases() != null) {
                    for (String alias : item.getAliases()) addName(alias, item);
                }
            }
        }
    }

    static IngredientEditorState empty(List<Ingredient> dictionary) {
        return new IngredientEditorState(false, dictionary);
    }

    static IngredientEditorState from(Recipe recipe, List<Ingredient> dictionary) {
        IngredientEditorState state = new IngredientEditorState(true, dictionary);
        if (recipe.getIngredients() != null) {
            for (RecipeIngredient item : recipe.getIngredients()) {
                if (item == null) continue;
                Row row = new Row(state.nextId++, Boolean.FALSE.equals(item.getTrackStock())
                        ? Kind.LEGACY_UNTRACKED : Kind.TRACKED);
                row.ingredientId = item.getIngredientId();
                row.quantity = item.getQuantity() == null ? "" : item.getQuantity();
                row.unit = item.getUnit();
                Ingredient ingredient = item.getIngredientId() == null ? null : state.byId.get(item.getIngredientId());
                row.trackedName = ingredient == null ? "" : ingredient.getName();
                state.rows.put(row.id, row);
            }
        }
        if (recipe.getRecipeOnlyIngredients() != null) {
            for (RecipeOnlyIngredient item : recipe.getRecipeOnlyIngredients()) {
                if (item == null) continue;
                Row row = new Row(state.nextId++, Kind.RECIPE_ONLY);
                row.recipeName = item.getName() == null ? "" : item.getName();
                row.amountText = item.getAmountText() == null ? "" : item.getAmountText();
                row.note = item.getNote() == null ? "" : item.getNote();
                state.rows.put(row.id, row);
            }
        }
        return state;
    }

    long addTracked() {
        Row row = new Row(nextId++, Kind.TRACKED);
        rows.put(row.id, row);
        return row.id;
    }

    long addRecipeOnly() {
        Row row = new Row(nextId++, Kind.RECIPE_ONLY);
        rows.put(row.id, row);
        recipeOnlyDirty = true;
        return row.id;
    }

    void remove(long id) {
        Row row = rows.remove(id);
        if (row != null && row.kind == Kind.RECIPE_ONLY) recipeOnlyDirty = true;
    }

    Row row(long id) { return rows.get(id); }

    List<Row> trackedRows() {
        List<Row> result = new ArrayList<>();
        for (Row row : rows.values()) if (row.trackedRow()) result.add(row);
        return result;
    }

    List<Row> recipeOnlyRows() {
        List<Row> result = new ArrayList<>();
        for (Row row : rows.values()) if (row.kind == Kind.RECIPE_ONLY) result.add(row);
        return result;
    }

    boolean recipeOnlyDirty() { return recipeOnlyDirty; }

    /** Null means "preserve existing recipe-only rows" on update. */
    List<RecipeOnlyIngredient> recipeOnlyPayloadForUpdate() {
        return hadExisting && !recipeOnlyDirty ? null : recipeOnlyIngredients();
    }

    List<RecipeOnlyIngredient> recipeOnlyIngredients() {
        List<RecipeOnlyIngredient> result = new ArrayList<>();
        for (Row row : recipeOnlyRows()) {
            result.add(new RecipeOnlyIngredient()
                    .name(row.recipeName == null ? "" : row.recipeName.trim())
                    .amountText(blankToNull(row.amountText))
                    .note(blankToNull(row.note)));
        }
        return result;
    }

    List<RecipeIngredient> recipeIngredients() {
        List<RecipeIngredient> result = new ArrayList<>();
        for (Row row : trackedRows()) {
            result.add(new RecipeIngredient().ingredientId(row.ingredientId)
                    .quantity(row.quantity)
                    .unit(row.unit)
                    .trackStock(row.kind == Kind.TRACKED));
        }
        return result;
    }

    boolean hasLegacyRows() {
        for (Row row : rows.values()) if (row.legacy()) return true;
        return false;
    }

    void setTrackedText(long id, String text) {
        Row row = rows.get(id);
        if (row == null || row.kind == Kind.LEGACY_UNTRACKED) return;
        row.trackedName = text == null ? "" : text;
        Ingredient ingredient = byName.get(normalize(row.trackedName));
        if (ingredient == null) {
            row.ingredientId = null;
            row.unit = null;
        } else {
            row.ingredientId = ingredient.getId();
            row.unit = ingredient.getUnit();
        }
    }

    void selectTrackedIngredient(long id, Ingredient ingredient) {
        Row row = rows.get(id);
        if (row == null || row.kind == Kind.LEGACY_UNTRACKED || ingredient == null) return;
        row.trackedName = ingredient.getName();
        row.ingredientId = ingredient.getId();
        row.unit = ingredient.getUnit();
    }

    void setQuantity(long id, String value) {
        Row row = rows.get(id);
        if (row == null || row.kind == Kind.LEGACY_UNTRACKED) return;
        row.quantity = value == null ? "" : value;
    }

    void setRecipeName(long id, String value) {
        Row row = rows.get(id);
        if (row == null || row.kind != Kind.RECIPE_ONLY) return;
        row.recipeName = value == null ? "" : value;
        recipeOnlyDirty = true;
    }

    void setAmountText(long id, String value) {
        Row row = rows.get(id);
        if (row == null || row.kind != Kind.RECIPE_ONLY) return;
        row.amountText = value == null ? "" : value;
        recipeOnlyDirty = true;
    }

    void setNote(long id, String value) {
        Row row = rows.get(id);
        if (row == null || row.kind != Kind.RECIPE_ONLY) return;
        row.note = value == null ? "" : value;
        recipeOnlyDirty = true;
    }

    /** Explicit cross-type action; both sides' values are retained. */
    void convert(long id) {
        Row row = rows.get(id);
        if (row == null) return;
        if (row.kind == Kind.RECIPE_ONLY) {
            row.kind = Kind.TRACKED;
            if (blank(row.trackedName)) row.trackedName = row.recipeName;
            recipeOnlyDirty = true;
            return;
        }
        if (row.kind == Kind.TRACKED || row.kind == Kind.LEGACY_UNTRACKED) {
            if (blank(row.recipeName)) row.recipeName = row.trackedName;
            if (blank(row.amountText)) row.amountText = amount(row.quantity, row.unit);
            row.kind = Kind.RECIPE_ONLY;
            recipeOnlyDirty = true;
        }
    }

    Validation validate() {
        List<RowError> errors = new ArrayList<>();
        if (rows.isEmpty()) {
            errors.add(new RowError(0, Field.EMPTY));
            return new Validation(errors);
        }
        List<Row> tracked = trackedRows();
        List<Row> recipeOnly = recipeOnlyRows();
        if (tracked.size() > 100 || recipeOnly.size() > 100) {
            errors.add(new RowError(0, Field.LIMIT));
        }
        Set<UUID> seen = new HashSet<>();
        Set<UUID> duplicates = new HashSet<>();
        for (Row row : tracked) {
            if (row.ingredientId == null) errors.add(new RowError(row.id, Field.INGREDIENT));
            if (row.unit == null) errors.add(new RowError(row.id, Field.UNIT));
            BigDecimal quantity = IngredientImportParser.number(row.quantity);
            if (quantity == null || (row.kind == Kind.TRACKED && quantity.signum() <= 0)) {
                errors.add(new RowError(row.id, Field.QUANTITY));
            }
            if (row.ingredientId != null && !seen.add(row.ingredientId)) duplicates.add(row.ingredientId);
        }
        for (Row row : tracked) {
            if (row.ingredientId != null && duplicates.contains(row.ingredientId)) {
                errors.add(new RowError(row.id, Field.DUPLICATE));
            }
        }
        for (Row row : recipeOnly) {
            String name = row.recipeName == null ? "" : row.recipeName.trim();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > 120) {
                errors.add(new RowError(row.id, Field.RECIPE_NAME));
            }
            if (row.amountText != null && row.amountText.codePointCount(0, row.amountText.length()) > 200) {
                errors.add(new RowError(row.id, Field.RECIPE_AMOUNT));
            }
            if (row.note != null && row.note.codePointCount(0, row.note.length()) > 500) {
                errors.add(new RowError(row.id, Field.RECIPE_NOTE));
            }
        }
        return new Validation(errors);
    }

    private void addName(String name, Ingredient ingredient) {
        String normalized = normalize(name);
        if (!normalized.isEmpty()) byName.putIfAbsent(normalized, ingredient);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    private static String amount(String quantity, Unit unit) {
        if (blank(quantity)) return "";
        return unit == null ? quantity : quantity + " " + unit.getValue();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
