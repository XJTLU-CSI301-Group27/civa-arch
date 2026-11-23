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
                List.of("鸡蛋 3", "鸡蛋"), dictionary());
        assertTrue(request != null);
        assertEquals(2, request.getIngredients().size());
        assertEquals(eggId, request.getIngredients().get(0).getIngredientId());
        assertEquals("3", request.getIngredients().get(0).getQuantity());
        assertTrue(request.getIngredients().get(0).getTrackStock());
        assertEquals("2", request.getYieldServings());
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
