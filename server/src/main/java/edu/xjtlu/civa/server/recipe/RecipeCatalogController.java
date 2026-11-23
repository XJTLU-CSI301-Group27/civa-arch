package edu.xjtlu.civa.server.recipe;

import edu.xjtlu.civa.server.api.RecipeCatalogApi;
import edu.xjtlu.civa.server.api.model.CatalogRecipe;
import edu.xjtlu.civa.server.api.model.CatalogRecipePage;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Read-only public recipe catalogue; every operation requires an authenticated session. */
@RestController
class RecipeCatalogController implements RecipeCatalogApi {
    private final RecipeCatalogService catalog;

    RecipeCatalogController(RecipeCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override public ResponseEntity<CatalogRecipePage> listCatalogRecipes(String q, String category, String tag,
            Boolean highProtein, Integer page, Integer size) {
        return ResponseEntity.ok(catalog.list(q, category, tag, Boolean.TRUE.equals(highProtein), page, size));
    }

    @Override public ResponseEntity<CatalogRecipe> getCatalogRecipe(UUID recipeId) {
        return ResponseEntity.ok(catalog.get(recipeId));
    }
}
