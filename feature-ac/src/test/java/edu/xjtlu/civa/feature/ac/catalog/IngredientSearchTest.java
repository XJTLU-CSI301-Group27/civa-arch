package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.Unit;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class IngredientSearchTest {
    private final Ingredient egg = new Ingredient().id(UUID.randomUUID()).name("鸡蛋")
            .aliases(List.of("蛋")).unit(Unit.COUNT).active(true);
    private final Ingredient flour = new Ingredient().id(UUID.randomUUID()).name("面粉")
            .aliases(List.of("小麦粉")).unit(Unit.G).active(true);

    @Test public void emptyQueryReturnsTheWholeDictionary() {
        assertEquals(2, IngredientSearch.filter(List.of(egg, flour), "").size());
        assertEquals(2, IngredientSearch.filter(List.of(egg, flour), null).size());
    }

    @Test public void searchesCanonicalNamesAndAliasesCaseInsensitively() {
        assertEquals(egg.getId(), IngredientSearch.filter(List.of(egg, flour), "鸡蛋").get(0).getId());
        assertEquals(egg.getId(), IngredientSearch.filter(List.of(egg, flour), "蛋").get(0).getId());
        assertEquals(flour.getId(), IngredientSearch.filter(List.of(egg, flour), "小麦粉").get(0).getId());
        assertEquals(flour.getId(), IngredientSearch.filter(List.of(egg, flour), "面粉").get(0).getId());
    }

    @Test public void noMatchReturnsNothing() {
        assertTrue(IngredientSearch.filter(List.of(egg, flour), "牛肉").isEmpty());
    }
}
