package edu.xjtlu.civa.feature.ac.catalog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.CatalogRecipeIngredient;
import edu.xjtlu.civa.api.model.CreatePersonalRecipeRequest;
import edu.xjtlu.civa.api.model.ConfirmCookingRequest;
import edu.xjtlu.civa.api.model.CookingEvent;
import edu.xjtlu.civa.api.model.CookingPreview;
import edu.xjtlu.civa.api.model.CookingPreviewRequest;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.PersonalRecipe;
import edu.xjtlu.civa.api.model.PersonalRecipeIngredient;
import edu.xjtlu.civa.api.model.RecipeFavorite;
import edu.xjtlu.civa.api.model.RecipeFavoriteKind;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.data.MediaImages;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.Haptics;
import edu.xjtlu.civa.core.ui.Route;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.R;
import edu.xjtlu.civa.feature.ac.record.CookingIntent;
import edu.xjtlu.civa.feature.ac.record.IdempotentAction;
import edu.xjtlu.civa.feature.ac.record.RecordSubmissionViewModel;
import edu.xjtlu.civa.feature.ac.record.RecordSubmissions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import retrofit2.Call;

/** Recipe detail for public and personal recipes, with favourites and the personal actions. */
public final class RecipeCatalogDetailFragment extends Fragment {
    private static final String ARG_ID = "recipeId";

    private final RecipeViewCallbacks viewCallbacks = new RecipeViewCallbacks();

    private AcHost host;
    private String entityId, recipeId;
    private boolean personal;
    private Call<?> call;
    private PersonalRecipe personalRecipe;
    private CatalogRecipe catalogRecipe;
    private Recipe householdRecipe;
    private List<Ingredient> dictionary;
    private boolean household;
    private boolean favorite;
    private RecordSubmissionViewModel submissions;
    private CreateAttemptStore createStore;
    private CreateAttempt importAttempt;
    private boolean importing;

    public static RecipeCatalogDetailFragment create(@Nullable String recipeId) {
        RecipeCatalogDetailFragment fragment = new RecipeCatalogDetailFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ID, recipeId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.ac_fragment_catalog_detail, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        viewCallbacks.startView();
        host = (AcHost) requireActivity();
        submissions = new ViewModelProvider(requireActivity()).get(RecordSubmissionViewModel.class);
        try {
            createStore = new CreateAttemptStore(host.calls());
        } catch (RuntimeException unavailable) {
            createStore = null;
        }
        view.findViewById(R.id.ac_detail_close).setOnClickListener(ignored -> host.navigate(Route.RECIPES, null));
        entityId = getArguments() == null ? null : getArguments().getString(ARG_ID);
        if (entityId == null) {
            render(view, null);
            return;
        }
        personal = RecipeCard.isPersonal(entityId);
        household = RecipeCard.isHousehold(entityId);
        recipeId = RecipeCard.idOf(entityId);
        if (household) {
            loadHousehold(view);
        } else if (personal) {
            PersonalRecipe cached = CatalogCache.loadPersonal(requireContext(), recipeId);
            if (cached != null) {
                personalRecipe = cached;
                render(view, cached);
            }
            loadPersonal(view);
        } else {
            CatalogRecipe cached = CatalogCache.loadDetail(requireContext(), recipeId);
            if (cached != null) {
                catalogRecipe = cached;
                render(view, cached);
            }
            loadCatalog(view);
        }
        resumeCooking();
        resumeImportPending();
    }

    private void loadCatalog(View view) {
        call = host.api().recipeCatalog().getCatalogRecipe(UUID.fromString(recipeId));
        host.calls().enqueue((Call<CatalogRecipe>) call, viewCallbacks.bind(new ApiCallback<CatalogRecipe>() {
            @Override public void onSuccess(@Nullable CatalogRecipe value) {
                if (!isAdded()) return;
                if (value == null) return;
                catalogRecipe = value;
                CatalogCache.saveDetail(requireContext(), value);
                render(view, value);
            }

            @Override public void onError(ApiError error) { }
        }));
            }

    private void loadHousehold(View view) {
        java.util.UUID home = host.sessions() == null ? null : uuidOrNull(host.sessions().currentHouseholdId());
        if (home == null) {
            toast(R.string.ac_catalog_home_missing);
            return;
        }
        ensureDictionary(() -> {
            if (!isAdded()) return;
            call = host.api().recipe().getRecipe(home, UUID.fromString(recipeId));
            host.calls().enqueue((Call<Recipe>) call, viewCallbacks.bind(new ApiCallback<Recipe>() {
                @Override public void onSuccess(@Nullable Recipe value) {
                    if (!isAdded()) return;
                    if (value == null) return;
                    householdRecipe = value;
                    render(view, value);
                }

                @Override public void onError(ApiError error) {
                    if (!isAdded()) return;
                    toast(R.string.ac_catalog_error);
                }
            }));
        });
    }

    private void ensureDictionary(Runnable then) {
        if (dictionary != null) { then.run(); return; }
        loadDictionaryPage(0, new ArrayList<>(), then);
    }

    private void loadDictionaryPage(int page, List<Ingredient> collected, Runnable then) {
        host.calls().enqueue(host.api().inventory().listIngredients(null, page, 100),
                viewCallbacks.bind(new ApiCallback<edu.xjtlu.civa.api.model.IngredientPage>() {
                    @Override public void onSuccess(@Nullable edu.xjtlu.civa.api.model.IngredientPage value) {
                        if (!isAdded()) return;
                        if (value == null || value.getItems() == null) {
                            dictionary = List.of();
                            then.run();
                            return;
                        }
                        collected.addAll(value.getItems());
                        long total = value.getTotal() == null ? collected.size() : value.getTotal();
                        if (collected.size() < total && !value.getItems().isEmpty()) {
                            loadDictionaryPage(page + 1, collected, then);
                            return;
                        }
                        dictionary = collected.isEmpty() ? List.of() : List.copyOf(collected);
                        then.run();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        dictionary = List.of();
                        then.run();
                    }
                }));
    }

    private void loadPersonal(View view) {
        call = host.api().personalRecipes().getPersonalRecipe(UUID.fromString(recipeId));
        host.calls().enqueue((Call<PersonalRecipe>) call, viewCallbacks.bind(new ApiCallback<PersonalRecipe>() {
            @Override public void onSuccess(@Nullable PersonalRecipe value) {
                if (!isAdded()) return;
                if (value == null) return;
                personalRecipe = value;
                CatalogCache.savePersonal(requireContext(), value);
                render(view, value);
            }

            @Override public void onError(ApiError error) { }
        }));
    }

    private void render(View view, Object value) {
        TextView emoji = view.findViewById(R.id.ac_detail_emoji);
        ImageView photo = view.findViewById(R.id.ac_detail_image);
        UUID photoId = null;
        if (value instanceof PersonalRecipe personal) photoId = personal.getImageId();
        else if (value instanceof Recipe household) photoId = household.getImageId();
        if (photoId != null) {
            photo.setVisibility(View.VISIBLE);
            emoji.setVisibility(View.GONE);
            MediaImages.load(host.api(), host.calls(), photoId, photo);
        } else {
            photo.setVisibility(View.GONE);
            emoji.setVisibility(View.VISIBLE);
        }
        TextView name = view.findViewById(R.id.ac_detail_name);
        TextView meta = view.findViewById(R.id.ac_detail_meta);
        TextView tags = view.findViewById(R.id.ac_detail_tags);
        TextView description = view.findViewById(R.id.ac_detail_description);
        LinearLayout ingredients = view.findViewById(R.id.ac_detail_ingredients);
        TextView ingredientsHeader = view.findViewById(R.id.ac_detail_ingredients_header);
        LinearLayout recipeOnly = view.findViewById(R.id.ac_detail_recipe_only);
        TextView recipeOnlyHeader = view.findViewById(R.id.ac_detail_recipe_only_header);
        LinearLayout steps = view.findViewById(R.id.ac_detail_steps);
        LinearLayout nutrition = view.findViewById(R.id.ac_detail_nutrition);
        if (value == null) {
            name.setText(R.string.ac_catalog_missing);
            return;
        }
        String emojiText, title, category, cuisine, kcal, servingSize, protein, carbs, fat, protein100, carbs100, fat100, descriptionText;
        List<String> stepList, methods = List.of(), flavors = List.of(), meals = List.of();
        List<?> ingredientList;
        Integer minutes;
        if (value instanceof CatalogRecipe recipe) {
            emojiText = recipe.getEmoji(); title = recipe.getName(); category = recipe.getCategory();
            cuisine = recipe.getCuisine(); kcal = recipe.getKcal(); minutes = recipe.getEstimatedMinutes();
            descriptionText = recipe.getDescription();
            stepList = recipe.getSteps(); ingredientList = recipe.getIngredients();
            methods = recipe.getMethods(); flavors = recipe.getFlavors(); meals = recipe.getMealTypes();
            protein = recipe.getProtein(); carbs = recipe.getCarbs(); fat = recipe.getFat();
            protein100 = recipe.getProteinPer100g(); carbs100 = recipe.getCarbsPer100g(); fat100 = recipe.getFatPer100g();
            servingSize = null;
        } else if (value instanceof Recipe recipe) {
            emojiText = "🏠"; title = recipe.getName(); category = recipe.getTags() == null ? ""
                    : String.join(" · ", recipe.getTags());
            cuisine = ""; kcal = recipe.getTotalKcal(); minutes = recipe.getDurationMinutes();
            descriptionText = "";
            stepList = recipe.getSteps(); ingredientList = List.of();
            protein = null; carbs = null; fat = null; protein100 = null; carbs100 = null; fat100 = null;
            servingSize = null;
        } else if (value instanceof PersonalRecipe recipe) {
            emojiText = recipe.getEmoji(); title = recipe.getName(); category = recipe.getCategory();
            cuisine = ""; kcal = recipe.getKcal(); minutes = recipe.getEstimatedMinutes();
            descriptionText = recipe.getDescription();
            stepList = recipe.getSteps(); ingredientList = recipe.getIngredients();
            methods = recipe.getMethods(); flavors = recipe.getFlavors(); meals = recipe.getMealTypes();
            protein = recipe.getProtein(); carbs = recipe.getCarbs(); fat = recipe.getFat();
            protein100 = recipe.getProteinPer100g(); carbs100 = recipe.getCarbsPer100g(); fat100 = recipe.getFatPer100g();
            servingSize = recipe.getServingSize();
        } else {
            return;
        }
        emoji.setText(emojiText == null ? "🍽️" : emojiText);
        name.setText(title);
        List<String> metaParts = new ArrayList<>();
        if (has(category)) metaParts.add(category);
        if (has(cuisine)) metaParts.add(cuisine);
        if (kcal != null) metaParts.add(getString(R.string.ac_l10n_kcal_per_serving, kcal));
        if (minutes != null) metaParts.add(getString(R.string.ac_l10n_minutes, minutes));
        if (has(servingSize)) metaParts.add(servingSize);
        meta.setText(String.join(" · ", metaParts));
        List<String> tagParts = new ArrayList<>(methods);
        tagParts.addAll(flavors);
        tagParts.addAll(meals);
        tags.setText(String.join(" · ", tagParts));
        tags.setVisibility(tagParts.isEmpty() ? View.GONE : View.VISIBLE);
        boolean hasDescription = has(descriptionText);
        description.setText(hasDescription ? descriptionText : getString(R.string.ac_catalog_no_description));
        description.setVisibility(hasDescription ? View.VISIBLE : View.GONE);

        ingredients.removeAllViews();
        recipeOnly.removeAllViews();
        if (value instanceof Recipe recipe) {
            java.util.Map<UUID, String> names = HouseholdRecipes.names(dictionary == null ? List.of() : dictionary);
            List<String> tracked = HouseholdRecipes.trackedLines(getResources(), recipe, names);
            List<String> described = HouseholdRecipes.recipeOnlyLines(getResources(), recipe);
            ingredientsHeader.setVisibility(tracked.isEmpty() && described.isEmpty() ? View.VISIBLE : (tracked.isEmpty() ? View.GONE : View.VISIBLE));
            ingredients.setVisibility(tracked.isEmpty() && described.isEmpty() ? View.VISIBLE : (tracked.isEmpty() ? View.GONE : View.VISIBLE));
            for (String item : tracked) ingredients.addView(line(item, 15, R.color.ac_catalog_ink));
            if (tracked.isEmpty() && described.isEmpty()) {
                ingredients.addView(line(getString(R.string.ac_catalog_missing), 15, R.color.ac_catalog_ink));
            }
            recipeOnlyHeader.setVisibility(described.isEmpty() ? View.GONE : View.VISIBLE);
            recipeOnly.setVisibility(described.isEmpty() ? View.GONE : View.VISIBLE);
            for (String item : described) recipeOnly.addView(line(item, 15, R.color.ac_catalog_ink));
        } else {
            ingredientsHeader.setVisibility(View.VISIBLE);
            ingredients.setVisibility(View.VISIBLE);
            recipeOnlyHeader.setVisibility(View.GONE);
            recipeOnly.setVisibility(View.GONE);
            for (Object item : ingredientList) ingredients.addView(line(display(item), 15, R.color.ac_catalog_ink));
        }
        steps.removeAllViews();
        int number = 1;
        for (String step : stepList) steps.addView(line(number++ + ". " + step, 15, R.color.ac_catalog_ink));
        nutrition.removeAllViews();
        boolean any = false;
        any |= addNutrition(nutrition, R.string.ac_catalog_protein, protein);
        any |= addNutrition(nutrition, R.string.ac_catalog_carbs, carbs);
        any |= addNutrition(nutrition, R.string.ac_catalog_fat, fat);
        any |= addNutrition(nutrition, R.string.ac_catalog_protein100, protein100);
        any |= addNutrition(nutrition, R.string.ac_catalog_carbs100, carbs100);
        any |= addNutrition(nutrition, R.string.ac_catalog_fat100, fat100);
        if (!any) nutrition.addView(line(getString(R.string.ac_catalog_no_nutrition), 15, R.color.ac_catalog_muted));

        TextView footer = view.findViewById(R.id.ac_detail_footer);
        footer.setText(household ? R.string.ac_catalog_household_shared
                : personal ? R.string.ac_catalog_personal_note : R.string.ac_catalog_readonly);
        bindActions(view);
        loadFavoriteState(view);
    }

    private boolean addNutrition(LinearLayout container, int label, String value) {
        if (value == null) return false;
        container.addView(nutritionRow(label, value));
        return true;
    }

    private void bindActions(View view) {
        MaterialButton favoriteButton = view.findViewById(R.id.ac_detail_favorite);
        MaterialButton save = view.findViewById(R.id.ac_detail_save);
        MaterialButton edit = view.findViewById(R.id.ac_detail_edit);
        MaterialButton archive = view.findViewById(R.id.ac_detail_archive);
        MaterialButton delete = view.findViewById(R.id.ac_detail_delete);
        MaterialButton addHome = view.findViewById(R.id.ac_detail_add_home);
        MaterialButton cook = view.findViewById(R.id.ac_detail_cook);
        favoriteButton.setOnClickListener(ignored -> toggleFavorite(view));
        save.setVisibility((personal || household) ? View.GONE : View.VISIBLE);
        addHome.setVisibility(household ? View.GONE : View.VISIBLE);
        cook.setVisibility(household ? View.VISIBLE : View.GONE);
        cook.setOnClickListener(ignored -> confirmCooking());
        addHome.setOnClickListener(ignored -> addToHousehold());
        edit.setVisibility(personal || household ? View.VISIBLE : View.GONE);
        archive.setVisibility(personal || household ? View.VISIBLE : View.GONE);
        delete.setVisibility(personal ? View.VISIBLE : View.GONE);
        save.setOnClickListener(ignored -> saveToMine(save));
        edit.setOnClickListener(ignored -> host.navigate(Route.RECIPE_EDIT, household ? RecipeCard.householdKey(recipeId) : RecipeCard.personalKey(recipeId)));
        archive.setOnClickListener(ignored -> { if (household) archiveHousehold(); else archivePersonal(); });
        delete.setOnClickListener(ignored -> confirmDelete());
    }

    private void loadFavoriteState(View view) {
        RecipeFavoriteKind kind = household ? RecipeFavoriteKind.HOUSEHOLD
                : personal ? RecipeFavoriteKind.PERSONAL : RecipeFavoriteKind.CATALOG;
        host.calls().enqueue(host.api().recipeFavorites().getRecipeFavorite(kind, UUID.fromString(recipeId)),
                viewCallbacks.bind(new ApiCallback<RecipeFavorite>() {
                    @Override public void onSuccess(@Nullable RecipeFavorite value) {
                        if (!isAdded()) return;
                        favorite = value != null;
                        paintFavorite(view);
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        favorite = false;
                        paintFavorite(view);
                    }
                }));
    }

    private void paintFavorite(View view) {
        MaterialButton button = view.findViewById(R.id.ac_detail_favorite);
        button.setText(favorite ? R.string.ac_catalog_favorited : R.string.ac_catalog_favorite);
    }

    private void buttonBounce(View view) {
        if (!edu.xjtlu.civa.core.ui.Motion.enabled()) return;
        MaterialButton button = view.findViewById(R.id.ac_detail_favorite);
        button.animate().cancel();
        button.setScaleX(0.96f);
        button.setScaleY(0.96f);
        button.animate().scaleX(1f).scaleY(1f).setDuration(180L)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.2f)).start();
    }

    private void toggleFavorite(View view) {
        RecipeFavoriteKind kind = household ? RecipeFavoriteKind.HOUSEHOLD
                : personal ? RecipeFavoriteKind.PERSONAL : RecipeFavoriteKind.CATALOG;
        UUID id = UUID.fromString(recipeId);
        Call<Void> request = favorite ? host.api().recipeFavorites().removeRecipeFavorite(kind, id)
                : host.api().recipeFavorites().addRecipeFavorite(kind, id);
        boolean target = !favorite;
        host.calls().enqueue(request, viewCallbacks.bind(new ApiCallback<Void>() {
            @Override public void onSuccess(@Nullable Void value) {
                if (!isAdded()) return;
                favorite = target;
                paintFavorite(view);
                Haptics.tap(view);
                buttonBounce(view);
            }

            @Override public void onError(ApiError error) { }
        }));
    }

    private void saveToMine(MaterialButton save) {
        if (!(catalogRecipe != null)) return;
        // The public card may already exist in "my recipes": offer to open it instead of duplicating.
        host.calls().enqueue(host.api().personalRecipes().listPersonalRecipes(catalogRecipe.getName(),
                        null, false, 0, 20), new ApiCallback<edu.xjtlu.civa.api.model.PersonalRecipePage>() {
                    @Override public void onSuccess(@Nullable edu.xjtlu.civa.api.model.PersonalRecipePage value) {
                        if (!isAdded()) return;
                        edu.xjtlu.civa.api.model.PersonalRecipe existing = null;
                        if (value != null && value.getItems() != null) {
                            for (edu.xjtlu.civa.api.model.PersonalRecipe row : value.getItems()) {
                                if (row.getName() != null
                                        && row.getName().trim().equalsIgnoreCase(catalogRecipe.getName().trim())) {
                                    existing = row;
                                    break;
                                }
                            }
                        }
                        if (existing != null) {
                            final edu.xjtlu.civa.api.model.PersonalRecipe found = existing;
                            new MaterialAlertDialogBuilder(requireContext())
                                    .setMessage(R.string.ac_catalog_already_mine)
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .setPositiveButton(R.string.ac_catalog_open_mine, (dialog, which) ->
                                            host.navigate(Route.RECIPE_EDIT, RecipeCard.personalKey(found.getId().toString())))
                                    .show();
                        } else {
                            createPersonalCopy(save);
                        }
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        createPersonalCopy(save);
                    }
                });
    }

    private void createPersonalCopy(MaterialButton save) {
        CreatePersonalRecipeRequest request = new CreatePersonalRecipeRequest()
                .name(catalogRecipe.getName())
                .category(catalogRecipe.getCategory())
                .description(catalogRecipe.getDescription())
                .emoji(catalogRecipe.getEmoji())
                .steps(catalogRecipe.getSteps())
                .ingredients(toPersonalIngredients(catalogRecipe.getIngredients()))
                .methods(catalogRecipe.getMethods())
                .flavors(catalogRecipe.getFlavors())
                .mealTypes(catalogRecipe.getMealTypes())
                .estimatedMinutes(catalogRecipe.getEstimatedMinutes())
                .kcal(catalogRecipe.getKcal())
                .protein(catalogRecipe.getProtein())
                .carbs(catalogRecipe.getCarbs())
                .fat(catalogRecipe.getFat())
                .proteinPer100g(catalogRecipe.getProteinPer100g())
                .carbsPer100g(catalogRecipe.getCarbsPer100g())
                .fatPer100g(catalogRecipe.getFatPer100g());
        save.setEnabled(false);
        host.calls().enqueue(host.api().personalRecipes().createPersonalRecipe(UUID.randomUUID(), request),
                viewCallbacks.bind(new ApiCallback<PersonalRecipe>() {
                    @Override public void onSuccess(@Nullable PersonalRecipe value) {
                        if (!isAdded()) return;
                        save.setText(R.string.ac_catalog_saved);
                        toast(R.string.ac_catalog_saved_toast);
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        save.setEnabled(true);
                        toast(R.string.ac_catalog_error);
                    }
                }));
    }

    private List<edu.xjtlu.civa.api.model.PersonalRecipeIngredient> toPersonalIngredients(
            List<CatalogRecipeIngredient> source) {
        List<edu.xjtlu.civa.api.model.PersonalRecipeIngredient> result = new ArrayList<>();
        for (CatalogRecipeIngredient item : source) {
            result.add(new edu.xjtlu.civa.api.model.PersonalRecipeIngredient()
                    .name(item.getName())
                    .quantity(item.getQuantity())
                    .quantityPrefix(item.getQuantityPrefix())
                    .unit(item.getUnit())
                    .note(item.getNote()));
        }
        return result;
    }

    private void confirmCooking() {
        if (householdRecipe == null || host.sessions() == null) return;
        java.util.UUID home = uuidOrNull(host.sessions().currentHouseholdId());
        if (home == null) { toast(R.string.ac_catalog_home_missing); return; }
        android.widget.EditText servings = new android.widget.EditText(requireContext());
        servings.setText(householdRecipe.getYieldServings() == null ? "2" : householdRecipe.getYieldServings());
        servings.setHint(R.string.ac_detail_cook_servings);
        servings.setTextSize(15);
        servings.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        servings.setPadding(pad, pad / 2, pad, pad / 2);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ac_detail_cook_title)
                .setMessage(R.string.ac_detail_cook_message)
                .setView(servings)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ac_detail_cook, (dialog, which) -> {
                    String amount = servings.getText().toString().trim();
                    if (amount.isEmpty() || new java.math.BigDecimal(amount).signum() <= 0) {
                        toast(R.string.ac_detail_cook_shortage);
                        return;
                    }
                    CookingPreviewRequest request = new CookingPreviewRequest()
                            .recipeId(householdRecipe.getId()).servings(amount);
                    host.calls().enqueue(host.api().records().previewCooking(home, request),
                            new ApiCallback<CookingPreview>() {
                                @Override public void onSuccess(@Nullable CookingPreview preview) {
                                    if (!isAdded()) return;
                                    if (preview == null || preview.getShortages() == null
                                            || !preview.getShortages().isEmpty()) {
                                        toast(R.string.ac_detail_cook_shortage);
                                        return;
                                    }
                                    ConfirmCookingRequest confirm = new ConfirmCookingRequest()
                                            .recipeId(householdRecipe.getId())
                                            .recipeVersion(preview.getRecipeVersion())
                                            .servings(preview.getServings())
                                            .allocations(preview.getAllocations())
                                            .cookedAt(java.time.format.DateTimeFormatter
                                                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                                                    .withZone(java.time.ZoneOffset.UTC)
                                                    .format(java.time.Instant.now()));
                                    startCookingConfirmation(home, confirm);
                                }

                                @Override public void onError(ApiError error) {
                                    if (!isAdded()) return;
                                    toast(R.string.ac_catalog_error);
                                }
                            });
                })
                .show();
    }

    // ---- Cooking confirmation: one intent, one idempotency key, explicit unconfirmed state ----

    private void startCookingConfirmation(UUID home, ConfirmCookingRequest request) {
        String user = userId();
        if (user == null || !isAdded() || householdRecipe == null) return;
        IdempotentAction action = submissions.action(RecordSubmissionViewModel.COOKING);
        String summary = getString(R.string.ac_record_summary_cooking, householdRecipe.getName());
        if (!action.begin(summary)) {
            toast(R.string.ac_record_working);
            return;
        }
        submissions.remember(RecordSubmissionViewModel.COOKING, new CookingIntent(home, request));
        RecordSubmissions.recordIntent(host.calls(), user, home.toString(),
                RecordSubmissionViewModel.COOKING, action, summary,
                () -> sendCookingConfirmation(action, home, request, user),
                () -> {
                    action.abandon();
                    submissions.forget(RecordSubmissionViewModel.COOKING);
                    toast(R.string.ac_record_storage_failed);
                });
    }

    private void sendCookingConfirmation(IdempotentAction action, UUID home, ConfirmCookingRequest request,
                                         String user) {
        String key = action.key();
        if (key == null || !isAdded()) return;
        host.calls().enqueue(host.api().records().confirmCooking(home, UUID.fromString(key), request),
                new ApiCallback<CookingEvent>() {
                    @Override public void onSuccess(@Nullable CookingEvent value) {
                        if (!isAdded()) return;
                        RecordSubmissions.settled(host.calls(), user, action);
                        submissions.forget(RecordSubmissionViewModel.COOKING);
                        toast(R.string.ac_detail_cook_done);
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        if (IdempotentAction.ambiguous(error)) {
                            action.uncertain();
                            promptUnconfirmedCooking();
                        } else {
                            RecordSubmissions.rejected(host.calls(), user, action);
                            submissions.forget(RecordSubmissionViewModel.COOKING);
                            toast(R.string.ac_catalog_error);
                        }
                    }
                });
    }

    private void promptUnconfirmedCooking() {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ac_record_unconfirmed_title)
                .setMessage(R.string.ac_record_unconfirmed_message)
                .setCancelable(false)
                .setPositiveButton(R.string.ac_record_unconfirmed_retry, (dialog, which) -> retryCooking())
                .setNegativeButton(R.string.ac_record_unconfirmed_discard, (dialog, which) -> {
                    String user = userId();
                    if (user != null) {
                        RecordSubmissions.abandoned(host.calls(), user,
                                submissions.action(RecordSubmissionViewModel.COOKING));
                    }
                    submissions.forget(RecordSubmissionViewModel.COOKING);
                })
                .show();
    }

    private void retryCooking() {
        if (!isAdded() || submissions == null) return;
        IdempotentAction action = submissions.action(RecordSubmissionViewModel.COOKING);
        CookingIntent intent = submissions.intent(RecordSubmissionViewModel.COOKING, CookingIntent.class);
        String user = userId();
        if (!action.retry() || intent == null || user == null) {
            submissions.forget(RecordSubmissionViewModel.COOKING);
            return;
        }
        sendCookingConfirmation(action, intent.householdId(), intent.request(), user);
    }

    private void resumeCooking() {
        if (submissions == null || !isAdded()) return;
        IdempotentAction action = submissions.action(RecordSubmissionViewModel.COOKING);
        // A fresh view cannot own a running request; an in-flight marker is an orphaned intent.
        if (action.inFlight()) action.uncertain();
        if (action.unconfirmed()) promptUnconfirmedCooking();
    }

    @Nullable private String userId() {
        return host == null || host.sessions() == null ? null : host.sessions().currentUserId();
    }

    private void addToHousehold() {
        java.util.UUID home = host.sessions() == null ? null : uuidOrNull(host.sessions().currentHouseholdId());
        if (home == null) { toast(R.string.ac_catalog_home_missing); return; }
        ensureDictionary(() -> {
            String name = null;
            List<String> steps = List.of();
            List<IngredientImportParser.Input> inputs = List.of();
            String sourceYield = null;
            Integer sourceDuration = null;
            if (catalogRecipe != null) {
                name = catalogRecipe.getName();
                steps = catalogRecipe.getSteps() == null ? List.of() : catalogRecipe.getSteps();
                sourceYield = validYield(catalogRecipe.getServings());
                sourceDuration = validDuration(catalogRecipe.getEstimatedMinutes());
                List<IngredientImportParser.Input> parsed = new ArrayList<>();
                for (CatalogRecipeIngredient ingredient : catalogRecipe.getIngredients() == null
                        ? List.<CatalogRecipeIngredient>of() : catalogRecipe.getIngredients()) {
                    parsed.add(new IngredientImportParser.Input(ingredient.getName(),
                            ingredient.getQuantity(), ingredient.getQuantityPrefix(), ingredient.getUnit(),
                            ingredient.getNote(), ingredient.getSourceText()));
                }
                inputs = parsed;
            } else if (personalRecipe != null) {
                name = personalRecipe.getName();
                steps = personalRecipe.getSteps() == null ? List.of() : personalRecipe.getSteps();
                sourceYield = validYield(personalRecipe.getServings());
                sourceDuration = validDuration(personalRecipe.getEstimatedMinutes());
                List<IngredientImportParser.Input> parsed = new ArrayList<>();
                for (PersonalRecipeIngredient ingredient : personalRecipe.getIngredients() == null
                        ? List.<PersonalRecipeIngredient>of() : personalRecipe.getIngredients()) {
                    parsed.add(new IngredientImportParser.Input(ingredient.getName(),
                            ingredient.getQuantity(), ingredient.getQuantityPrefix(), ingredient.getUnit(),
                            ingredient.getNote(), ingredient.getSourceText()));
                }
                inputs = parsed;
            }
            if (name == null) return;
            List<Ingredient> dictionaryNow = dictionary == null ? List.of() : dictionary;
            IngredientImportParser.Result result = IngredientImportParser.parse(inputs, dictionaryNow);
            if (result.lines().isEmpty()) {
                toast(R.string.ac_import_no_ingredients);
                return;
            }
            String finalName = name;
            List<String> finalSteps = steps;
            String finalYield = sourceYield;
            Integer finalDuration = sourceDuration;
            Runnable show = () -> ImportReviewDialog.show(requireContext(), result.lines(), dictionaryNow,
                    finalYield, finalDuration,
                    (tracked, recipeOnly, yield, duration) -> createImported(home, finalName, finalSteps,
                            tracked, recipeOnly, yield, duration));
            ensureNoPendingCreate(home, show);
        });
    }

    private void ensureNoPendingCreate(java.util.UUID home, Runnable then) {
        String user = userId();
        if (createStore == null) {
            // No local pending store means we cannot guarantee import recovery; do not pretend.
            toast(R.string.ac_import_pending_unavailable);
            return;
        }
        if (user == null || !isAdded()) return;
        createStore.recover(user, home, null, new ApiCallback<CreateAttempt>() {
            @Override public void onSuccess(@Nullable CreateAttempt value) {
                if (!isAdded()) return;
                if (value != null) {
                    if (value.source == CreateAttempt.Source.IMPORT) {
                        showImportRetry(value);
                    } else {
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.ac_import_pending_title)
                                .setMessage(R.string.ac_import_pending_message)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
                    return;
                }
                then.run();
            }

            @Override public void onError(ApiError error) {
                if (!isAdded()) return;
                toast(R.string.ac_import_pending_unavailable);
            }
        });
    }

    private void createImported(java.util.UUID home, String name, List<String> steps,
                                List<edu.xjtlu.civa.api.model.RecipeIngredient> tracked,
                                List<edu.xjtlu.civa.api.model.RecipeOnlyIngredient> recipeOnly,
                                String yield, int duration) {
        if (!isAdded() || importing) return;
        if (tracked.isEmpty() && recipeOnly.isEmpty()) {
            toast(R.string.ac_import_invalid);
            return;
        }
        CreateRecipeRequest request = ImportRequest.build(name, steps, tracked, recipeOnly, yield, duration);
        if (request == null) {
            toast(R.string.ac_import_invalid);
            return;
        }
        String user = userId();
        if (user == null || createStore == null) {
            toast(R.string.ac_import_pending_unavailable);
            return;
        }
        importAttempt = CreateAttempt.start(UUID.randomUUID(), request, java.util.UUID.fromString(user),
                home, CreateAttempt.Source.IMPORT);
        importing = true;
        createStore.record(importAttempt, new ApiCallback<Void>() {
            @Override public void onSuccess(@Nullable Void value) {
                if (!isAdded()) return;
                sendImport(importAttempt);
            }

            @Override public void onError(ApiError error) {
                importing = false;
                if (isAdded()) toast(R.string.ac_import_pending_unavailable);
            }
        });
    }

    private void sendImport(CreateAttempt attempt) {
        if (!isAdded() || attempt == null) return;
        importing = true;
        host.calls().enqueue(host.api().recipe().createRecipe(attempt.householdId, attempt.key, attempt.payload),
                viewCallbacks.bind(new ApiCallback<Recipe>() {
                    @Override public void onSuccess(@Nullable Recipe value) {
                        importing = false;
                        attempt.confirmed();
                        if (createStore != null) createStore.confirm(attempt, importStorageCallback());
                        if (!isAdded()) return;
                        toast(R.string.ac_catalog_added_to_home);
                        if (value != null && value.getId() != null) {
                            host.navigate(Route.RECIPE_DETAIL, RecipeCard.householdKey(value.getId().toString()));
                        }
                    }

                    @Override public void onError(ApiError error) {
                        importing = false;
                        if (definitiveImportFailure(error)) {
                            attempt.failedNotExecuted();
                            if (createStore != null) createStore.confirm(attempt, importStorageCallback());
                            if (isAdded()) toast(R.string.ac_catalog_error);
                        } else {
                            attempt.unknown();
                            if (createStore != null) createStore.markUnknown(attempt, importStorageCallback());
                            if (isAdded()) showImportRetry(attempt);
                        }
                    }
                }));
    }

    private void showImportRetry(CreateAttempt attempt) {
        if (!isAdded()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ac_import_pending_title)
                .setMessage(R.string.ac_import_unknown_message)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ac_import_retry, (dialog, which) -> retryImport(attempt))
                .show();
    }

    private void retryImport(CreateAttempt attempt) {
        String user = userId();
        if (attempt == null || user == null
                || !attempt.matches(java.util.UUID.fromString(user), currentHousehold())) {
            toast(R.string.ac_recipe_attempt_identity);
            return;
        }
        attempt.retrying();
        sendImport(attempt);
    }

    private void resumeImportPending() {
        java.util.UUID home = currentHousehold();
        String user = userId();
        if (home == null || user == null || createStore == null) return;
        createStore.recover(user, home, CreateAttempt.Source.IMPORT, new ApiCallback<CreateAttempt>() {
            @Override public void onSuccess(@Nullable CreateAttempt value) {
                if (!isAdded() || value == null) return;
                importAttempt = value;
                showImportRetry(value);
            }

            @Override public void onError(ApiError error) { }
        });
    }

    @Nullable private java.util.UUID currentHousehold() {
        return host == null || host.sessions() == null ? null : uuidOrNull(host.sessions().currentHouseholdId());
    }

    private static boolean definitiveImportFailure(ApiError error) {
        if (error == null || error.code == null) return false;
        return switch (error.code) {
            case "VALIDATION_ERROR", "NOT_FOUND", "FORBIDDEN", "HOUSEHOLD_REQUIRED",
                    "INVALID_INGREDIENT" -> true;
            default -> false;
        };
    }

    private ApiCallback<Void> importStorageCallback() {
        return new ApiCallback<Void>() {
            @Override public void onSuccess(@Nullable Void value) { }
            @Override public void onError(ApiError error) {
                if (isAdded()) toast(R.string.ac_import_pending_unavailable);
            }
        };
    }

    private static String validYield(String value) {
        java.math.BigDecimal parsed = IngredientImportParser.number(value);
        return parsed != null && parsed.signum() > 0 ? IngredientImportParser.text(parsed) : null;
    }

    private static Integer validDuration(Integer value) {
        return value != null && value >= 1 && value <= 1440 ? value : null;
    }

    private void archiveHousehold() {
        if (householdRecipe == null || host.sessions() == null) return;
        java.util.UUID home = uuidOrNull(host.sessions().currentHouseholdId());
        if (home == null) { toast(R.string.ac_catalog_home_missing); return; }
        host.calls().enqueue(host.api().recipe().setRecipeArchived(home, householdRecipe.getId(),
                new edu.xjtlu.civa.api.model.ArchiveRecipeRequest()
                        .archived(!Boolean.TRUE.equals(householdRecipe.getArchived()))
                        .expectedVersion(householdRecipe.getVersion())),
                viewCallbacks.bind(new ApiCallback<Recipe>() {
                    @Override public void onSuccess(@Nullable Recipe value) {
                        if (!isAdded()) return;
                        householdRecipe = value;
                        toast(value != null && Boolean.TRUE.equals(value.getArchived())
                                ? R.string.ac_catalog_archived : R.string.ac_catalog_restored);
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_catalog_error);
                    }
                }));
    }

    private void archivePersonal() {
        if (personalRecipe == null) return;
        host.calls().enqueue(host.api().personalRecipes().setPersonalRecipeArchived(
                personalRecipe.getId(), new edu.xjtlu.civa.api.model.ArchivePersonalRecipeRequest()
                        .archived(!Boolean.TRUE.equals(personalRecipe.getArchived()))
                        .expectedVersion(personalRecipe.getVersion())),
                viewCallbacks.bind(new ApiCallback<PersonalRecipe>() {
                    @Override public void onSuccess(@Nullable PersonalRecipe value) {
                        if (!isAdded()) return;
                        personalRecipe = value;
                        CatalogCache.savePersonal(requireContext(), value);
                        toast(value != null && Boolean.TRUE.equals(value.getArchived())
                                ? R.string.ac_catalog_archived : R.string.ac_catalog_restored);
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_catalog_error);
                    }
                }));
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.ac_catalog_delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ac_catalog_delete, (dialog, which) -> deletePersonal())
                .show();
    }

    private void deletePersonal() {
        host.calls().enqueue(host.api().personalRecipes().deletePersonalRecipe(UUID.fromString(recipeId)),
                viewCallbacks.bind(new ApiCallback<Void>() {
                    @Override public void onSuccess(@Nullable Void value) {
                        if (!isAdded()) return;
                        CatalogCache.clearPersonal(requireContext(), recipeId);
                        requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_catalog_error);
                    }
                }));
    }

    private void toast(int text) {
        edu.xjtlu.civa.core.ui.Feedback.show(requireActivity(), text, false);
    }

    private String display(Object item) {
        if (item instanceof CatalogRecipeIngredient value) return displayIngredient(value.getName(), value.getQuantity(),
                value.getQuantityPrefix(), value.getUnit(), value.getNote());
        if (item instanceof edu.xjtlu.civa.api.model.PersonalRecipeIngredient value) {
            return displayIngredient(value.getName(), value.getQuantity(), value.getQuantityPrefix(),
                    value.getUnit(), value.getNote());
        }
        return String.valueOf(item);
    }

    private String displayIngredient(String name, String quantity, String prefix, String unit, String note) {
        List<String> parts = new ArrayList<>();
        if (has(name)) parts.add(name);
        if (has(prefix)) parts.add(prefix);
        if (has(quantity)) parts.add(quantity);
        if (has(unit)) parts.add(unit);
        if (has(note)) parts.add(getString(R.string.ac_l10n_parenthesized, note));
        return String.join(" ", parts);
    }

    private TextView nutritionRow(int label, String value) {
        return line(getString(R.string.ac_l10n_label_value, getString(label), value), 15, R.color.ac_catalog_ink);
    }

    private TextView line(String text, int size, int color) {
        TextView view = new TextView(requireContext());
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(requireContext().getColor(color));
        view.setPadding(0, 6, 0, 6);
        view.setLineSpacing(0f, 1.25f);
        return view;
    }

    private static boolean has(String value) { return value != null && !value.trim().isEmpty(); }

    private static java.util.UUID uuidOrNull(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return java.util.UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }

    @Override public void onDestroyView() {
        // Invalidate before cancel(): cancellation can deliver an error callback.
        viewCallbacks.endView();
        if (call != null) {
            call.cancel();
            call = null;
        }
        super.onDestroyView();
    }
}
