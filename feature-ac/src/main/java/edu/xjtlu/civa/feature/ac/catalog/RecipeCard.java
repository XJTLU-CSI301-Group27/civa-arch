package edu.xjtlu.civa.feature.ac.catalog;

import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.PersonalRecipe;
import edu.xjtlu.civa.api.model.RecipeFavorite;
import edu.xjtlu.civa.api.model.RecipeFavoriteKind;
import java.util.ArrayList;
import java.util.List;

/** One card in the recipe grid, independent of the recipe space it came from. */
record RecipeCard(String id, String name, String emoji, String meta, String tags, Kind kind) {
    enum Kind { CATALOG, PERSONAL }

    static RecipeCards cards() { return new RecipeCards(); }

    static final class RecipeCards {
        List<RecipeCard> fromCatalog(List<CatalogRecipe> rows) {
            List<RecipeCard> cards = new ArrayList<>();
            for (CatalogRecipe row : rows) cards.add(new RecipeCard(row.getId() == null ? null : row.getId().toString(),
                    row.getName(), row.getEmoji(), meta(row.getCategory(), row.getKcal(), row.getEstimatedMinutes()),
                    row.getFlavors().isEmpty() ? row.getCuisine() : String.join(" · ", row.getFlavors()),
                    Kind.CATALOG));
            return cards;
        }

        List<RecipeCard> fromPersonal(List<PersonalRecipe> rows) {
            List<RecipeCard> cards = new ArrayList<>();
            for (PersonalRecipe row : rows) cards.add(new RecipeCard(row.getId() == null ? null : row.getId().toString(),
                    row.getName(), row.getEmoji(), meta(row.getCategory(), row.getKcal(), row.getEstimatedMinutes()),
                    row.getFlavors().isEmpty() ? "我的菜谱" : String.join(" · ", row.getFlavors()),
                    Kind.PERSONAL));
            return cards;
        }

        List<RecipeCard> fromFavorites(List<RecipeFavorite> rows) {
            List<RecipeCard> cards = new ArrayList<>();
            for (RecipeFavorite row : rows) {
                if (row.getKind() == RecipeFavoriteKind.HOUSEHOLD) continue;
                Kind kind = row.getKind() == RecipeFavoriteKind.PERSONAL ? Kind.PERSONAL : Kind.CATALOG;
                cards.add(new RecipeCard(row.getRecipeId() == null ? null : row.getRecipeId().toString(),
                        row.getName(), row.getEmoji(), "想吃", row.getCategory(), kind));
            }
            return cards;
        }

        private static String meta(String category, String kcal, Integer minutes) {
            List<String> parts = new ArrayList<>();
            if (category != null && !category.isEmpty()) parts.add(category);
            if (kcal != null) parts.add(kcal + " kcal");
            if (minutes != null) parts.add(minutes + " 分钟");
            return String.join(" · ", parts);
        }
    }

    static String personalKey(String id) { return "p:" + id; }

    static String idOf(String entityId) {
        return entityId != null && entityId.startsWith("p:") ? entityId.substring(2) : entityId;
    }

    static boolean isPersonal(String entityId) {
        return entityId != null && entityId.startsWith("p:");
    }
}
