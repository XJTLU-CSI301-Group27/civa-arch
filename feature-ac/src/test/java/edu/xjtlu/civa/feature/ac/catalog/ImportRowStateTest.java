package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.RecipeOnlyIngredient;
import edu.xjtlu.civa.api.model.Unit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class ImportRowStateTest {
    private final Ingredient egg = new Ingredient().id(UUID.randomUUID()).name("鸡蛋")
            .unit(Unit.COUNT).active(true);

    @Test public void readyLineDefaultsToTrackedAndCanBecomeRecipeOnlyWithOriginalText() {
        ImportRowState state = new ImportRowState(line(new IngredientImportParser.Input(
                "鸡蛋", "1.5", null, "kg", "散养", null), egg.getId(), "鸡蛋", "1500",
                Unit.G, IngredientImportParser.Kind.CONVERT, IngredientImportParser.Reason.OK));

        assertTrue(state.isTracked());
        state.mode(ImportRowState.Mode.RECIPE_ONLY);
        assertEquals("鸡蛋", state.recipeName());
        assertEquals("1.5 kg", state.amountText());
        assertEquals("散养", state.note());
        RecipeOnlyIngredient only = state.toRecipeOnly();
        assertEquals("鸡蛋", only.getName());
        assertEquals("1.5 kg", only.getAmountText());
        assertEquals("散养", only.getNote());
    }

    @Test public void incompleteLineStartsUndecidedAndRequiresAnExplicitType() {
        ImportRowState state = new ImportRowState(line(new IngredientImportParser.Input(
                "食盐", "适量", null, null, null, null), null, null, null, null,
                IngredientImportParser.Kind.REVIEW, IngredientImportParser.Reason.MISSING_QUANTITY));

        assertEquals(ImportRowState.Mode.NONE, state.mode());
        assertEquals(ImportRowState.Error.CHOOSE, state.validate(new HashSet<>(), List.of(egg)));
        state.mode(ImportRowState.Mode.RECIPE_ONLY);
        state.recipeName("食盐");
        state.amountText("适量");
        assertEquals(ImportRowState.Error.NONE, state.validate(new HashSet<>(), List.of(egg)));
        assertEquals("食盐", state.toRecipeOnly().getName());
        assertEquals("适量", state.toRecipeOnly().getAmountText());
    }

    @Test public void incompleteLineCanBecomeTrackedOnlyWithDictionaryAndPositiveQuantity() {
        ImportRowState state = new ImportRowState(line(new IngredientImportParser.Input(
                "未知", "2", "勺", null, null, null), null, null, null, null,
                IngredientImportParser.Kind.REVIEW, IngredientImportParser.Reason.NO_INGREDIENT));
        state.mode(ImportRowState.Mode.TRACKED);

        assertEquals(ImportRowState.Error.INGREDIENT, state.validate(new HashSet<>(), List.of(egg)));
        state.trackedId(egg.getId());
        state.quantity("0");
        assertEquals(ImportRowState.Error.QUANTITY, state.validate(new HashSet<>(), List.of(egg)));
        state.quantity("2");
        assertEquals(ImportRowState.Error.NONE, state.validate(new HashSet<>(), List.of(egg)));
    }

    @Test public void duplicateTrackedIdsAreRejected() {
        ImportRowState state = new ImportRowState(line(new IngredientImportParser.Input(
                "鸡蛋", "2", null, "个", null, null), egg.getId(), "鸡蛋", "2",
                Unit.COUNT, IngredientImportParser.Kind.DIRECT, IngredientImportParser.Reason.OK));
        HashSet<UUID> seen = new HashSet<>();
        seen.add(egg.getId());
        assertEquals(ImportRowState.Error.DUPLICATE, state.validate(seen, List.of(egg)));
    }

    @Test public void amountTextIsOptionalAndSourceTextIsNotDumpedIntoIt() {
        ImportRowState state = new ImportRowState(line(new IngredientImportParser.Input(
                "香菜", null, null, null, null, "香菜 根据个人口味添加"), null, null, null, null,
                IngredientImportParser.Kind.REVIEW, IngredientImportParser.Reason.NO_INGREDIENT));
        state.mode(ImportRowState.Mode.RECIPE_ONLY);
        state.recipeName("香菜");
        assertNull(state.amountText());
        assertEquals(ImportRowState.Error.NONE, state.validate(new HashSet<>(), List.of(egg)));
        assertNull(state.toRecipeOnly().getAmountText());
    }

    @Test public void nameLengthIsLimitedAndMissingNameIsRejected() {
        ImportRowState state = new ImportRowState(line(new IngredientImportParser.Input(
                "盐", null, null, null, null, null), null, null, null, null,
                IngredientImportParser.Kind.REVIEW, IngredientImportParser.Reason.NO_INGREDIENT));
        state.mode(ImportRowState.Mode.RECIPE_ONLY);
        state.recipeName("");
        assertEquals(ImportRowState.Error.NAME, state.validate(new HashSet<>(), List.of(egg)));
        state.recipeName("x".repeat(121));
        assertEquals(ImportRowState.Error.NAME, state.validate(new HashSet<>(), List.of(egg)));
    }

    private static IngredientImportParser.Line line(IngredientImportParser.Input input, UUID id,
                                                     String name, String quantity, Unit unit,
                                                     IngredientImportParser.Kind kind,
                                                     IngredientImportParser.Reason reason) {
        return new IngredientImportParser.Line(input, id, name, quantity, unit, kind, reason);
    }
}
