package edu.xjtlu.civa.feature.ac.catalog;

import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import edu.xjtlu.civa.api.model.CreatePersonalRecipeRequest;
import edu.xjtlu.civa.api.model.PersonalRecipe;
import edu.xjtlu.civa.api.model.PersonalRecipeIngredient;
import edu.xjtlu.civa.api.model.MediaScope;
import edu.xjtlu.civa.api.model.UpdatePersonalRecipeRequest;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.data.MediaImages;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.feature.ac.AcHost;
import edu.xjtlu.civa.feature.ac.R;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import retrofit2.Call;

/** Minimal personal recipe editor: free-text ingredients and steps, one per line. */
public final class PersonalRecipeEditorFragment extends Fragment {
    private static final String ARG_ID = "recipeId";

    private AcHost host;
    private String recipeId;
    private PersonalRecipe existing;
    private EditText name, category, description, ingredients, steps;
    private MaterialButton save, pickImage, removeImage;
    private ImageView image;
    private UUID imageId;
    private boolean uploading;
    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::uploadImage);

    public static PersonalRecipeEditorFragment create(@Nullable String recipeId) {
        PersonalRecipeEditorFragment fragment = new PersonalRecipeEditorFragment();
        Bundle args = new Bundle();
        args.putString(ARG_ID, recipeId);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.ac_fragment_recipe_editor, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        host = (AcHost) requireActivity();
        String entityId = getArguments() == null ? null : getArguments().getString(ARG_ID);
        recipeId = RecipeCard.isPersonal(entityId) ? RecipeCard.idOf(entityId) : null;
        name = view.findViewById(R.id.ac_editor_name);
        category = view.findViewById(R.id.ac_editor_category);
        description = view.findViewById(R.id.ac_editor_description);
        ingredients = view.findViewById(R.id.ac_editor_ingredients);
        steps = view.findViewById(R.id.ac_editor_steps);
        save = view.findViewById(R.id.ac_editor_save);
        image = view.findViewById(R.id.ac_editor_image);
        pickImage = view.findViewById(R.id.ac_editor_image_pick);
        removeImage = view.findViewById(R.id.ac_editor_image_remove);
        pickImage.setOnClickListener(ignored -> imagePicker.launch("image/*"));
        removeImage.setOnClickListener(ignored -> {
            imageId = null;
            image.setImageDrawable(null);
            image.setVisibility(View.GONE);
        });
        TextView heading = view.findViewById(R.id.ac_editor_heading);
        save.setOnClickListener(ignored -> submit());
        if (recipeId != null) {
            heading.setText(R.string.ac_editor_edit_title);
            loadExisting();
        } else {
            heading.setText(R.string.ac_editor_new_title);
        }
        view.findViewById(R.id.ac_editor_cancel).setOnClickListener(ignored ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
    }

    private void loadExisting() {
        host.calls().enqueue(host.api().personalRecipes().getPersonalRecipe(UUID.fromString(recipeId)),
                new ApiCallback<PersonalRecipe>() {
                    @Override public void onSuccess(@Nullable PersonalRecipe value) {
                        if (!isAdded()) return;
                        if (value == null) return;
                        existing = value;
                        name.setText(value.getName());
                        category.setText(value.getCategory());
                        description.setText(value.getDescription());
                        ingredients.setText(joinIngredients(value.getIngredients()));
                        steps.setText(String.join("\n", value.getSteps()));
                        imageId = value.getImageId();
                        if (imageId != null) {
                            image.setVisibility(View.VISIBLE);
                            MediaImages.load(host.api(), host.calls(), imageId, image);
                        }
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ac_catalog_error);
                    }
                });
    }

    private void uploadImage(@Nullable Uri uri) {
        if (uri == null || !isAdded()) return;
        uploading = true;
        pickImage.setEnabled(false);
        toast(R.string.ac_editor_image_uploading);
        MediaImages.upload(requireContext(), host.api(), host.calls(), uri, MediaScope.PERSONAL, null, id -> {
            if (!isAdded()) return;
            uploading = false;
            pickImage.setEnabled(true);
            if (id == null) { toast(R.string.ac_editor_image_failed); return; }
            imageId = id;
            image.setVisibility(View.VISIBLE);
            image.setImageURI(uri);
        });
    }

    private void submit() {
        if (uploading) { toast(R.string.ac_editor_image_uploading); return; }
        String title = name.getText().toString().trim();
        List<String> stepLines = lines(steps.getText().toString());
        List<PersonalRecipeIngredient> ingredientLines = ingredientItems(ingredients.getText().toString());
        if (title.isEmpty() || stepLines.isEmpty() || ingredientLines.isEmpty()) {
            toast(R.string.ac_editor_incomplete);
            return;
        }
        save.setEnabled(false);
        if (recipeId == null) {
            CreatePersonalRecipeRequest request = new CreatePersonalRecipeRequest()
                    .name(title)
                    .category(category.getText().toString().trim())
                    .description(description.getText().toString().trim())
                    .steps(stepLines)
                    .ingredients(ingredientLines)
                    .imageId(imageId);
            host.calls().enqueue(host.api().personalRecipes().createPersonalRecipe(UUID.randomUUID(), request),
                    new ApiCallback<PersonalRecipe>() {
                        @Override public void onSuccess(@Nullable PersonalRecipe value) {
                            if (!isAdded()) return;
                            toast(R.string.ac_editor_saved);
                            getParentFragmentManager().popBackStack();
                        }

                        @Override public void onError(ApiError error) {
                            if (!isAdded()) return;
                            save.setEnabled(true);
                            toast(R.string.ac_catalog_error);
                        }
                    });
        } else if (existing != null) {
            UpdatePersonalRecipeRequest request = new UpdatePersonalRecipeRequest()
                    .name(title)
                    .category(category.getText().toString().trim())
                    .description(description.getText().toString().trim())
                    .steps(stepLines)
                    .ingredients(ingredientLines)
                    .imageId(imageId)
                    .expectedVersion(existing.getVersion());
            host.calls().enqueue(host.api().personalRecipes().updatePersonalRecipe(existing.getId(), request),
                    new ApiCallback<PersonalRecipe>() {
                        @Override public void onSuccess(@Nullable PersonalRecipe value) {
                            if (!isAdded()) return;
                            if (value != null) CatalogCache.savePersonal(requireContext(), value);
                            toast(R.string.ac_editor_saved);
                            getParentFragmentManager().popBackStack();
                        }

                        @Override public void onError(ApiError error) {
                            if (!isAdded()) return;
                            save.setEnabled(true);
                            toast(R.string.ac_catalog_error);
                        }
                    });
        }
    }

    static List<String> lines(String text) {
        List<String> result = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    static List<PersonalRecipeIngredient> ingredientItems(String text) {
        List<PersonalRecipeIngredient> result = new ArrayList<>();
        for (String line : lines(text)) {
            result.add(new PersonalRecipeIngredient().name(line));
        }
        return result;
    }

    private static String joinIngredients(List<PersonalRecipeIngredient> items) {
        List<String> lines = new ArrayList<>();
        for (PersonalRecipeIngredient item : items) lines.add(item.getName() == null ? "" : item.getName());
        return String.join("\n", lines);
    }

    private void toast(int text) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show();
    }
}
