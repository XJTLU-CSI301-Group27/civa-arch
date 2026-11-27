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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
    private MaterialButton save, pickImage, removeImage, addTracked, attemptRetry;
    private TextView dictionaryError, attemptStatus;
    private ImageView image;
    private RecyclerView trackedRecycler, recipeOnlyRecycler;
    private TrackedIngredientAdapter trackedAdapter;
    private RecipeOnlyIngredientAdapter recipeOnlyAdapter;
    private UUID imageId;
    private boolean uploading;
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
        addTracked = view.findViewById(R.id.ac_household_recipe_add_tracked);
        dictionaryError = view.findViewById(R.id.ac_household_recipe_dictionary_error);
        attemptStatus = view.findViewById(R.id.ac_household_recipe_attempt_status);
        attemptRetry = view.findViewById(R.id.ac_household_recipe_attempt_retry);
        attemptRetry.setOnClickListener(ignored -> retryCreate());
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

        observeSaveState();
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
        boolean dictionaryReady = !vm.dictionary.isEmpty();
        dictionaryError.setVisibility(dictionaryReady ? View.GONE : View.VISIBLE);
        addTracked.setEnabled(dictionaryReady);
        trackedAdapter.submit(vm.state.trackedRows(), Map.of());
        recipeOnlyAdapter.submit(vm.state.recipeOnlyRows(), Map.of());
        bindAttempt();
    }

    private void bindAttempt() {
        CreateAttempt attempt = vm.createAttempt;
        attemptStatus.setVisibility(View.GONE);
        attemptRetry.setVisibility(View.GONE);
        if (attempt == null) {
            save.setEnabled(!Boolean.TRUE.equals(vm.saving.getValue()) && vm.state != null);
            return;
        }
        switch (attempt.phase()) {
            case SENDING -> {
                attemptStatus.setText(R.string.ac_recipe_attempt_sending);
                attemptStatus.setVisibility(View.VISIBLE);
                save.setEnabled(false);
            }
            case UNKNOWN -> {
                attemptStatus.setText(R.string.ac_recipe_attempt_unknown);
                attemptStatus.setVisibility(View.VISIBLE);
                attemptRetry.setVisibility(View.VISIBLE);
                save.setEnabled(false);
            }
            case FAILED_NOT_EXECUTED -> {
                attemptStatus.setText(R.string.ac_recipe_attempt_failed);
                attemptStatus.setVisibility(View.VISIBLE);
                save.setEnabled(vm.state != null);
            }
            case CONFIRMED -> save.setEnabled(false);
        }
    }

    private void observeSaveState() {
        vm.saving.observe(getViewLifecycleOwner(), value -> {
            if (save == null) return;
            if (vm.createAttempt != null) bindAttempt();
            else save.setEnabled(!Boolean.TRUE.equals(value) && vm.state != null);
        });
        vm.error.observe(getViewLifecycleOwner(), value -> {
            if (value == null || !isAdded()) return;
            toast(value);
            vm.error.setValue(null);
        });
        vm.saved.observe(getViewLifecycleOwner(), value -> {
            if (!Boolean.TRUE.equals(value)) return;
            vm.saved.setValue(false);
            if (isAdded()) getParentFragmentManager().popBackStack();
        });
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
        @Override public void onPickIngredient(long rowId) {
            IngredientPickerDialog.show(requireContext(), vm.dictionary, ingredient -> {
                vm.state.selectTrackedIngredient(rowId, ingredient);
                bindState();
            });
        }

        @Override public boolean dictionaryHas(long rowId) {
            IngredientEditorState.Row row = vm.state.row(rowId);
            return row != null && row.ingredientId != null && byId(row.ingredientId) != null;
        }

        @Override public void onQuantity(long rowId, String text) {
            vm.state.setQuantity(rowId, text);
        }

        @Override public void onRemove(long rowId) {
            vm.state.remove(rowId);
            bindState();
        }

        @Override public void onConvert(long rowId) {
            confirmConvert(rowId);
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
            confirmConvert(rowId);
        }
    };

    private void confirmConvert(long rowId) {
        if (vm.state == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ac_ingredient_convert_title)
                .setMessage(R.string.ac_ingredient_convert_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.ac_ingredient_convert_confirm, (dialog, which) -> {
                    vm.state.convert(rowId);
                    bindState();
                })
                .show();
    }
    private Ingredient byId(UUID id) {
        for (Ingredient ingredient : vm.dictionary) if (ingredient.getId().equals(id)) return ingredient;
        return null;
    }

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

    private void sendCreate(CreateAttempt attempt) {
        save.setEnabled(false);
        vm.saving.setValue(true);
        SubmitButton.start(save);
        bindAttempt();
        host.calls().enqueue(host.api().recipe().createRecipe(attempt.householdId, attempt.key, attempt.payload),
                new ApiCallback<Recipe>() {
                    @Override public void onSuccess(@Nullable Recipe value) {
                        attempt.confirmed();
                        vm.saved.setValue(true);
                    }

                    @Override public void onError(ApiError error) {
                        if (definitelyNotExecuted(error)) attempt.failedNotExecuted();
                        else attempt.unknown();
                        vm.saving.setValue(false);
                        vm.error.setValue(R.string.ac_catalog_error);
                        if (isAdded()) {
                            SubmitButton.stop(save);
                            bindAttempt();
                        }
                    }
                });
    }

    private void retryCreate() {
        CreateAttempt attempt = vm.createAttempt;
        if (attempt == null || !attempt.canRetryOriginal()) return;
        if (!attempt.matches(currentUserId(), home)) {
            toast(R.string.ac_recipe_attempt_identity);
            return;
        }
        attempt.retrying();
        sendCreate(attempt);
    }

    private static boolean definitelyNotExecuted(ApiError error) {
        if (error == null || error.code == null) return false;
        return switch (error.code) {
            case "VALIDATION_ERROR", "NOT_FOUND", "FORBIDDEN", "HOUSEHOLD_REQUIRED",
                    "INVALID_INGREDIENT" -> true;
            default -> false;
        };
    }

    private UUID currentUserId() {
        if (host == null || host.sessions() == null) return null;
        return uuidOrNull(host.sessions().currentUserId());
    }

    private void submit() {
        if (Boolean.TRUE.equals(vm.saving.getValue()) || vm.state == null) return;
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
        if (existing == null) {
            CreateAttempt prior = vm.createAttempt;
            if (prior != null && prior.phase() == CreateAttempt.Phase.UNKNOWN) {
                toast(R.string.ac_recipe_attempt_unknown);
                bindAttempt();
                return;
            }
            CreateRecipeRequest request = new CreateRecipeRequest()
                    .name(recipeName)
                    .steps(stepList)
                    .ingredients(tracked)
                    .recipeOnlyIngredients(recipeOnly)
                    .yieldServings(IngredientImportParser.text(yieldValue))
                    .durationMinutes(minutes)
                    .tags(new LinkedHashSet<>(List.of("家庭菜谱")))
                    .imageId(imageId);
            CreateAttempt attempt = prior;
            if (attempt == null || attempt.phase() == CreateAttempt.Phase.FAILED_NOT_EXECUTED) {
                attempt = CreateAttempt.start(UUID.randomUUID(), request, currentUserId(), home);
                vm.createAttempt = attempt;
                sendCreate(attempt);
                return;
            }
            if (attempt.phase() == CreateAttempt.Phase.SENDING
                    || attempt.phase() == CreateAttempt.Phase.CONFIRMED) return;
            sendCreate(attempt);
            return;
        }

        save.setEnabled(false);
        vm.saving.setValue(true);
        SubmitButton.start(save);
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
                        vm.saved.setValue(true);
                    }

                    @Override public void onError(ApiError error) {
                        vm.saving.setValue(false);
                        vm.error.setValue(R.string.ac_catalog_error);
                        if (isAdded()) SubmitButton.stop(save);
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

    private static UUID uuidOrNull(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }
}
