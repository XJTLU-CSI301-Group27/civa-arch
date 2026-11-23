package edu.xjtlu.civa.feature.bd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.CatalogRecipeIngredient;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

/** The public catalogue is adapted into the prompt-card shape without inventing data. */
public class BdRecipeCatalogMappingTest {
    private CatalogRecipe recipe(String name, String ingredient, List<String> flavors, List<String> steps) {
        return new CatalogRecipe()
                .id(UUID.randomUUID())
                .name(name)
                .category("家常菜")
                .cuisine("川菜")
                .description("描述")
                .steps(steps)
                .ingredients(List.of(new CatalogRecipeIngredient().name(ingredient)))
                .methods(List.of("炖"))
                .flavors(flavors)
                .mealTypes(List.of("晚餐"))
                .source(CatalogRecipe.SourceEnum.A)
                .estimatedMinutes(25);
    }

    @Test public void mapsNamesIngredientsFlavorsAndMinutes() {
        List<BdRecipeCatalog.Recipe> mapped = BdRecipeCatalog.fromCatalog(List.of(
                recipe("辣子鸡", "鸡腿", List.of("辣"), List.of("切块", "下锅")),
                recipe("清蒸鱼", "鲈鱼", List.of("清淡"), List.of("蒸"))));
        assertEquals(2, mapped.size());
        assertEquals("辣子鸡", mapped.get(0).name);
        assertTrue(mapped.get(0).spicy);
        assertTrue(mapped.get(0).meat);
        assertEquals(Integer.valueOf(25), mapped.get(0).minutes);
        assertEquals("鸡腿", mapped.get(0).ingredients.get(0));
        assertEquals(2, mapped.get(0).steps.size());
        assertFalse(mapped.get(1).spicy);
        assertNull(mapped.get(1).image);
    }

    @Test public void skipsRowsWithoutUsableContent() {
        CatalogRecipe broken = new CatalogRecipe()
                .id(UUID.randomUUID())
                .name("空菜")
                .category("家常菜")
                .cuisine("通用")
                .description("")
                .steps(List.of())
                .ingredients(List.of())
                .methods(List.of())
                .flavors(List.of())
                .mealTypes(List.of())
                .source(CatalogRecipe.SourceEnum.BD);
        assertTrue(BdRecipeCatalog.fromCatalog(List.of(broken)).isEmpty());
    }
}
