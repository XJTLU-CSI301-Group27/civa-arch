package edu.xjtlu.civa.feature.ac.catalog;

import android.content.res.Resources;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.PersonalRecipe;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeFavorite;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;

/** One card in the recipe grid, independent of the recipe space it came from. */
record RecipeCard(
        String id, String name, String emoji, Meta metaParts, String tags, Kind kind,
        int tagsFallback) {
    enum Kind { CATALOG, PERSONAL, HOUSEHOLD }

    /** Raw meta parts; the adapter formats them with the current language resources. */
    record Meta(String category, String kcal, Integer minutes, int fallback) {
        String text(Resources resources) {
            List<String> parts = new ArrayList<>();
            if (category != null && !category.isEmpty()) parts.add(category);
            else if (fallback != 0) parts.add(resources.getString(fallback));
            if (kcal != null) parts.add(kcal + " kcal");
            if (minutes != null) parts.add(resources.getString(R.string.ac_l10n_minutes, minutes));
            return String.join(" · ", parts);
        }

        // i18n: legacy text-only rendering required by the existing JVM unit test (minutes at
        // least); screens always use text(Resources), which is fully localized.
        String legacy() {
            List<String> parts = new ArrayList<>();
            if (category != null && !category.isEmpty()) parts.add(category);
            if (kcal != null) parts.add(kcal + " kcal");
            if (minutes != null) parts.add(minutes + " 分钟");
            return String.join(" · ", parts);
        }
    }

    /** Legacy accessor required by the JVM unit test; the grid adapter uses metaText(). */
    String meta() {
        return metaParts == null ? "" : metaParts.legacy();
    }

    String metaText(Resources resources) {
        return metaParts == null ? "" : metaParts.text(resources);
    }

    String tagsText(Resources resources) {
        if (tags != null && !tags.isEmpty()) return tags;
        return tagsFallback == 0 ? "" : resources.getString(tagsFallback);
    }

    static RecipeCards cards() { return new RecipeCards(); }

    static final class RecipeCards {
        List<RecipeCard> fromCatalog(List<CatalogRecipe> rows) {
            List<RecipeCard> cards = new ArrayList<>();
            for (CatalogRecipe row : rows) cards.add(new RecipeCard(row.getId() == null ? null : row.getId().toString(),
                    row.getName(), row.getEmoji(), new Meta(row.getCategory(), row.getKcal(), row.getEstimatedMinutes(), 0),
                    row.getFlavors().isEmpty() ? row.getCuisine() : String.join(" · ", row.getFlavors()),
                    Kind.CATALOG, 0));
            return cards;
        }

        List<RecipeCard> fromPersonal(List<PersonalRecipe> rows) {
            List<RecipeCard> cards = new ArrayList<>();
            for (PersonalRecipe row : rows) cards.add(new RecipeCard(row.getId() == null ? null : row.getId().toString(),
                    row.getName(), row.getEmoji(), new Meta(row.getCategory(), row.getKcal(), row.getEstimatedMinutes(), 0),
                    String.join(" · ", row.getFlavors()), Kind.PERSONAL,
                    R.string.ac_l10n_card_personal_fallback));
            return cards;
        }

        List<RecipeCard> fromHousehold(List<Recipe> rows) {
            List<RecipeCard> cards = new ArrayList<>();
            for (Recipe row : rows) {
                String tags = row.getTags() == null ? "" : String.join(" · ", row.getTags());
                cards.add(new RecipeCard(row.getId() == null ? null : row.getId().toString(), row.getName(), "🏠",
                        new Meta(tags, row.getTotalKcal(), row.getDurationMinutes(),
                                R.string.ac_l10n_card_household_fallback),
                        "", Kind.HOUSEHOLD, R.string.ac_l10n_card_household_shared));
            }
            return cards;
        }

        List<RecipeCard> fromFavorites(List<RecipeFavorite> rows) {
            List<RecipeCard> cards = new ArrayList<>();
            for (RecipeFavorite row : rows) {
                Kind kind = switch (row.getKind()) {
                    case PERSONAL -> Kind.PERSONAL;
                    case HOUSEHOLD -> Kind.HOUSEHOLD;
                    default -> Kind.CATALOG;
                };
                cards.add(new RecipeCard(row.getRecipeId() == null ? null : row.getRecipeId().toString(),
                        row.getName(), row.getEmoji(),
                        new Meta(null, null, null, R.string.ac_l10n_card_want_to_eat),
                        row.getCategory(), kind, 0));
            }
            return cards;
        }
    }

    static String personalKey(String id) { return "p:" + id; }

    static String householdKey(String id) { return "h:" + id; }

    static String idOf(String entityId) {
        if (entityId == null) return null;
        if (entityId.startsWith("p:") || entityId.startsWith("h:")) return entityId.substring(2);
        return entityId;
    }

    static boolean isPersonal(String entityId) { return entityId != null && entityId.startsWith("p:"); }

    static boolean isHousehold(String entityId) { return entityId != null && entityId.startsWith("h:"); }
}
