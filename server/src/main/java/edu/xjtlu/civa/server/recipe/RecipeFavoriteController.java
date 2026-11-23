package edu.xjtlu.civa.server.recipe;

import edu.xjtlu.civa.server.api.RecipeFavoriteApi;
import edu.xjtlu.civa.server.api.model.RecipeFavorite;
import edu.xjtlu.civa.server.api.model.RecipeFavoriteKind;
import edu.xjtlu.civa.server.api.model.RecipeFavoritePage;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RecipeFavoriteController implements RecipeFavoriteApi {
    private final RecipeFavoriteService favorites;

    RecipeFavoriteController(RecipeFavoriteService favorites) {
        this.favorites = favorites;
    }

    @Override public ResponseEntity<RecipeFavoritePage> listRecipeFavorites(RecipeFavoriteKind kind,
            Integer page, Integer size) {
        return ResponseEntity.ok(favorites.list(actor(), kind, page, size));
    }

    @Override public ResponseEntity<RecipeFavorite> getRecipeFavorite(RecipeFavoriteKind kind, UUID recipeId) {
        return ResponseEntity.ok(favorites.get(actor(), kind, recipeId));
    }

    @Override public ResponseEntity<Void> addRecipeFavorite(RecipeFavoriteKind kind, UUID recipeId) {
        favorites.add(actor(), kind, recipeId);
        return ResponseEntity.noContent().build();
    }

    @Override public ResponseEntity<Void> removeRecipeFavorite(RecipeFavoriteKind kind, UUID recipeId) {
        favorites.remove(actor(), kind, recipeId);
        return ResponseEntity.noContent().build();
    }

    private static UUID actor() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
