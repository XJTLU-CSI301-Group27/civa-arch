package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.RecipeOnlyIngredient;
import edu.xjtlu.civa.api.model.Unit;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class ImportRequestTest {
    private final UUID egg = UUID.randomUUID();
    private final RecipeIngredient tracked = new RecipeIngredient().ingredientId(egg)
            .quantity("2").unit(Unit.COUNT).trackStock(true);
    private final RecipeOnlyIngredient salt = new RecipeOnlyIngredient().name("食盐").amountText("适量");

    @Test public void trackedOnlyAndRecipeOnlyOnlyAndMixedAreAllValid() {
        assertNotNull(ImportRequest.build("t", List.of("x"), List.of(tracked), List.of(), "2", 20));
        assertNotNull(ImportRequest.build("o", List.of("x"), List.of(), List.of(salt), "2", 20));
        var mixed = ImportRequest.build("m", List.of("x"), List.of(tracked), List.of(salt), "2", 20);
        assertNotNull(mixed);
        assertEquals(1, mixed.getIngredients().size());
        assertEquals(1, mixed.getRecipeOnlyIngredients().size());
    }

    @Test public void bothTypesEmptyInvalidQuantityAndDuplicateTrackedAreRejected() {
        assertNull(ImportRequest.build("x", List.of("x"), List.of(), List.of(), "2", 20));
        assertNull(ImportRequest.build("x", List.of("x"),
                List.of(new RecipeIngredient().ingredientId(egg).quantity("0").unit(Unit.COUNT).trackStock(true)),
                List.of(), "2", 20));
        assertNull(ImportRequest.build("x", List.of("x"), List.of(tracked, tracked), List.of(), "2", 20));
    }

    @Test public void recipeOnlyAllowsNullAmountAndRejectsMissingName() {
        var request = ImportRequest.build("x", List.of("x"), List.of(),
                List.of(new RecipeOnlyIngredient().name("香菜").amountText(null)), "2", 20);
        assertNotNull(request);
        assertNull(request.getRecipeOnlyIngredients().get(0).getAmountText());
        assertNull(ImportRequest.build("x", List.of("x"), List.of(),
                List.of(new RecipeOnlyIngredient().name(" ").amountText("适量")), "2", 20));
    }
}
