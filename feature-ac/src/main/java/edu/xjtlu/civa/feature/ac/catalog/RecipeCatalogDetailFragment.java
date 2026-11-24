package edu.xjtlu.civa.feature.ac.catalog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import edu.xjtlu.civa.api.model.CatalogRecipe;
import edu.xjtlu.civa.api.model.CatalogRecipeIngredient;
import edu.xjtlu.civa.api.model.CreatePersonalRecipeRequest;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.PersonalRecipe;
import edu.xjtlu.civa.api.model.RecipeFavorite;
import edu.xjtlu.civa.api.model.RecipeFavoriteKind;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.Route;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import retrofit2.Call;

/** Recipe detail for public and personal recipes, with favourites and the personal actions. */
public final class RecipeCatalogDetailFragment extends Fragment {
    private static final String ARG_ID = "recipeId";

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
        host = (AcHost) requireActivity();
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
    }

    private void loadCatalog(View view) {
        call = host.api().recipeCatalog().getCatalogRecipe(UUID.fromString(recipeId));
        host.calls().enqueue((Call<CatalogRecipe>) call, new ApiCallback<CatalogRecipe>() {
            @Override public void onSuccess(@Nullable CatalogRecipe value) {
                if (!isAdded()) return;
                if (value == null) return;
                catalogRecipe = value;
                CatalogCache.saveDetail(requireContext(), value);
                render(view, value);
            }

            @Override public void onError(ApiError error) { }
        });
            }

    private void loadHousehold(View view) {
        java.util.UUID home = host.sessions() == null ? null : uuidOrNull(host.sessions().currentHouseholdId());
        if (home == null) {
            toast(R.string.ac_catalog_home_missing);
            return;
        }
        ensureDictionary(() -> { });
        call = host.api().recipe().getRecipe(home, UUID.fromString(recipeId));
        host.calls().enqueue((Call<Recipe>) call, new ApiCallback<Recipe>() {
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
        });
    }

    private void ensureDictionary(Runnable then) {
        if (dictionary != null) { then.run(); return; }
        host.calls().enqueue(host.api().inventory().listIngredients(null, 0, 100),
                new ApiCallback<edu.xjtlu.civa.api.model.IngredientPage>() {
                    @Override public void onSuccess(@Nullable edu.xjtlu.civa.api.model.IngredientPage value) {
                        if (!isAdded()) return;
                        dictionary = value == null || value.getItems() == null ? List.of() : value.getItems();
                        then.run();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        dictionary = List.of();
                        then.run();
                    }
                });
    }

    private void loadPersonal(View view) {
        call = host.api().personalRecipes().getPersonalRecipe(UUID.fromString(recipeId));
        host.calls().enqueue((Call<PersonalRecipe>) call, new ApiCallback<PersonalRecipe>() {
            @Override public void onSuccess(@Nullable PersonalRecipe value) {
                if (!isAdded()) return;
                if (value == null) return;
                personalRecipe = value;
                CatalogCache.savePersonal(requireContext(), value);
                render(view, value);
            }

            @Override public void onError(ApiError error) { }
        });
    }

    private void render(View view, Object value) {
        TextView emoji = view.findViewById(R.id.ac_detail_emoji);
        TextView name = view.findViewById(R.id.ac_detail_name);
        TextView meta = view.findViewById(R.id.ac_detail_meta);
        TextView tags = view.findViewById(R.id.ac_detail_tags);
        TextView description = view.findViewById(R.id.ac_detail_description);
        LinearLayout ingredients = view.findViewById(R.id.ac_detail_ingredients);
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
        if (kcal != null) metaParts.add(kcal + " kcal / 份");
        if (minutes != null) metaParts.add(minutes + " 分钟");
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
        if (value instanceof Recipe recipe) {
            java.util.Map<UUID, String> names = HouseholdRecipes.names(dictionary == null ? List.of() : dictionary);
            String display = HouseholdRecipes.display(recipe, names);
            ingredients.addView(line(display.isEmpty() ? getString(R.string.ac_catalog_missing) : display, 15,
                    R.color.ac_catalog_ink));
        } else {
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
        favoriteButton.setOnClickListener(ignored -> toggleFavorite(view));
        save.setVisibility((personal || household) ? View.GONE : View.VISIBLE);
        addHome.setVisibility(household ? View.GONE : View.VISIBLE);
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
                new ApiCallback<RecipeFavorite>() {
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
                });
    }

    private void paintFavorite(View view) {
        MaterialButton button = view.findViewById(R.id.ac_detail_favorite);
        button.setText(favorite ? R.string.ac_catalog_favorited : R.string.ac_catalog_favorite);
    }

    private void toggleFavorite(View view) {
        RecipeFavoriteKind kind = household ? RecipeFavoriteKind.HOUSEHOLD
                : personal ? RecipeFavoriteKind.PERSONAL : RecipeFavoriteKind.CATALOG;
        UUID id = UUID.fromString(recipeId);
        Call<Void> request = favorite ? host.api().recipeFavorites().removeRecipeFavorite(kind, id)
                : host.api().recipeFavorites().addRecipeFavorite(kind, id);
        boolean target = !favorite;
        host.calls().enqueue(request, new ApiCallback<Void>() {
            @Override public void onSuccess(@Nullable Void value) {
                if (!isAdded()) return;
                favorite = target;
                paintFavorite(view);
            }

            @Override public void onError(ApiError error) { }
        });
    }

    private void saveToMine(MaterialButton save) {
        if (!(catalogRecipe != null)) return;
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
                new ApiCallback<PersonalRecipe>() {
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
                });
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

    private void addToHousehold() {
        java.util.UUID home = host.sessions() == null ? null : uuidOrNull(host.sessions().currentHouseholdId());
        if (home == null) { toast(R.string.ac_catalog_home_missing); return; }
        ensureDictionary(() -> {
            List<String> steps = List.of();
            List<String> lines = List.of();
            String name = null;
            if (catalogRecipe != null) {
                name = catalogRecipe.getName(); steps = catalogRecipe.getSteps();
                lines = HouseholdRecipes.catalogIngredientLines(catalogRecipe.getIngredients());
            } else if (personalRecipe != null) {
                name = personalRecipe.getName(); steps = personalRecipe.getSteps();
                lines = HouseholdRecipes.personalIngredientLines(personalRecipe.getIngredients());
            }
            if (name == null) return;
            CreateRecipeRequest request = HouseholdRecipes.request(name, steps, lines,
                    HouseholdRecipes.byName(dictionary));
            if (request == null) { toast(R.string.ac_catalog_ingredient_unknown); return; }
            host.calls().enqueue(host.api().recipe().createRecipe(home, UUID.randomUUID(), request),
                    new ApiCallback<Recipe>() {
                        @Override public void onSuccess(@Nullable Recipe value) {
                            if (!isAdded()) return;
                            toast(R.string.ac_catalog_added_to_home);
                        }

                        @Override public void onError(ApiError error) {
                            if (!isAdded()) return;
                            toast(R.string.ac_catalog_error);
                        }
                    });
        });
    }

    private void archiveHousehold() {
        if (householdRecipe == null || host.sessions() == null) return;
        java.util.UUID home = uuidOrNull(host.sessions().currentHouseholdId());
        if (home == null) { toast(R.string.ac_catalog_home_missing); return; }
        host.calls().enqueue(host.api().recipe().setRecipeArchived(home, householdRecipe.getId(),
                new edu.xjtlu.civa.api.model.ArchiveRecipeRequest()
                        .archived(!Boolean.TRUE.equals(householdRecipe.getArchived()))
                        .expectedVersion(householdRecipe.getVersion())),
                new ApiCallback<Recipe>() {
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
                });
    }

    private void archivePersonal() {
        if (personalRecipe == null) return;
        host.calls().enqueue(host.api().personalRecipes().setPersonalRecipeArchived(
                personalRecipe.getId(), new edu.xjtlu.civa.api.model.ArchivePersonalRecipeRequest()
                        .archived(!Boolean.TRUE.equals(personalRecipe.getArchived()))
                        .expectedVersion(personalRecipe.getVersion())),
                new ApiCallback<PersonalRecipe>() {
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
                });
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
                new ApiCallback<Void>() {
                    @Override public void onSuccess(@Nullable Void value) {
                        if (!isAdded()) return;
                        CatalogCache.clearPersonal(requireContext(), recipeId);
                        requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_catalog_error);
                    }
                });
    }

    private void toast(int text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
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
        if (has(note)) parts.add("（" + note + "）");
        return String.join(" ", parts);
    }

    private TextView nutritionRow(int label, String value) {
        return line(getString(label) + "：" + value, 15, R.color.ac_catalog_ink);
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
        if (call != null) {
            call.cancel();
            call = null;
        }
        super.onDestroyView();
    }
}
