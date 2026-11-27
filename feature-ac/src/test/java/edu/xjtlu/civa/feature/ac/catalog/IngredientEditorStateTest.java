package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.RecipeOnlyIngredient;
import edu.xjtlu.civa.api.model.Unit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public class IngredientEditorStateTest {
    private static final UUID EGG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FLOUR = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private final Ingredient egg = new Ingredient().id(EGG).name("鸡蛋").unit(Unit.COUNT).active(true);
    private final Ingredient flour = new Ingredient().id(FLOUR).name("面粉").unit(Unit.G).active(true);
    private final List<Ingredient> dictionary = List.of(egg, flour);

    @Test public void trackedRowsCanBeAddedEditedAndRemoved() {
        IngredientEditorState state = IngredientEditorState.empty(dictionary);
        long id = state.addTracked();
        state.setTrackedText(id, "鸡蛋");
        state.setQuantity(id, "3");

        assertEquals(EGG, state.row(id).ingredientId);
        assertEquals(Unit.COUNT, state.row(id).unit);
        assertEquals("3", state.row(id).quantity);
        assertTrue(state.validate().valid());

        state.remove(id);
        assertEquals(0, state.trackedRows().size());
        assertFalse(state.validate().valid());
    }

    @Test public void recipeOnlyRowsCanBeAddedEditedAndRemoved() {
        IngredientEditorState state = IngredientEditorState.empty(dictionary);
        long id = state.addRecipeOnly();
        state.setRecipeName(id, "食盐");
        state.setAmountText(id, "适量");
        state.setNote(id, "出锅前放");

        assertEquals("适量", state.row(id).amountText);
        assertTrue(state.validate().valid());
        assertTrue(state.recipeOnlyDirty());
        assertEquals(1, state.recipeOnlyIngredients().size());

        state.remove(id);
        assertEquals(0, state.recipeOnlyRows().size());
        assertTrue(state.recipeOnlyIngredients().isEmpty());
    }

    @Test public void trackedToRecipeOnlyKeepsTheOriginalAmountAsText() {
        IngredientEditorState state = IngredientEditorState.empty(dictionary);
        long id = state.addTracked();
        state.setTrackedText(id, "鸡蛋");
        state.setQuantity(id, "2");
        state.convert(id);

        assertEquals(IngredientEditorState.Kind.RECIPE_ONLY, state.row(id).kind);
        assertEquals("鸡蛋", state.row(id).recipeName);
        assertEquals("2 COUNT", state.row(id).amountText);
        assertEquals(0, state.trackedRows().size());
        assertTrue(state.validate().valid());
    }

    @Test public void recipeOnlyToTrackedRetainsTextButRequiresAValidTrackedRow() {
        IngredientEditorState state = IngredientEditorState.empty(dictionary);
        long id = state.addRecipeOnly();
        state.setRecipeName(id, "面粉");
        state.setAmountText(id, "2 勺");
        state.convert(id);

        assertEquals(IngredientEditorState.Kind.TRACKED, state.row(id).kind);
        assertEquals("面粉", state.row(id).trackedName);
        assertFalse(state.validate().valid());

        state.setTrackedText(id, "面粉");
        state.setQuantity(id, "200");
        assertTrue(state.validate().valid());
    }

    @Test public void onlyRecipeOnlyAndMixedRecipesAreValid() {
        IngredientEditorState only = IngredientEditorState.empty(dictionary);
        long salt = only.addRecipeOnly();
        only.setRecipeName(salt, "食盐");
        only.setAmountText(salt, "适量");
        assertTrue(only.validate().valid());
        assertTrue(only.recipeIngredients().isEmpty());

        IngredientEditorState mixed = IngredientEditorState.from(recipe(
                List.of(tracked(EGG, "2", Unit.COUNT, true)),
                List.of(only("食盐", "适量"))), dictionary);
        assertTrue(mixed.validate().valid());
        assertEquals(1, mixed.recipeIngredients().size());
        assertEquals(1, mixed.recipeOnlyIngredients().size());
    }

    @Test public void bothEmptyDuplicateAndInvalidTrackedRowsAreRejected() {
        IngredientEditorState empty = IngredientEditorState.empty(dictionary);
        assertFalse(empty.validate().valid());
        assertTrue(empty.validate().errors().get(0).field() == IngredientEditorState.Field.EMPTY);

        IngredientEditorState duplicate = IngredientEditorState.empty(dictionary);
        long first = duplicate.addTracked();
        long second = duplicate.addTracked();
        duplicate.setTrackedText(first, "鸡蛋");
        duplicate.setQuantity(first, "2");
        duplicate.setTrackedText(second, "鸡蛋");
        duplicate.setQuantity(second, "3");
        assertFalse(duplicate.validate().valid());
        assertTrue(duplicate.validate().errors().stream()
                .anyMatch(error -> error.field() == IngredientEditorState.Field.DUPLICATE));

        IngredientEditorState invalid = IngredientEditorState.empty(dictionary);
        long row = invalid.addTracked();
        invalid.setTrackedText(row, "鸡蛋");
        invalid.setQuantity(row, "0");
        assertFalse(invalid.validate().valid());
        assertTrue(invalid.validate().errors().stream()
                .anyMatch(error -> error.field() == IngredientEditorState.Field.QUANTITY));
    }

    @Test public void legacyFalseRowsArePreservedAndOnlyConvertedExplicitly() {
        IngredientEditorState state = IngredientEditorState.from(recipe(
                List.of(tracked(EGG, "0", Unit.COUNT, false)), List.of()), dictionary);
        assertEquals(IngredientEditorState.Kind.LEGACY_UNTRACKED, state.trackedRows().get(0).kind);
        assertTrue(state.validate().valid());
        List<RecipeIngredient> tracked = state.recipeIngredients();
        assertEquals(1, tracked.size());
        assertFalse(tracked.get(0).getTrackStock());

        long id = state.trackedRows().get(0).id;
        state.convert(id);
        assertEquals(IngredientEditorState.Kind.RECIPE_ONLY, state.row(id).kind);
        assertTrue(state.recipeOnlyDirty());
        assertTrue(state.recipeIngredients().isEmpty());
        assertEquals("0 COUNT", state.row(id).amountText);
    }

    @Test public void updatePayloadIsNullUntilRecipeOnlyRowsActuallyChange() {
        IngredientEditorState state = IngredientEditorState.from(recipe(
                List.of(tracked(EGG, "2", Unit.COUNT, true)), List.of(only("食盐", "适量"))), dictionary);
        assertFalse(state.recipeOnlyDirty());
        assertNull(state.recipeOnlyPayloadForUpdate());

        long trackedId = state.trackedRows().get(0).id;
        state.setQuantity(trackedId, "3");
        assertNull(state.recipeOnlyPayloadForUpdate());

        long recipeOnlyId = state.recipeOnlyRows().get(0).id;
        state.setAmountText(recipeOnlyId, "少许");
        assertTrue(state.recipeOnlyDirty());
        assertEquals(1, state.recipeOnlyPayloadForUpdate().size());

        state.remove(recipeOnlyId);
        assertTrue(state.recipeOnlyPayloadForUpdate().isEmpty());
    }

    @Test public void stableIdsAndDictionaryLookupAreExact() {
        IngredientEditorState state = IngredientEditorState.empty(dictionary);
        long first = state.addTracked();
        long second = state.addTracked();
        assertTrue(second > first);
        state.setTrackedText(first, "鸡蛋液");
        assertNull(state.row(first).ingredientId);
        assertNull(state.row(first).unit);
        state.setTrackedText(first, "鸡蛋");
        assertEquals(EGG, state.row(first).ingredientId);
        assertEquals(Unit.COUNT, state.row(first).unit);
    }

    @Test public void recipeOnlyTextLimitsAndNameRequirementAreValidated() {
        IngredientEditorState state = IngredientEditorState.empty(dictionary);
        long id = state.addRecipeOnly();
        state.setRecipeName(id, " ");
        assertFalse(state.validate().valid());
        state.setRecipeName(id, "盐");
        state.setAmountText(id, "x".repeat(201));
        assertFalse(state.validate().valid());
        state.setAmountText(id, "适量");
        state.setNote(id, "x".repeat(501));
        assertFalse(state.validate().valid());
        state.setNote(id, "备注");
        assertTrue(state.validate().valid());
    }

    private static Recipe recipe(List<RecipeIngredient> tracked, List<RecipeOnlyIngredient> only) {
        return new Recipe().id(UUID.randomUUID()).householdId(UUID.randomUUID()).authorId(UUID.randomUUID())
                .name("测试").steps(List.of("做")).ingredients(tracked).recipeOnlyIngredients(only)
                .yieldServings("2").durationMinutes(20).tags(Set.of()).archived(false).version(1L);
    }

    private static RecipeIngredient tracked(UUID id, String quantity, Unit unit, boolean trackStock) {
        return new RecipeIngredient().ingredientId(id).quantity(quantity).unit(unit).trackStock(trackStock);
    }

    private static RecipeOnlyIngredient only(String name, String amount) {
        return new RecipeOnlyIngredient().name(name).amountText(amount);
    }
}
