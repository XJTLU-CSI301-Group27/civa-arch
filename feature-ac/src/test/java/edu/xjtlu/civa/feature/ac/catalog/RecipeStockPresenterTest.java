package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.MatchLine;
import edu.xjtlu.civa.api.model.MatchStatus;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.RecipeMatch;
import edu.xjtlu.civa.api.model.Unit;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public class RecipeStockPresenterTest {
    private static final UUID RECIPE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EGG = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MILK = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test public void enoughInventoryIsEnough() {
        RecipeStockPresenter.StockView view = present(
                List.of(line(EGG, "2", "3", Unit.G, MatchStatus.ENOUGH)),
                Map.of(EGG, ingredient(EGG, "Egg", Unit.G)));

        assertEquals(RecipeStockPresenter.State.ENOUGH, view.state());
        assertTrue(view.tracked());
        assertTrue(view.missing().isEmpty());
    }

    @Test public void partialStockReportsTheExactShortage() {
        RecipeStockPresenter.StockView view = present(
                List.of(line(EGG, "3", "1", Unit.G, MatchStatus.MISSING)),
                Map.of(EGG, ingredient(EGG, "Egg", Unit.G)));

        assertEquals(RecipeStockPresenter.State.MISSING, view.state());
        assertEquals(1, view.missing().size());
        assertEquals("2", view.missing().get(0).quantity());
        assertEquals("Egg", view.missing().get(0).name());
    }

    @Test public void emptyFridgeIsMissingNotUnknown() {
        RecipeStockPresenter.StockView view = present(
                List.of(line(EGG, "2", "0", Unit.G, MatchStatus.MISSING)),
                Map.of(EGG, ingredient(EGG, "Egg", Unit.G)));

        assertEquals(RecipeStockPresenter.State.MISSING, view.state());
        assertEquals("2", view.missing().get(0).quantity());
    }

    @Test public void expiredOrZeroStockIsAlreadyMissingFromTheServer() {
        // The server excludes expired and zero-quantity batches; the client must show the shortage.
        RecipeStockPresenter.StockView view = present(
                List.of(line(EGG, "1", "0", Unit.COUNT, MatchStatus.MISSING)),
                Map.of(EGG, ingredient(EGG, "Egg", Unit.COUNT)));

        assertEquals(RecipeStockPresenter.State.MISSING, view.state());
        assertEquals(Unit.COUNT, view.missing().get(0).unit());
    }

    @Test public void nullRequiredIsUnknownRatherThanZero() {
        assertEquals(RecipeStockPresenter.State.UNKNOWN,
                present(List.of(line(EGG, null, "5", Unit.G, MatchStatus.UNKNOWN)),
                        Map.of(EGG, ingredient(EGG, "Egg", Unit.G))).state());
    }

    @Test public void nullAvailableIsUnknown() {
        assertEquals(RecipeStockPresenter.State.UNKNOWN,
                present(List.of(line(EGG, "2", null, Unit.G, MatchStatus.MISSING)),
                        Map.of(EGG, ingredient(EGG, "Egg", Unit.G))).state());
    }

    @Test public void missingDictionaryEntryIsUnknown() {
        assertEquals(RecipeStockPresenter.State.UNKNOWN,
                present(List.of(line(EGG, "2", "0", Unit.G, MatchStatus.MISSING)), Map.of()).state());
    }

    @Test public void unitMismatchIsUnknownNotMissingZero() {
        // The server counted no G stock for a recipe written in ML; that is incomparable, not a shortage.
        assertEquals(RecipeStockPresenter.State.UNKNOWN,
                present(List.of(line(EGG, "2", "0", Unit.ML, MatchStatus.MISSING)),
                        Map.of(EGG, ingredient(EGG, "Egg", Unit.G))).state());
    }

    @Test public void repeatedIngredientSumsRequiredButUsesSharedAvailableOnce() {
        RecipeStockPresenter.StockView view = present(
                List.of(line(EGG, "2", "4", Unit.G, MatchStatus.MISSING),
                        line(EGG, "3", "4", Unit.G, MatchStatus.MISSING)),
                Map.of(EGG, ingredient(EGG, "Egg", Unit.G)));

        assertEquals(RecipeStockPresenter.State.MISSING, view.state());
        assertEquals(1, view.missing().size());
        assertEquals("1", view.missing().get(0).quantity());
    }

    @Test public void inconsistentSharedBalanceIsUnknown() {
        assertEquals(RecipeStockPresenter.State.UNKNOWN,
                present(List.of(line(EGG, "2", "4", Unit.G, MatchStatus.ENOUGH),
                                line(EGG, "3", "5", Unit.G, MatchStatus.ENOUGH)),
                        Map.of(EGG, ingredient(EGG, "Egg", Unit.G))).state());
    }

    @Test public void sameIngredientInDifferentUnitsIsUnknown() {
        assertEquals(RecipeStockPresenter.State.UNKNOWN,
                present(List.of(line(EGG, "2", "10", Unit.G, MatchStatus.ENOUGH),
                                line(EGG, "1", "2", Unit.ML, MatchStatus.ENOUGH)),
                        Map.of(EGG, ingredient(EGG, "Egg", Unit.G))).state());
    }

    @Test public void untrackedRecipeIsEnoughButMarkedNoStockRequired() {
        RecipeStockPresenter.StockView view = present(List.of(), Map.of());

        assertEquals(RecipeStockPresenter.State.ENOUGH, view.state());
        assertFalse(view.tracked());
        assertTrue(view.noStockRequired());
    }

    @Test public void missingMatchOrMismatchedRecipeIsUnknown() {
        assertEquals(RecipeStockPresenter.State.UNKNOWN,
                RecipeStockPresenter.present(recipe("2"), null, Map.of(), "2").state());
        assertEquals(RecipeStockPresenter.State.UNKNOWN,
                RecipeStockPresenter.present(recipe("2"),
                        match(UUID.randomUUID(), List.of(line(EGG, "2", "3", Unit.G, MatchStatus.ENOUGH))),
                        Map.of(EGG, ingredient(EGG, "Egg", Unit.G)), "2").state());
    }

    @Test public void coverageIsNotUsedAsTheOverallState() {
        RecipeMatch match = match(RECIPE, List.of(line(EGG, "3", "1", Unit.G, MatchStatus.MISSING)));
        match.coverage("1");

        assertEquals(RecipeStockPresenter.State.MISSING,
                RecipeStockPresenter.present(recipe("2"), match,
                        Map.of(EGG, ingredient(EGG, "Egg", Unit.G)), "2").state());
    }

    @Test public void severalShortagesAreAllRetainedForTheUiToTruncate() {
        RecipeStockPresenter.StockView view = present(
                List.of(line(EGG, "3", "1", Unit.G, MatchStatus.MISSING),
                        line(MILK, "2", "0", Unit.ML, MatchStatus.MISSING)),
                Map.of(EGG, ingredient(EGG, "Egg", Unit.G),
                        MILK, ingredient(MILK, "Milk", Unit.ML)));

        assertEquals(2, view.missing().size());
        assertEquals("2", view.missing().get(0).quantity());
        assertEquals("2", view.missing().get(1).quantity());
    }

    @Test public void servingsUseAValidYieldOrFallBackToOne() {
        assertEquals("2.5", RecipeStockPresenter.servingsFor(recipe("2.5")));
        assertEquals("1", RecipeStockPresenter.servingsFor(recipe(null)));
        assertEquals("1", RecipeStockPresenter.servingsFor(recipe("0")));
        assertEquals("1", RecipeStockPresenter.servingsFor(recipe("abc")));
    }

    @Test public void requestedServingsAreKeptForTheBasisLabel() {
        RecipeStockPresenter.StockView view = RecipeStockPresenter.present(
                recipe("2"), match(RECIPE, List.of(line(EGG, "2", "3", Unit.G, MatchStatus.ENOUGH))),
                Map.of(EGG, ingredient(EGG, "Egg", Unit.G)), "1");

        assertEquals("1", view.servings());
    }

    @Test public void stateRankSortsStablyAndKeepsEveryState() {
        RecipeStockPresenter.StockView enoughOne = new RecipeStockPresenter.StockView(
                RecipeStockPresenter.State.ENOUGH, "1", true, List.of());
        RecipeStockPresenter.StockView enoughTwo = new RecipeStockPresenter.StockView(
                RecipeStockPresenter.State.ENOUGH, "1", true, List.of());
        RecipeStockPresenter.StockView missing = new RecipeStockPresenter.StockView(
                RecipeStockPresenter.State.MISSING, "1", true, List.of());
        RecipeStockPresenter.StockView unknown = new RecipeStockPresenter.StockView(
                RecipeStockPresenter.State.UNKNOWN, "1", false, List.of());
        List<RecipeStockPresenter.StockView> views = new ArrayList<>(List.of(unknown, missing, enoughTwo, enoughOne));

        views.sort(Comparator.comparingInt(RecipeStockPresenter::rank));

        assertEquals(4, views.size());
        assertSame(enoughTwo, views.get(0));
        assertSame(enoughOne, views.get(1));
        assertSame(missing, views.get(2));
        assertSame(unknown, views.get(3));
    }

    private RecipeStockPresenter.StockView present(List<MatchLine> lines, Map<UUID, Ingredient> dictionary) {
        return RecipeStockPresenter.present(recipe("2"), match(RECIPE, lines), dictionary, "2");
    }

    private static Recipe recipe(String yield) {
        return new Recipe()
                .id(RECIPE).householdId(UUID.randomUUID()).authorId(UUID.randomUUID())
                .name("Dish").steps(List.of("cook")).ingredients(List.of(
                        new RecipeIngredient().ingredientId(EGG).quantity("1").unit(Unit.G).trackStock(true)))
                .yieldServings(yield).durationMinutes(10).tags(java.util.Set.of())
                .archived(false).version(1L);
    }

    private static RecipeMatch match(UUID recipeId, List<MatchLine> lines) {
        return new RecipeMatch().recipeId(recipeId).recipeVersion(1L).servings("2")
                .lines(lines).coverage("0").checkedAt("2026-11-27T00:00:00.000Z");
    }

    private static MatchLine line(UUID ingredientId, String required, String available, Unit unit,
                                  MatchStatus status) {
        return new MatchLine().ingredientId(ingredientId).required(required).available(available)
                .unit(unit).status(status);
    }

    private static Ingredient ingredient(UUID id, String name, Unit unit) {
        return new Ingredient().id(id).name(name).aliases(List.of()).unit(unit).active(true);
    }
}
