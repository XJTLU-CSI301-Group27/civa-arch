package edu.xjtlu.civa.feature.bd;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import edu.xjtlu.civa.api.model.NutritionEstimate;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.data.ApiCalls;
import edu.xjtlu.civa.core.data.ApiProvider;
import edu.xjtlu.civa.core.ui.ApiError;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.CatalogRecipePage;
import java.io.StringReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import retrofit2.Call;

/** Activity-owned add-dish flow and personal local planning drafts; the nutrition estimate is the only server call. */
public final class BdHomeViewModel extends AndroidViewModel {
    enum Phase { NONE, CHOOSE, MANUAL, PREFERENCES, RECOMMENDATION, DISH, RECIPE, MISSING_RECIPE, EDIT_RECIPE, NUTRITION }
    final MutableLiveData<Integer> changes = new MutableLiveData<>(0);
    LocalDate date = LocalDate.now();
    LocalDate targetDate;
    String targetMeal = "BREAKFAST";
    Phase phase = Phase.NONE;
    String manualName = "", draftImage, message;
    BdRecipeCatalog.Taste spicy = BdRecipeCatalog.Taste.ANY, meat = BdRecipeCatalog.Taste.ANY;
    boolean quickOnly, ready, saving, importing;
    int planVersion;
    BdRecipeCatalog.Recipe candidate;
    String selectedDishId, recipeIngredientsDraft = "", recipeStepsDraft = "";
    BdNutrition nutrition;
    boolean nutritionLoading;
    private final ApiProvider api;
    private final ApiCalls calls;
    private Call<NutritionEstimate> nutritionCall;
    private int nutritionRequest;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Set<String> seen = new HashSet<>();
    private BdRecipeCatalog catalog;
    private static final String CATALOG_CACHE = "bd_catalog_cache_v2", CATALOG_KEY = "recipes";
    private boolean catalogRefreshStarted;
    private BdDraftStore store;
    private BdDraft data;
    private String owner, scope, operationId;
    private int generation, sequence, flowGeneration;
    private boolean closed;

    public BdHomeViewModel(@NonNull Application application) { this(application, null, null); }

    BdHomeViewModel(@NonNull Application application, @Nullable ApiProvider api, @Nullable ApiCalls calls) {
        super(application);
        this.api = api;
        this.calls = calls;
    }

    void initialize(String nextOwner, Bundle restored, String... legacyOwners) {
        if (nextOwner.equals(owner)) return;
        if (owner != null) discardDraftPhoto();
        cancelNutrition();
        selectedDishId = null; nutrition = null;
        recipeIngredientsDraft = ""; recipeStepsDraft = "";
        owner = nextOwner;
        scope = BdDraft.scopeHash(nextOwner);
        int loadingGeneration = ++generation;
        ready = false; saving = false; importing = false;
        phase = Phase.NONE; manualName = ""; draftImage = null; candidate = null; message = null;
        spicy = BdRecipeCatalog.Taste.ANY; meat = BdRecipeCatalog.Taste.ANY; quickOnly = false; seen.clear(); operationId = null; targetDate = null;
        date = LocalDate.now(); data = BdDraft.empty(scope);
        if (restored != null && scope.equals(restored.getString("home.scope"))) restore(restored);
        String loadingScope = scope;
        List<String> legacyScopes = new ArrayList<>();
        for (String legacyOwner : legacyOwners) legacyScopes.add(BdDraft.scopeHash(legacyOwner));
        io.execute(() -> {
            try {
                BdDraftStore loadedStore = new BdDraftStore(getApplication(), loadingScope);
                BdDraft loaded = loadedStore.loadMigrating(legacyScopes);
                BdRecipeCatalog loadedCatalog;
                try (InputStreamReader reader = new InputStreamReader(getApplication().getAssets().open("recipes/catalog.json"), StandardCharsets.UTF_8)) {
                    loadedCatalog = new BdRecipeCatalog(reader);
                }
                String cached = getApplication().getSharedPreferences(CATALOG_CACHE, android.content.Context.MODE_PRIVATE)
                        .getString(CATALOG_KEY, null);
                if (cached != null) {
                    try {
                        loadedCatalog = new BdRecipeCatalog(new StringReader(cached));
                    } catch (RuntimeException ignored) {
                        // A corrupt cache falls back to the bundled cards.
                    }
                }
                final BdRecipeCatalog resolvedCatalog = loadedCatalog;
                main.post(() -> {
                    if (closed || generation != loadingGeneration) return;
                    store = loadedStore; data = loaded; catalog = resolvedCatalog; ready = true;
                    if (candidate != null) candidate = catalog.byId(candidate.id);
                    if (operationId != null) {
                        for (List<BdDraft.Dish> list : data.meals.values()) {
                            for (BdDraft.Dish dish : list) if (operationId.equals(dish.id)) { phase = Phase.NONE; draftImage = null; }
                        }
                    }
                    if (phase == Phase.RECOMMENDATION && candidate == null) phase = Phase.PREFERENCES;
                    if (detailFlow() && selectedDish() == null) phase = Phase.NONE;
                    planVersion++; changed();
                    if (phase == Phase.NUTRITION) showNutrition();
                    refreshCatalog(loadingGeneration);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (closed || generation != loadingGeneration) return;
                    message = getApplication().getString(R.string.bd_dish_load_error);
                    changed();
                });
            }
        });
    }

    List<BdDraft.Dish> dishes(String meal) { return data == null ? java.util.Collections.emptyList() : data.dishes(date, meal); }
    void selectDate(LocalDate value) { date = value; changed(); }

    void begin(String meal) {
        if (!ready || saving) return;
        discardDraftPhoto();
        targetMeal = meal; targetDate = date;
        operationId = UUID.randomUUID().toString();
        manualName = ""; draftImage = null; candidate = null; message = null;
        flowGeneration++; seen.clear(); phase = Phase.CHOOSE; changed();
    }

    void manual() { message = null; phase = Phase.MANUAL; changed(); }
    void preferences() { message = null; phase = Phase.PREFERENCES; changed(); }

    void back() {
        if (saving || importing) return;
        message = null;
        if (detailFlow()) {
            cancelNutrition(); phase = Phase.NONE; flowGeneration++; changed(); return;
        }
        if (phase == Phase.MANUAL || phase == Phase.PREFERENCES) phase = Phase.CHOOSE;
        else { phase = Phase.NONE; discardDraftPhoto(); manualName = ""; flowGeneration++; }
        changed();
    }

    int matchingCount() { return catalog == null ? 0 : catalog.recommendationPool(spicy, meat, quickOnly).size(); }
    boolean canReroll() { return matchingCount() > 1; }

    void drawFirst() { seen.clear(); candidate = null; drawNext(); }
    void drawNext() {
        if (!ready || saving) return;
        List<BdRecipeCatalog.Recipe> pool = catalog.recommendationPool(spicy, meat, quickOnly);
        BdRecipeCatalog.Recipe next = BdRecipeCatalog.draw(pool, seen, candidate == null ? null : candidate.id, random);
        if (next == null) { message = getApplication().getString(R.string.bd_dish_no_match); changed(); return; }
        candidate = next; phase = Phase.RECOMMENDATION; message = null; changed();
    }

    void saveManual() {
        if (!ready || saving || importing) return;
        if (manualName.trim().isEmpty()) { message = getApplication().getString(R.string.bd_dish_name_required); changed(); return; }
        try { save(BdDraft.Dish.custom(operationId, manualName, draftImage)); }
        catch (IllegalArgumentException error) { message = getApplication().getString(R.string.bd_dish_name_required); changed(); }
    }

    void saveCandidate() {
        if (ready && !saving && candidate != null) save(BdDraft.Dish.recipe(operationId, candidate));
    }

    private void save(BdDraft.Dish dish) {
        BdDraft updated = data.withDish(targetDate, targetMeal, dish);
        BdDraftStore targetStore = store;
        int savingGeneration = generation;
        saving = true; message = null; changed();
        io.execute(() -> {
            try {
                targetStore.save(updated);
                main.post(() -> {
                    if (closed || generation != savingGeneration) return;
                    data = updated; saving = false; planVersion++;
                    // Ownership transfers to the saved dish; closing the flow must not delete its image.
                    draftImage = null; manualName = ""; phase = Phase.NONE; flowGeneration++; changed();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (closed || generation != savingGeneration) return;
                    saving = false; message = getApplication().getString(R.string.bd_dish_save_error); changed();
                });
            }
        });
    }

    void importPhoto(Uri uri) {
        if (uri == null || !ready || phase != Phase.MANUAL || saving || importing) return;
        int photoGeneration = generation, photoFlow = flowGeneration;
        String photoScope = scope;
        importing = true; message = null; changed();
        io.execute(() -> {
            try {
                String image = BdPhotoFiles.importPhoto(getApplication(), uri, photoScope);
                main.post(() -> {
                    if (closed || generation != photoGeneration || flowGeneration != photoFlow) {
                        BdPhotoFiles.discard(getApplication(), image); return;
                    }
                    String previous = draftImage; draftImage = image; importing = false; changed();
                    if (previous != null) io.execute(() -> BdPhotoFiles.discard(getApplication(), previous));
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (closed || generation != photoGeneration || flowGeneration != photoFlow) return;
                    importing = false; message = getApplication().getString(R.string.bd_dish_photo_error); changed();
                });
            }
        });
    }

    void clearMessage() { message = null; changed(); }
    void changed() { changes.setValue(++sequence); }
    boolean busy() { return saving || importing; }

    void saveState(Bundle out) {
        out.putString("home.dishId", selectedDishId);
        out.putString("home.recipeIngredients", recipeIngredientsDraft);
        out.putString("home.recipeSteps", recipeStepsDraft);
        out.putString("home.scope", scope);
        out.putString("home.date", date.toString());
        out.putString("home.phase", phase.name());
        out.putString("home.targetDate", targetDate == null ? date.toString() : targetDate.toString());
        out.putString("home.meal", targetMeal);
        out.putString("home.operation", operationId);
        out.putString("home.name", manualName);
        out.putString("home.image", draftImage);
        out.putString("home.spicyPreference", spicy.name()); out.putString("home.meatPreference", meat.name()); out.putBoolean("home.quick", quickOnly);
        out.putString("home.candidate", candidate == null ? null : candidate.id);
        out.putStringArrayList("home.seen", new ArrayList<>(seen));
    }

    private void restore(Bundle saved) {
        try {
            selectedDishId = saved.getString("home.dishId");
            recipeIngredientsDraft = saved.getString("home.recipeIngredients", "");
            recipeStepsDraft = saved.getString("home.recipeSteps", "");
            date = LocalDate.parse(saved.getString("home.date", LocalDate.now().toString()));
            targetDate = LocalDate.parse(saved.getString("home.targetDate", date.toString()));
            phase = Phase.valueOf(saved.getString("home.phase", "NONE"));
            targetMeal = saved.getString("home.meal", "BREAKFAST");
            BdDraft.key(targetDate, targetMeal);
            operationId = saved.getString("home.operation", UUID.randomUUID().toString());
            manualName = saved.getString("home.name", ""); draftImage = saved.getString("home.image");
            spicy = BdRecipeCatalog.Taste.restore(saved.getString("home.spicyPreference"),
                    saved.containsKey("home.spicy") ? saved.getBoolean("home.spicy") : null);
            meat = BdRecipeCatalog.Taste.restore(saved.getString("home.meatPreference"),
                    saved.containsKey("home.meat") ? saved.getBoolean("home.meat") : null);
            quickOnly = saved.getBoolean("home.quick");
            String candidateId = saved.getString("home.candidate");
            if (candidateId != null) { candidate = new BdRecipeCatalog.Recipe(); candidate.id = candidateId; }
            ArrayList<String> ids = saved.getStringArrayList("home.seen");
            if (ids != null) seen.addAll(ids);
        } catch (IllegalArgumentException error) {
            phase = Phase.NONE; date = LocalDate.now(); draftImage = null;
        }
    }


    boolean detailFlow() {
        return phase == Phase.DISH || phase == Phase.RECIPE || phase == Phase.MISSING_RECIPE
                || phase == Phase.EDIT_RECIPE || phase == Phase.NUTRITION;
    }

    BdDraft.Dish selectedDish() {
        return data == null || targetDate == null || selectedDishId == null ? null : data.find(targetDate, targetMeal, selectedDishId);
    }

    void openDish(String meal, String id) {
        if (!ready || busy() || data.find(date, meal, id) == null) return;
        discardDraftPhoto(); cancelNutrition(); flowGeneration++;
        targetDate = date; targetMeal = meal; selectedDishId = id; operationId = null;
        recipeIngredientsDraft = ""; recipeStepsDraft = ""; nutrition = null;
        message = null; phase = Phase.DISH; changed();
    }

    void showRecipe() {
        BdDraft.Dish dish = selectedDish();
        if (dish == null || busy()) return;
        message = null; phase = dish.steps.isEmpty() ? Phase.MISSING_RECIPE : Phase.RECIPE; changed();
    }

    void editRecipe() {
        BdDraft.Dish dish = selectedDish();
        if (dish == null || busy()) return;
        recipeIngredientsDraft = android.text.TextUtils.join("\n", dish.ingredients);
        recipeStepsDraft = android.text.TextUtils.join("\n", dish.steps);
        message = null; phase = Phase.EDIT_RECIPE; changed();
    }

    void saveRecipe() {
        if (!ready || busy() || selectedDish() == null) return;
        try {
            BdDraft updated = data.withRecipe(targetDate, targetMeal, selectedDishId, recipeIngredientsDraft, recipeStepsDraft);
            nutrition = null; commitDetail(updated, Phase.RECIPE, null, false);
        } catch (IllegalArgumentException error) { message = getApplication().getString(R.string.bd_detail_recipe_required); changed(); }
    }

    void removeDish() {
        BdDraft.Dish dish = selectedDish();
        if (!ready || busy() || dish == null) return;
        commitDetail(data.withoutDish(targetDate, targetMeal, dish.id), Phase.NONE, dish.image, false);
    }

    private void commitDetail(BdDraft updated, Phase success, String abandonedPhoto, boolean estimateCache) {
        BdDraftStore targetStore = store; int targetGeneration = generation;
        saving = true; message = null; changed();
        io.execute(() -> {
            try {
                targetStore.save(updated);
                main.post(() -> {
                    if (closed || generation != targetGeneration) return;
                    data = updated; saving = false; planVersion++; phase = success; changed();
                    if (abandonedPhoto != null && !updated.usesImage(abandonedPhoto)) io.execute(() -> BdPhotoFiles.discard(getApplication(), abandonedPhoto));
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (closed || generation != targetGeneration) return;
                    saving = false; message = getApplication().getString(estimateCache ? R.string.bd_detail_cache_error : R.string.bd_dish_save_error); changed();
                });
            }
        });
    }

    void showNutrition() {
        BdDraft.Dish dish = selectedDish();
        if (!ready || busy() || dish == null || nutritionLoading) return;
        phase = Phase.NUTRITION; message = null;
        nutrition = dish.nutrition != null && dish.nutrition.validFor(dish) ? dish.nutrition : null;
        changed();
        if (nutrition != null) return;
        if (api == null || calls == null) {
            message = getApplication().getString(R.string.bd_detail_ai_config);
            changed();
            return;
        }
        int requestId = ++nutritionRequest, targetGeneration = generation;
        String targetId = dish.id;
        nutritionLoading = true; changed();
        Call<NutritionEstimate> call = api.nutrition().estimateNutrition(BdNutritionClient.request(dish));
        nutritionCall = call;
        calls.enqueue(call, new ApiCallback<NutritionEstimate>() {
            @Override public void onSuccess(NutritionEstimate value) {
                if (closed || generation != targetGeneration || requestId != nutritionRequest
                        || phase != Phase.NUTRITION || !targetId.equals(selectedDishId)) return;
                nutritionCall = null; nutritionLoading = false;
                BdNutrition result;
                try {
                    result = BdNutritionClient.map(dish, value);
                } catch (RuntimeException invalid) {
                    nutrition = null;
                    message = getApplication().getString(R.string.bd_detail_ai_invalid);
                    changed();
                    return;
                }
                nutrition = result;
                commitDetail(data.withNutrition(targetDate, targetMeal, targetId, result), Phase.NUTRITION, null, true);
            }

            @Override public void onError(ApiError error) {
                if (closed || generation != targetGeneration || requestId != nutritionRequest || phase != Phase.NUTRITION) return;
                nutritionCall = null; nutritionLoading = false;
                message = getApplication().getString(textFor(BdNutritionClient.problemFor(error == null ? null : error.code)));
                changed();
            }
        });
    }

    private static int textFor(BdNutritionClient.Problem problem) {
        switch (problem) {
            case AUTHORIZATION: return R.string.bd_detail_ai_auth;
            case BALANCE: return R.string.bd_detail_ai_balance;
            case RATE_LIMIT: return R.string.bd_detail_ai_rate;
            case CONFIGURATION: return R.string.bd_detail_ai_config;
            case INVALID_RESPONSE: return R.string.bd_detail_ai_invalid;
            default: return R.string.bd_detail_ai_network;
        }
    }

    private void cancelNutrition() {
        nutritionRequest++; nutritionLoading = false;
        if (nutritionCall != null) { nutritionCall.cancel(); nutritionCall = null; }
    }

    private void discardDraftPhoto() {
        String previous = draftImage; draftImage = null;
        if (previous != null) io.execute(() -> BdPhotoFiles.discard(getApplication(), previous));
    }

    @Override protected void onCleared() { closed = true; cancelNutrition(); io.shutdown(); }

    /** Refreshes the prompt pool from the public catalogue; keeps the cached cards on failure. */
    private void refreshCatalog(int targetGeneration) {
        if (catalogRefreshStarted || api == null || calls == null) return;
        catalogRefreshStarted = true;
        fetchCatalogPage(0, new ArrayList<>(), targetGeneration);
    }

    private void fetchCatalogPage(int page, List<CatalogRecipe> collected, int targetGeneration) {
        if (closed || generation != targetGeneration) return;
        calls.enqueue(api.recipeCatalog().listCatalogRecipes(null, null, null, false, page, 100),
                new ApiCallback<CatalogRecipePage>() {
                    @Override public void onSuccess(CatalogRecipePage value) {
                        if (closed || generation != targetGeneration) return;
                        if (value == null || value.getItems() == null) return;
                        collected.addAll(value.getItems());
                        long total = value.getTotal() == null ? collected.size() : value.getTotal();
                        if (collected.size() < total && page < 20) {
                            fetchCatalogPage(page + 1, collected, targetGeneration);
                            return;
                        }
                        List<BdRecipeCatalog.Recipe> mapped = BdRecipeCatalog.fromCatalog(collected);
                        if (mapped.isEmpty()) return;
                        try {
                            catalog = new BdRecipeCatalog(mapped);
                        } catch (RuntimeException invalid) {
                            return;
                        }
                        getApplication().getSharedPreferences(CATALOG_CACHE, android.content.Context.MODE_PRIVATE)
                                .edit().putString(CATALOG_KEY, new Gson().toJson(mapped)).apply();
                        if (candidate != null) candidate = catalog.byId(candidate.id);
                        planVersion++;
                        changed();
                    }

                    @Override public void onError(ApiError error) {
                        // Keep the cached or bundled pool; a failed refresh must not block the flow.
                    }
                });
    }
}
