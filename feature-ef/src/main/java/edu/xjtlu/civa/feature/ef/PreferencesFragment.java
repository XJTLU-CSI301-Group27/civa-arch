package edu.xjtlu.civa.feature.ef;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.IngredientPage;
import edu.xjtlu.civa.api.model.Preference;
import edu.xjtlu.civa.api.model.UpdatePreferenceRequest;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import edu.xjtlu.civa.core.ui.Route;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Personal taste preferences: liked tags and excluded ingredients. */
public final class PreferencesFragment extends Fragment {
    private ProfileHost host;
    private EditText tags;
    private MaterialButton saveButton;
    private TextView excludedSummary, status;
    private final Set<UUID> excluded = new LinkedHashSet<>();
    private final Map<UUID, String> ingredientNames = new LinkedHashMap<>();
    private long version;
    private boolean loading;

    public PreferencesFragment() { super(R.layout.ef_fragment_preferences); }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        host = (ProfileHost) requireActivity();
        tags = view.findViewById(R.id.ef_preferences_tags);
        saveButton = view.findViewById(R.id.ef_preferences_save);
        excludedSummary = view.findViewById(R.id.ef_preferences_excluded_summary);
        status = view.findViewById(R.id.ef_preferences_status);
        view.findViewById(R.id.ef_preferences_excluded_pick).setOnClickListener(ignored -> pickExcluded());
        saveButton.setOnClickListener(ignored -> save());
        load();
    }

    private void load() {
        loading = true;
        status.setText(R.string.ef_preferences_loading);
        host.calls().enqueue(host.api().profile().getPreferences(), new ApiCallback<Preference>() {
            @Override public void onSuccess(@Nullable Preference value) {
                if (!isAdded()) return;
                loading = false;
                version = value == null || value.getVersion() == null ? 0 : value.getVersion();
                tags.setText(value == null || value.getLikedTags() == null ? ""
                        : String.join(getString(R.string.ef_l10n_tag_separator), value.getLikedTags()));
                excluded.clear();
                if (value != null && value.getExcludedIngredientIds() != null) {
                    excluded.addAll(value.getExcludedIngredientIds());
                }
                paintExcluded();
                status.setText(getString(R.string.ef_preferences_loaded, (int) version));
            }

            @Override public void onError(ApiError error) {
                if (!isAdded()) return;
                loading = false;
                status.setText(R.string.ef_preferences_error);
            }
        });
    }

    private void save() {
        if (loading) return;
        java.util.Set<String> liked = new LinkedHashSet<>();
        // i18n: non-UI — input syntax: the user may type an ASCII or a full-width comma.
        for (String raw : tags.getText().toString().split("[，,]")) {
            String value = raw.trim();
            if (!value.isEmpty()) liked.add(value);
        }
        UpdatePreferenceRequest request = new UpdatePreferenceRequest()
                .likedTags(liked)
                .excludedIngredientIds(new LinkedHashSet<>(excluded))
                .expectedVersion(version);
        loading = true;
        edu.xjtlu.civa.core.ui.SubmitButton.start(saveButton);
        host.calls().enqueue(host.api().profile().updatePreferences(request), new ApiCallback<Preference>() {
            @Override public void onSuccess(@Nullable Preference value) {
                if (!isAdded()) return;
                loading = false;
                edu.xjtlu.civa.core.ui.SubmitButton.stop(saveButton);
                version = value == null || value.getVersion() == null ? version + 1 : value.getVersion();
                toast(R.string.ef_preferences_saved);
                status.setText(getString(R.string.ef_preferences_loaded, (int) version));
            }

            @Override public void onError(ApiError error) {
                if (!isAdded()) return;
                loading = false;
                edu.xjtlu.civa.core.ui.SubmitButton.stop(saveButton);
                // A stale version means another device changed preferences: reload it.
                if ("VERSION_CONFLICT".equals(error == null ? null : error.code)) {
                    toast(R.string.ef_preferences_conflict);
                    load();
                } else {
                    status.setText(R.string.ef_preferences_error);
                }
            }
        });
    }

    private void pickExcluded() {
        if (loading) return;
        host.calls().enqueue(host.api().inventory().listIngredients(null, 0, 100),
                new ApiCallback<IngredientPage>() {
                    @Override public void onSuccess(@Nullable IngredientPage value) {
                        if (!isAdded()) return;
                        ingredientNames.clear();
                        List<String> names = new ArrayList<>();
                        if (value != null && value.getItems() != null) {
                            for (Ingredient ingredient : value.getItems()) {
                                ingredientNames.put(ingredient.getId(), ingredient.getName());
                                names.add(ingredient.getName());
                            }
                        }
                        boolean[] checked = new boolean[names.size()];
                        List<UUID> ids = new ArrayList<>(ingredientNames.keySet());
                        for (int i = 0; i < ids.size(); i++) checked[i] = excluded.contains(ids.get(i));
                        new MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.ef_preferences_pick_excluded)
                                .setMultiChoiceItems(names.toArray(new String[0]), checked,
                                        (dialog, which, isChecked) -> {
                                            if (isChecked) excluded.add(ids.get(which));
                                            else excluded.remove(ids.get(which));
                                        })
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.ef_preferences_done, (dialog, which) -> paintExcluded())
                                .show();
                    }

                    @Override public void onError(ApiError error) {
                        if (!isAdded()) return;
                        toast(R.string.ef_preferences_error);
                    }
                });
    }

    private void paintExcluded() {
        if (excluded.isEmpty()) {
            excludedSummary.setText(R.string.ef_preferences_none_excluded);
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID id : excluded) {
            String name = ingredientNames.get(id);
            names.add(name == null ? getString(R.string.ef_preferences_unknown_ingredient) : name);
        }
        excludedSummary.setText(getString(R.string.ef_preferences_excluded_summary, names.size(),
                String.join(getString(R.string.ef_l10n_name_separator), names)));
    }

    private void toast(int text) {
        edu.xjtlu.civa.core.ui.Feedback.show(requireActivity(), text, false);
    }
}
