package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.PersonalRecipeApi;
import edu.xjtlu.civa.server.api.model.ArchivePersonalRecipeRequest;
import edu.xjtlu.civa.server.api.model.CreatePersonalRecipeRequest;
import edu.xjtlu.civa.server.api.model.PersonalRecipe;
import edu.xjtlu.civa.server.api.model.PersonalRecipePage;
import edu.xjtlu.civa.server.api.model.UpdatePersonalRecipeRequest;
import edu.xjtlu.civa.server.application.CanonicalRequestDigest;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PersonalRecipeController implements PersonalRecipeApi {
    private final PersonalRecipeService recipes;
    private final CanonicalRequestDigest digest;
    private final ObjectMapper json;
    private final JsonNode schemas;

    PersonalRecipeController(PersonalRecipeService recipes, CanonicalRequestDigest digest, ObjectMapper json,
                             JsonNode schemas) {
        this.recipes = recipes;
        this.digest = digest;
        this.json = json;
        this.schemas = schemas;
    }

    @Override public ResponseEntity<PersonalRecipePage> listPersonalRecipes(String q, String category,
            Boolean archived, Integer page, Integer size) {
        return ResponseEntity.ok(recipes.list(actor(), q, category, archived, page, size));
    }

    @Override public ResponseEntity<PersonalRecipe> getPersonalRecipe(UUID recipeId) {
        return ResponseEntity.ok(recipes.get(actor(), recipeId));
    }

    @Override public ResponseEntity<PersonalRecipe> createPersonalRecipe(UUID idempotencyKey,
            CreatePersonalRecipeRequest body) {
        IdempotencyExecutor.Result result = recipes.create(actor(), idempotencyKey,
                hash("/api/v1/me/recipes", body, "CreatePersonalRecipeRequest"), body);
        try {
            return ResponseEntity.status(result.status).body(json.readValue(result.body, PersonalRecipe.class));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid stored personal recipe", invalid);
        }
    }

    @Override public ResponseEntity<PersonalRecipe> updatePersonalRecipe(UUID recipeId,
            UpdatePersonalRecipeRequest body) {
        return ResponseEntity.ok(recipes.update(actor(), recipeId, body));
    }

    @Override public ResponseEntity<PersonalRecipe> setPersonalRecipeArchived(UUID recipeId,
            ArchivePersonalRecipeRequest body) {
        return ResponseEntity.ok(recipes.archive(actor(), recipeId, body));
    }

    @Override public ResponseEntity<Void> deletePersonalRecipe(UUID recipeId) {
        recipes.delete(actor(), recipeId);
        return ResponseEntity.noContent().build();
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
