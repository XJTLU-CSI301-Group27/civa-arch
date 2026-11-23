package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.CatalogRecipeIngredient;
import edu.xjtlu.civa.api.model.PersonalRecipe;
import edu.xjtlu.civa.api.model.PersonalRecipeIngredient;
import edu.xjtlu.civa.api.model.RecipeFavorite;
import edu.xjtlu.civa.api.model.RecipeFavoriteKind;
import java.util.List;
import java.util.UUID;
import org.junit.Test;

public class RecipeCardTest {
    private final RecipeCard.RecipeCards cards = RecipeCard.cards();

    @Test public void catalogueAndPersonalRowsBecomeCardsWithTheirOwnKind() {
        CatalogRecipe catalog = new CatalogRecipe()
                .id(UUID.randomUUID()).name("一品锅").category("家常菜").cuisine("徽菜")
                .description("").steps(List.of("炖")).ingredients(List.of(new CatalogRecipeIngredient().name("鸡块")))
                .methods(List.of("炖")).flavors(List.of("咸鲜")).mealTypes(List.of("晚餐"))
                .source(CatalogRecipe.SourceEnum.A).kcal("580").estimatedMinutes(40);
        PersonalRecipe personal = new PersonalRecipe()
                .id(UUID.randomUUID()).name("我的小炒").steps(List.of("炒"))
                .ingredients(List.of(new PersonalRecipeIngredient().name("青椒"))).archived(false).version(1L)
                .flavors(List.of("辣"));

        List<RecipeCard> catalogCards = cards.fromCatalog(List.of(catalog));
        assertEquals(RecipeCard.Kind.CATALOG, catalogCards.get(0).kind());
        assertEquals("一品锅", catalogCards.get(0).name());
        assertTrue(catalogCards.get(0).meta().contains("580 kcal"));
        assertTrue(catalogCards.get(0).meta().contains("40 分钟"));

        List<RecipeCard> personalCards = cards.fromPersonal(List.of(personal));
        assertEquals(RecipeCard.Kind.PERSONAL, personalCards.get(0).kind());
        assertEquals("辣", personalCards.get(0).tags());
    }

    @Test public void favouritesSkipHouseholdRowsAndMapKinds() {
        RecipeFavorite catalog = new RecipeFavorite().kind(RecipeFavoriteKind.CATALOG).recipeId(UUID.randomUUID()).name("一品锅").createdAt("now");
        RecipeFavorite personal = new RecipeFavorite().kind(RecipeFavoriteKind.PERSONAL).recipeId(UUID.randomUUID()).name("我的小炒").createdAt("now");
        RecipeFavorite household = new RecipeFavorite().kind(RecipeFavoriteKind.HOUSEHOLD).recipeId(UUID.randomUUID()).name("家庭菜").createdAt("now");
        List<RecipeCard> result = cards.fromFavorites(List.of(catalog, personal, household));
        assertEquals(3, result.size());
        assertEquals(RecipeCard.Kind.CATALOG, result.get(0).kind());
        assertEquals(RecipeCard.Kind.PERSONAL, result.get(1).kind());
        assertEquals(RecipeCard.Kind.HOUSEHOLD, result.get(2).kind());
    }

    @Test public void entityKeysDistinguishPersonalRoutes() {
        String id = UUID.randomUUID().toString();
        String key = RecipeCard.personalKey(id);
        assertTrue(RecipeCard.isPersonal(key));
        assertFalse(RecipeCard.isPersonal(id));
        assertEquals(id, RecipeCard.idOf(key));
        assertEquals(id, RecipeCard.idOf(id));
    }

    @Test public void editorSplitsLinesAndTrims() {
        assertEquals(List.of("番茄 2 个", "鸡蛋 3 个"), PersonalRecipeEditorFragment.lines(" 番茄 2 个 \n\n鸡蛋 3 个 "));
        List<PersonalRecipeIngredient> items = PersonalRecipeEditorFragment.ingredientItems("番茄 2 个\n鸡蛋 3 个");
        assertEquals(2, items.size());
        assertEquals("番茄 2 个", items.get(0).getName());
    }
}
