package edu.xjtlu.civa.core.data;

import edu.xjtlu.civa.api.HouseholdApi;
import edu.xjtlu.civa.api.InventoryApi;
import edu.xjtlu.civa.api.MediaApi;
import edu.xjtlu.civa.api.PlanningApi;
import edu.xjtlu.civa.api.NutritionApi;
import edu.xjtlu.civa.api.ProfileApi;
import edu.xjtlu.civa.api.RecipeApi;
import edu.xjtlu.civa.api.PersonalRecipeApi;
import edu.xjtlu.civa.api.RecipeCatalogApi;
import edu.xjtlu.civa.api.RecipeFavoriteApi;
import edu.xjtlu.civa.api.RecordsApi;

/** Shared feature-facing endpoints. Calls are submitted through ApiCalls. */
public interface ApiProvider {
    HouseholdApi household();
    ProfileApi profile();
    RecipeApi recipe();
    RecipeCatalogApi recipeCatalog();
    PersonalRecipeApi personalRecipes();
    RecipeFavoriteApi recipeFavorites();
    InventoryApi inventory();
    PlanningApi planning();
    RecordsApi records();

    NutritionApi nutrition();

    MediaApi media();
}
