package edu.xjtlu.civa.feature.ac.catalog;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.IngredientPage;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.UpdateRecipeRequest;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import retrofit2.Call;

/** Creates or edits a household recipe from "ingredient quantity" lines and a step list. */
public final class HouseholdRecipeEditorFragment extends Fragment {
    private static final String ARG_ID = "recipeId";

    private AcHost host;
    private UUID home;
    private UUID recipeId;
    private Recipe existing;
    private List<Ingredient> dictionary = List.of();
    private EditText name, steps, ingredients;
    private MaterialButton save;

    public static HouseholdRecipeEditorFragment create(@Nullable String entityId) {
        HouseholdRecipeEditorFragment fragment = new HouseholdRecipeEditorFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ID, entityId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.ac_fragment_household_recipe_editor, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        host = (AcHost) requireActivity();
        String entityId = getArguments() == null ? null : getArguments().getString(ARG_ID);
        home = host.sessions() == null ? null : houseId(host.sessions().currentHouseholdId());
        recipeId = RecipeCard.isHousehold(entityId) ? UUID.fromString(RecipeCard.idOf(entityId)) : null;
        name = view.findViewById(R.id.ac_household_recipe_name);
        steps = view.findViewById(R.id.ac_household_recipe_steps);
        ingredients = view.findViewById(R.id.ac_household_recipe_ingredients);
        save = view.findViewById(R.id.ac_household_recipe_save);
        TextView heading = view.findViewById(R.id.ac_household_recipe_heading);
        heading.setText(recipeId == null ? R.string.ac_household_recipe_new : R.string.ac_household_recipe_edit);
        view.findViewById(R.id.ac_household_recipe_cancel).setOnClickListener(ignored ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        save.setEnabled(false);
        loadDictionary(0, new ArrayList<>(), () -> {
            save.setEnabled(true);
            if (recipeId != null) loadExisting();
        });
    }

    private void loadDictionary(int page, List<Ingredient> collected, Runnable done) {
        host.calls().enqueue(host.api().inventory().listIngredients(null, page, 100),
                new ApiCallback<IngredientPage>() {
                    @Override public void onSuccess(@Nullable IngredientPage value) {
                        if (value == null || value.getItems() == null) { dictionary = collected; done.run(); return; }
                        collected.addAll(value.getItems());
                        long total = value.getTotal() == null ? collected.size() : value.getTotal();
                        if (collected.size() < total && page < 20) loadDictionary(page + 1, collected, done);
                        else { dictionary = collected; done.run(); }
                    }

                    @Override public void onError(ApiError error) {
                        dictionary = collected;
                        done.run();
                    }
                });
    }

    private void loadExisting() {
        host.calls().enqueue(host.api().recipe().getRecipe(home, recipeId), new ApiCallback<Recipe>() {
            @Override public void onSuccess(@Nullable Recipe value) {
                if (value == null) return;
                existing = value;
                name.setText(value.getName());
                steps.setText(String.join("\n", value.getSteps()));
                Map<UUID, String> names = HouseholdRecipes.names(dictionary);
                List<String> lines = new ArrayList<>();
                if (value.getIngredients() != null) {
                    for (edu.xjtlu.civa.api.model.RecipeIngredient item : value.getIngredients()) {
                        lines.add(names.getOrDefault(item.getIngredientId(), "") + " " + item.getQuantity());
                    }
                }
                ingredients.setText(String.join("\n", lines));
                save.setOnClickListener(ignored -> submit());
            }

            @Override public void onError(ApiError error) {
                toast(R.string.ac_catalog_error);
            }
        });
    }

    private void submit() {
        if (home == null) { toast(R.string.ac_catalog_home_missing); return; }
        CreateRecipeRequest request = HouseholdRecipes.request(name.getText().toString().trim(),
                PersonalRecipeEditorFragment.lines(steps.getText().toString()),
                PersonalRecipeEditorFragment.lines(ingredients.getText().toString()),
                HouseholdRecipes.byName(dictionary));
        if (request == null) { toast(R.string.ac_catalog_ingredient_unknown); return; }
        save.setEnabled(false);
        if (existing == null) {
            host.calls().enqueue(host.api().recipe().createRecipe(home, UUID.randomUUID(), request),
                    new ApiCallback<Recipe>() {
                        @Override public void onSuccess(@Nullable Recipe value) {
                            toast(R.string.ac_editor_saved);
                            getParentFragmentManager().popBackStack();
                        }

                        @Override public void onError(ApiError error) {
                            save.setEnabled(true);
                            toast(R.string.ac_catalog_error);
                        }
                    });
        } else {
            UpdateRecipeRequest update = new UpdateRecipeRequest()
                    .name(request.getName())
                    .steps(request.getSteps())
                    .ingredients(request.getIngredients())
                    .yieldServings(request.getYieldServings())
                    .durationMinutes(request.getDurationMinutes())
                    .tags(request.getTags())
                    .expectedVersion(existing.getVersion());
            host.calls().enqueue(host.api().recipe().updateRecipe(home, existing.getId(), update),
                    new ApiCallback<Recipe>() {
                        @Override public void onSuccess(@Nullable Recipe value) {
                            toast(R.string.ac_editor_saved);
                            getParentFragmentManager().popBackStack();
                        }

                        @Override public void onError(ApiError error) {
                            save.setEnabled(true);
                            toast(R.string.ac_catalog_error);
                        }
                    });
        }
    }

    private void toast(int text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }

    private static UUID houseId(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }
}
