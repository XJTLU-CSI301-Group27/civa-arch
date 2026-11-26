package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.Unit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

/** Free-text catalogue/personal rows can be converted to household recipes via the dictionary. */
public class HouseholdRecipesTest {
    private final UUID eggId = UUID.randomUUID();

    private Map<String, Ingredient> dictionary() {
        Ingredient egg = new Ingredient().id(eggId).name("鸡蛋").unit(Unit.COUNT).active(true);
        return HouseholdRecipes.byName(List.of(egg));
    }

    @Test public void resolvesKnownIngredientsAndQuantities() {
        CreateRecipeRequest request = HouseholdRecipes.request("番茄炒蛋", List.of("切块", "合炒"),
                List.of("鸡蛋 3"), dictionary());
        assertTrue(request != null);
        assertEquals(1, request.getIngredients().size());
        assertEquals(eggId, request.getIngredients().get(0).getIngredientId());
        assertEquals("3", request.getIngredients().get(0).getQuantity());
        assertTrue(request.getIngredients().get(0).getTrackStock());
        assertEquals("2", request.getYieldServings());
    }

    @Test public void missingQuantityIsNotTurnedIntoOne() {
        assertNull(HouseholdRecipes.request("怪菜", List.of("做"), List.of("鸡蛋"), dictionary()));
        assertNull(HouseholdRecipes.request("怪菜", List.of("做"), List.of("鸡蛋 0"), dictionary()));
        assertNull(HouseholdRecipes.request("怪菜", List.of("做"), List.of("鸡蛋 -1"), dictionary()));
    }

    @Test public void duplicateIngredientIsRejectedInsteadOfOverwritten() {
        assertNull(HouseholdRecipes.request("怪菜", List.of("做"), List.of("鸡蛋 2", "鸡蛋 3"), dictionary()));
    }

    @Test public void suppliedYieldAndDurationAreKept() {
        CreateRecipeRequest request = HouseholdRecipes.request("番茄炒蛋", List.of("做"),
                List.of("鸡蛋 3"), dictionary(), "3", 45);
        assertTrue(request != null);
        assertEquals("3", request.getYieldServings());
        assertEquals(Integer.valueOf(45), request.getDurationMinutes());
    }

    @Test public void verifiedRequestRejectsAUnitThatDoesNotMatchTheDictionary() {
        Ingredient egg = new Ingredient().id(eggId).name("鸡蛋").unit(Unit.COUNT).active(true);
        RecipeIngredient wrong = new RecipeIngredient().ingredientId(eggId).quantity("2")
                .unit(Unit.G).trackStock(true);
        assertNull(HouseholdRecipes.verifiedRequest("蛋", List.of("做"), List.of(wrong),
                "2", 30, List.of(egg)));
    }

    @Test public void verifiedRequestKeepsConfirmedYieldAndDuration() {
        Ingredient egg = new Ingredient().id(eggId).name("鸡蛋").unit(Unit.COUNT).active(true);
        RecipeIngredient line = new RecipeIngredient().ingredientId(eggId).quantity("2")
                .unit(Unit.COUNT).trackStock(true);
        CreateRecipeRequest request = HouseholdRecipes.verifiedRequest("蛋", List.of("做"),
                List.of(line), "3", 45, List.of(egg));

        assertTrue(request != null);
        assertEquals("3", request.getYieldServings());
        assertEquals(Integer.valueOf(45), request.getDurationMinutes());
        assertEquals("2", request.getIngredients().get(0).getQuantity());
        assertTrue(request.getIngredients().get(0).getTrackStock());
    }

    @Test public void verifiedRequestRejectsDuplicatesAndZero() {
        Ingredient egg = new Ingredient().id(eggId).name("鸡蛋").unit(Unit.COUNT).active(true);
        RecipeIngredient two = new RecipeIngredient().ingredientId(eggId).quantity("2")
                .unit(Unit.COUNT).trackStock(true);
        RecipeIngredient zero = new RecipeIngredient().ingredientId(eggId).quantity("0")
                .unit(Unit.COUNT).trackStock(true);

        assertNull(HouseholdRecipes.verifiedRequest("蛋", List.of("做"), List.of(two, two),
                "2", 30, List.of(egg)));
        assertNull(HouseholdRecipes.verifiedRequest("蛋", List.of("做"), List.of(zero),
                "2", 30, List.of(egg)));
    }

    @Test public void unknownIngredientsReturnNull() {
        assertNull(HouseholdRecipes.request("怪菜", List.of("做"), List.of("牛肉 2"), dictionary()));
    }

    @Test public void householdCardsAndKeysUseTheHouseholdPrefix() {
        Recipe recipe = new Recipe().id(UUID.randomUUID()).name("家庭菜")
                .steps(List.of("做")).ingredients(List.of())
                .tags(new java.util.LinkedHashSet<>(List.of("家常菜"))).version(1L);
        List<RecipeCard> cards = RecipeCard.cards().fromHousehold(List.of(recipe));
        assertEquals(RecipeCard.Kind.HOUSEHOLD, cards.get(0).kind());
        String id = UUID.randomUUID().toString();
        assertTrue(RecipeCard.isHousehold(RecipeCard.householdKey(id)));
        assertFalse(RecipeCard.isHousehold(id));
        assertEquals(id, RecipeCard.idOf(RecipeCard.householdKey(id)));
    }

    @Test public void ingredientDisplayResolvesNames() {
        Recipe recipe = new Recipe().id(UUID.randomUUID()).name("家庭菜").steps(List.of("做"))
                .ingredients(List.of(new RecipeIngredient().ingredientId(eggId).quantity("3").unit(Unit.COUNT).trackStock(true)))
                .version(1L);
        String display = HouseholdRecipes.display(recipe, Map.of(eggId, "鸡蛋"));
        assertTrue(display.contains("鸡蛋 3 COUNT"));
    }
}
