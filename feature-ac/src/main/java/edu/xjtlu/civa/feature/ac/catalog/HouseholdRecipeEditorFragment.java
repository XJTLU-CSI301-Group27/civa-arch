package edu.xjtlu.civa.feature.ac.catalog;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.IngredientPage;
import edu.xjtlu.civa.api.model.MediaScope;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.api.model.RecipeIngredient;
import edu.xjtlu.civa.api.model.RecipeOnlyIngredient;
import edu.xjtlu.civa.api.model.UpdateRecipeRequest;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.data.MediaImages;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.SubmitButton;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.R;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Structured household recipe editor: tracked rows first, description-only rows second. */
public final class HouseholdRecipeEditorFragment extends Fragment {
    private static final String ARG_ID = "recipeId";

    private AcHost host;
    private UUID home;
    private UUID recipeId;
    private Recipe existing;
    private IngredientEditorViewModel vm;
    private EditText name, steps, yield, duration;
    private MaterialButton save, pickImage, removeImage;
    private ImageView image;
    private RecyclerView trackedRecycler, recipeOnlyRecycler;
    private TrackedIngredientAdapter trackedAdapter;
    private RecipeOnlyIngredientAdapter recipeOnlyAdapter;
    private UUID imageId;
    private boolean uploading;
    private boolean saving;
    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::uploadImage);

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
        super.onViewCreated(view, savedInstanceState);
        host = (AcHost) requireActivity();
        vm = new ViewModelProvider(this).get(IngredientEditorViewModel.class);
        String entityId = getArguments() == null ? null : getArguments().getString(ARG_ID);
        home = host.sessions() == null ? null : houseId(host.sessions().currentHouseholdId());
        recipeId = RecipeCard.isHousehold(entityId) ? UUID.fromString(RecipeCard.idOf(entityId)) : null;

        name = view.findViewById(R.id.ac_household_recipe_name);
        steps = view.findViewById(R.id.ac_household_recipe_steps);
        yield = view.findViewById(R.id.ac_household_recipe_yield);
        duration = view.findViewById(R.id.ac_household_recipe_duration);
        save = view.findViewById(R.id.ac_household_recipe_save);
        image = view.findViewById(R.id.ac_household_recipe_image);
        pickImage = view.findViewById(R.id.ac_household_recipe_image_pick);
        removeImage = view.findViewById(R.id.ac_household_recipe_image_remove);
        trackedRecycler = view.findViewById(R.id.ac_household_recipe_tracked_rows);
        recipeOnlyRecycler = view.findViewById(R.id.ac_household_recipe_recipe_only_rows);
        trackedAdapter = new TrackedIngredientAdapter(trackedHost);
        recipeOnlyAdapter = new RecipeOnlyIngredientAdapter(recipeOnlyHost);
        trackedRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recipeOnlyRecycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        trackedRecycler.setAdapter(trackedAdapter);
        recipeOnlyRecycler.setAdapter(recipeOnlyAdapter);

        TextView heading = view.findViewById(R.id.ac_household_recipe_heading);
        heading.setText(recipeId == null ? R.string.ac_household_recipe_new : R.string.ac_household_recipe_edit);
        view.findViewById(R.id.ac_household_recipe_cancel).setOnClickListener(ignored ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        view.findViewById(R.id.ac_household_recipe_add_tracked).setOnClickListener(ignored -> {
            if (vm.state == null) return;
            long id = vm.state.addTracked();
            bindState();
            scrollToTracked(id);
        });
        view.findViewById(R.id.ac_household_recipe_add_recipe_only).setOnClickListener(ignored -> {
            if (vm.state == null) return;
            long id = vm.state.addRecipeOnly();
            bindState();
            scrollToRecipeOnly(id);
        });
        pickImage.setOnClickListener(ignored -> imagePicker.launch("image/*"));
        removeImage.setOnClickListener(ignored -> {
            imageId = null;
            vm.imageId = null;
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
        });
        save.setOnClickListener(ignored -> submit());

        if (vm.initialized) {
            existing = vm.existing;
            imageId = vm.imageId;
            bindState();
            renderImage();
            return;
        }
        if (recipeId == null) {
            yield.setText("2");
            duration.setText("30");
        }
        save.setEnabled(false);
        loadDictionary(0, new ArrayList<>(), () -> {
            if (!isAdded()) return;
            vm.dictionary = dictionary;
            if (recipeId != null) loadExisting();
            else {
                vm.state = IngredientEditorState.empty(vm.dictionary);
                vm.initialized = true;
                bindState();
            }
        });
    }

    private List<Ingredient> dictionary = List.of();

    private void loadDictionary(int page, List<Ingredient> collected, Runnable done) {
        host.calls().enqueue(host.api().inventory().listIngredients(null, page, 100),
                new ApiCallback<IngredientPage>() {
                    @Override public void onSuccess(@Nullable IngredientPage value) {
                        if (!isAdded()) return;
                        if (value == null || value.getItems() == null) {
                            dictionary = List.copyOf(collected);
                            done.run();
                            return;
                        }
                        collected.addAll(value.getItems());
                        long total = value.getTotal() == null ? collected.size() : value.getTotal();
                        if (collected.size() < total && page < 20) loadDictionary(page + 1, collected, done);
                        else {
                            dictionary = List.copyOf(collected);
                            done.run();
                        }
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        dictionary = List.copyOf(collected);
                        done.run();
                    }
                });
    }

    private void loadExisting() {
        host.calls().enqueue(host.api().recipe().getRecipe(home, recipeId), new ApiCallback<Recipe>() {
            @Override public void onSuccess(@Nullable Recipe value) {
                if (!isAdded()) return;
                if (value == null) {
                    toast(R.string.ac_catalog_error);
                    return;
                }
                existing = value;
                vm.existing = value;
                imageId = value.getImageId();
                vm.imageId = imageId;
                name.setText(value.getName());
                yield.setText(value.getYieldServings());
                duration.setText(String.valueOf(value.getDurationMinutes()));
                steps.setText(String.join("\n", value.getSteps() == null ? List.of() : value.getSteps()));
                vm.state = IngredientEditorState.from(value, vm.dictionary);
                vm.initialized = true;
                bindState();
                renderImage();
            }

            @Override public void onError(ApiError error) {
                if (!isAdded()) return;
                toast(R.string.ac_catalog_error);
            }
        });
    }

    private void renderImage() {
        if (imageId == null) {
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
            return;
        }
        image.setVisibility(View.VISIBLE);
        MediaImages.load(host.api(), host.calls(), imageId, image);
    }

    private void bindState() {
        if (vm.state == null) return;
        trackedAdapter.submit(vm.state.trackedRows(), Map.of());
        recipeOnlyAdapter.submit(vm.state.recipeOnlyRows(), Map.of());
        save.setEnabled(!saving);
    }

    private void scrollToTracked(long id) {
        int index = indexOf(vm.state.trackedRows(), id);
        if (index >= 0) trackedRecycler.post(() -> trackedRecycler.smoothScrollToPosition(index));
    }

    private void scrollToRecipeOnly(long id) {
        int index = indexOf(vm.state.recipeOnlyRows(), id);
        if (index >= 0) recipeOnlyRecycler.post(() -> recipeOnlyRecycler.smoothScrollToPosition(index));
    }

    private static int indexOf(List<IngredientEditorState.Row> rows, long id) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id == id) return i;
        return -1;
    }

    private final TrackedIngredientAdapter.Host trackedHost = new TrackedIngredientAdapter.Host() {
        @Override public List<Ingredient> ingredients() { return vm.dictionary; }

        @Override public void onTrackedSelected(long rowId, Ingredient ingredient) {
            vm.state.selectTrackedIngredient(rowId, ingredient);
        }

        @Override public void onQuantity(long rowId, String text) {
            vm.state.setQuantity(rowId, text);
        }

        @Override public void onRemove(long rowId) {
            vm.state.remove(rowId);
            bindState();
        }

        @Override public void onConvert(long rowId) {
            vm.state.convert(rowId);
            bindState();
        }
    };

    private final RecipeOnlyIngredientAdapter.Host recipeOnlyHost = new RecipeOnlyIngredientAdapter.Host() {
        @Override public void onName(long rowId, String text) { vm.state.setRecipeName(rowId, text); }
        @Override public void onAmount(long rowId, String text) { vm.state.setAmountText(rowId, text); }
        @Override public void onNote(long rowId, String text) { vm.state.setNote(rowId, text); }

        @Override public void onRemove(long rowId) {
            vm.state.remove(rowId);
            bindState();
        }

        @Override public void onConvert(long rowId) {
            vm.state.convert(rowId);
            bindState();
        }
    };

    private void uploadImage(@Nullable Uri uri) {
        if (uri == null || home == null || !isAdded()) return;
        uploading = true;
        pickImage.setEnabled(false);
        toast(R.string.ac_editor_image_uploading);
        MediaImages.upload(requireContext(), host.api(), host.calls(), uri, MediaScope.HOUSEHOLD, home, id -> {
            if (!isAdded()) return;
            uploading = false;
            pickImage.setEnabled(true);
            if (id == null) {
                toast(R.string.ac_editor_image_failed);
                return;
            }
            imageId = id;
            vm.imageId = id;
            image.setVisibility(View.VISIBLE);
            image.setImageURI(uri);
        });
    }

    private void submit() {
        if (saving || vm.state == null) return;
        if (home == null) {
            toast(R.string.ac_catalog_home_missing);
            return;
        }
        if (uploading) {
            toast(R.string.ac_editor_image_uploading);
            return;
        }
        String recipeName = name.getText().toString().trim();
        List<String> stepList = PersonalRecipeEditorFragment.lines(steps.getText().toString());
        BigDecimal yieldValue = IngredientImportParser.number(yield.getText().toString());
        int minutes;
        try {
            minutes = Integer.parseInt(duration.getText().toString().trim());
        } catch (NumberFormatException invalid) {
            minutes = 0;
        }
        if (recipeName.isEmpty() || stepList.isEmpty() || yieldValue == null
                || yieldValue.signum() <= 0 || minutes < 1 || minutes > 1440) {
            toast(R.string.ac_household_recipe_invalid);
            return;
        }

        IngredientEditorState.Validation validation = vm.state.validate();
        if (!validation.valid()) {
            Map<Long, String> errors = errorMap(validation);
            trackedAdapter.submit(vm.state.trackedRows(), errors);
            recipeOnlyAdapter.submit(vm.state.recipeOnlyRows(), errors);
            toast(errors.isEmpty() ? R.string.ac_household_recipe_err_empty
                    : R.string.ac_household_recipe_validation);
            return;
        }

        List<RecipeIngredient> tracked = vm.state.recipeIngredients();
        List<RecipeOnlyIngredient> recipeOnly = vm.state.recipeOnlyIngredients();
        save.setEnabled(false);
        saving = true;
        SubmitButton.start(save);
        if (existing == null) {
            CreateRecipeRequest request = new CreateRecipeRequest()
                    .name(recipeName)
                    .steps(stepList)
                    .ingredients(tracked)
                    .recipeOnlyIngredients(recipeOnly)
                    .yieldServings(IngredientImportParser.text(yieldValue))
                    .durationMinutes(minutes)
                    .tags(new LinkedHashSet<>(List.of("家庭菜谱")))
                    .imageId(imageId);
            host.calls().enqueue(host.api().recipe().createRecipe(home, UUID.randomUUID(), request),
                    new ApiCallback<Recipe>() {
                        @Override public void onSuccess(@Nullable Recipe value) {
                            if (!isAdded()) return;
                            toast(R.string.ac_editor_saved);
                            getParentFragmentManager().popBackStack();
                        }

                        @Override public void onError(ApiError error) {
                            if (!isAdded()) return;
                            saving = false;
                            save.setEnabled(true);
                            SubmitButton.stop(save);
                            toast(R.string.ac_catalog_error);
                        }
                    });
            return;
        }

        UpdateRecipeRequest update = new UpdateRecipeRequest()
                .name(recipeName)
                .steps(stepList)
                .ingredients(tracked)
                .recipeOnlyIngredients(vm.state.recipeOnlyPayloadForUpdate())
                .yieldServings(IngredientImportParser.text(yieldValue))
                .durationMinutes(minutes)
                .tags(new LinkedHashSet<>(List.of("家庭菜谱")))
                .imageId(imageId)
                .expectedVersion(existing.getVersion());
        host.calls().enqueue(host.api().recipe().updateRecipe(home, existing.getId(), update),
                new ApiCallback<Recipe>() {
                    @Override public void onSuccess(@Nullable Recipe value) {
                        if (!isAdded()) return;
                        toast(R.string.ac_editor_saved);
                        getParentFragmentManager().popBackStack();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        saving = false;
                        save.setEnabled(true);
                        SubmitButton.stop(save);
                        toast(R.string.ac_catalog_error);
                    }
                });
    }

    private Map<Long, String> errorMap(IngredientEditorState.Validation validation) {
        Map<Long, String> result = new java.util.HashMap<>();
        for (IngredientEditorState.RowError error : validation.errors()) {
            if (error.rowId() == 0) continue;
            result.putIfAbsent(error.rowId(), getString(message(error.field())));
        }
        return result;
    }

    private static int message(IngredientEditorState.Field field) {
        return switch (field) {
            case INGREDIENT -> R.string.ac_household_recipe_err_ingredient;
            case QUANTITY -> R.string.ac_household_recipe_err_quantity;
            case UNIT -> R.string.ac_household_recipe_err_unit;
            case DUPLICATE -> R.string.ac_household_recipe_err_duplicate;
            case RECIPE_NAME -> R.string.ac_household_recipe_err_recipe_name;
            case RECIPE_AMOUNT, RECIPE_NOTE -> R.string.ac_household_recipe_err_too_long;
            case EMPTY, LIMIT -> R.string.ac_household_recipe_err_empty;
        };
    }

    private void toast(int text) {
        edu.xjtlu.civa.core.ui.Feedback.show(requireActivity(), text, false);
    }

    private static UUID houseId(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }
}
