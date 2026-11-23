package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.RecipeApi;
import edu.xjtlu.civa.server.api.model.ArchiveRecipeRequest;
import edu.xjtlu.civa.server.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.server.api.model.Recipe;
import edu.xjtlu.civa.server.api.model.RecipePage;
import edu.xjtlu.civa.server.api.model.UpdateRecipeRequest;
import edu.xjtlu.civa.server.application.CanonicalRequestDigest;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HouseholdRecipeController implements RecipeApi {
    private final HouseholdRecipeService recipes;
    private final CanonicalRequestDigest digest;
    private final ObjectMapper json;
    private final JsonNode schemas;

    HouseholdRecipeController(HouseholdRecipeService recipes, CanonicalRequestDigest digest, ObjectMapper json,
                              JsonNode schemas) {
        this.recipes = recipes;
        this.digest = digest;
        this.json = json;
        this.schemas = schemas;
    }

    @Override public ResponseEntity<RecipePage> listRecipes(UUID householdId, String q, String tag, UUID ingredientId,
            Boolean archived, Integer page, Integer size) {
        return ResponseEntity.ok(recipes.list(actor(), householdId, q, tag, ingredientId, archived, page, size));
    }

    @Override public ResponseEntity<Recipe> getRecipe(UUID householdId, UUID recipeId) {
        return ResponseEntity.ok(recipes.get(actor(), householdId, recipeId));
    }

    @Override public ResponseEntity<Recipe> createRecipe(UUID householdId, UUID idempotencyKey, CreateRecipeRequest body) {
        IdempotencyExecutor.Result result = recipes.create(actor(), householdId, idempotencyKey,
                hash("/api/v1/households/" + householdId + "/recipes", body, "CreateRecipeRequest"), body);
        try {
            return ResponseEntity.status(result.status).body(json.readValue(result.body, Recipe.class));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid stored recipe", invalid);
        }
    }

    @Override public ResponseEntity<Recipe> updateRecipe(UUID householdId, UUID recipeId, UpdateRecipeRequest body) {
        return ResponseEntity.ok(recipes.update(actor(), householdId, recipeId, body));
    }

    @Override public ResponseEntity<Recipe> setRecipeArchived(UUID householdId, UUID recipeId, ArchiveRecipeRequest body) {
        return ResponseEntity.ok(recipes.archive(actor(), householdId, recipeId, body));
    }

    @Override public ResponseEntity<Void> addWishlist(UUID recipeId) {
        recipes.addWishlist(actor(), recipeId);
        return ResponseEntity.noContent().build();
    }

    @Override public ResponseEntity<Void> removeWishlist(UUID recipeId) {
        recipes.removeWishlist(actor(), recipeId);
        return ResponseEntity.noContent().build();
    }

    @Override public ResponseEntity<RecipePage> listWishlist(Integer page, Integer size) {
        return ResponseEntity.ok(recipes.listWishlist(actor(), page, size));
    }

    private byte[] hash(String path, Object body, String schema) {
        try {
            return digest.digestJson("POST", path, json.writeValueAsString(body), schemas.get(schema));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid request", error);
        }
    }

    private static UUID actor() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
