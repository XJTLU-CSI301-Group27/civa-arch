package edu.xjtlu.civa.feature.bd;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
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

/** Activity-owned add-dish flow and personal local planning drafts. No protected API is called. */
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
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private BdNutritionClient.Call nutritionCall;
    private int nutritionRequest;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Set<String> seen = new HashSet<>();
    private BdRecipeCatalog catalog;
    private BdDraftStore store;
    private BdDraft data;
    private String owner, scope, operationId;
    private int generation, sequence, flowGeneration;
    private boolean closed;

    public BdHomeViewModel(@NonNull Application application) { super(application); }

    void initialize(String nextOwner, Bundle restored) {
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
        io.execute(() -> {
            try {
                BdDraftStore loadedStore = new BdDraftStore(getApplication(), loadingScope);
                BdDraft loaded = loadedStore.load();
                BdRecipeCatalog loadedCatalog;
                try (InputStreamReader reader = new InputStreamReader(getApplication().getAssets().open("recipes/catalog.json"), StandardCharsets.UTF_8)) {
                    loadedCatalog = new BdRecipeCatalog(reader);
                }
                main.post(() -> {
                    if (closed || generation != loadingGeneration) return;
                    store = loadedStore; data = loaded; catalog = loadedCatalog; ready = true;
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
        BdNutritionClient.Call request = new BdNutritionClient.Call(); nutritionCall = request;
        int requestId = ++nutritionRequest, targetGeneration = generation;
        String targetId = dish.id;
        nutritionLoading = true; changed();
        network.execute(() -> {
            try {
                BdNutrition result = request.execute(getApplication(), dish);
                main.post(() -> {
                    if (closed || generation != targetGeneration || requestId != nutritionRequest || phase != Phase.NUTRITION || !targetId.equals(selectedDishId)) return;
                    nutritionCall = null; nutritionLoading = false; nutrition = result;
                    commitDetail(data.withNutrition(targetDate, targetMeal, targetId, result), Phase.NUTRITION, null, true);
                });
            } catch (Exception error) {
                BdNutritionClient.Problem problem = error instanceof BdNutritionClient.Failure
                        ? ((BdNutritionClient.Failure) error).problem : BdNutritionClient.Problem.NETWORK;
                main.post(() -> {
                    if (closed || generation != targetGeneration || requestId != nutritionRequest || phase != Phase.NUTRITION) return;
                    nutritionCall = null; nutritionLoading = false;
                    int text = problem == BdNutritionClient.Problem.AUTHORIZATION ? R.string.bd_detail_ai_auth
                            : problem == BdNutritionClient.Problem.BALANCE ? R.string.bd_detail_ai_balance
                            : problem == BdNutritionClient.Problem.RATE_LIMIT ? R.string.bd_detail_ai_rate
                            : problem == BdNutritionClient.Problem.CONFIGURATION ? R.string.bd_detail_ai_config
                            : problem == BdNutritionClient.Problem.INVALID_RESPONSE ? R.string.bd_detail_ai_invalid : R.string.bd_detail_ai_network;
                    message = getApplication().getString(text); changed();
                });
            }
        });
    }

    private void cancelNutrition() {
        nutritionRequest++; nutritionLoading = false;
        if (nutritionCall != null) { nutritionCall.cancel(); nutritionCall = null; }
    }

    private void discardDraftPhoto() {
        String previous = draftImage; draftImage = null;
        if (previous != null) io.execute(() -> BdPhotoFiles.discard(getApplication(), previous));
    }

    @Override protected void onCleared() { closed = true; cancelNutrition(); io.shutdown(); network.shutdownNow(); }
}
