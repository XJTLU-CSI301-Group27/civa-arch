package edu.xjtlu.civa.feature.ac.catalog;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.CatalogRecipePage;
import edu.xjtlu.civa.api.model.PersonalRecipe;

/** Read-only cache of catalogue and personal recipe responses so the tab opens offline. */
final class CatalogCache {
    private static final String PREFS = "ac_catalog_cache_v1";
    private static final String LIST_KEY = "list";
    private static final Gson GSON = new Gson();

    private CatalogCache() { }

    static CatalogRecipePage loadList(Context context) {
        return read(context, LIST_KEY, CatalogRecipePage.class);
    }

    static void saveList(Context context, CatalogRecipePage page) {
        write(context, LIST_KEY, page);
    }

    static CatalogRecipe loadDetail(Context context, String id) {
        return read(context, "detail:" + id, CatalogRecipe.class);
    }

    static void saveDetail(Context context, CatalogRecipe recipe) {
        write(context, "detail:" + recipe.getId(), recipe);
    }

    static PersonalRecipe loadPersonal(Context context, String id) {
        return read(context, "personal:" + id, PersonalRecipe.class);
    }

    static void savePersonal(Context context, PersonalRecipe recipe) {
        write(context, "personal:" + recipe.getId(), recipe);
    }

    static void clearPersonal(Context context, String id) {
        prefs(context).edit().remove("personal:" + id).apply();
    }

    private static <T> T read(Context context, String key, Class<T> type) {
        String value = prefs(context).getString(key, null);
        if (value == null) return null;
        try {
            return GSON.fromJson(value, type);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static void write(Context context, String key, Object value) {
        try {
            prefs(context).edit().putString(key, GSON.toJson(value)).apply();
        } catch (RuntimeException ignored) {
            // A cache write must never break the visible response.
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
