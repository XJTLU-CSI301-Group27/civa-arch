package edu.xjtlu.civa.feature.ac.catalog;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import edu.xjtlu.civa.api.model.Ingredient;
import edu.xjtlu.civa.api.model.Recipe;
import java.util.List;
import java.util.UUID;

/** Retains the pure editor state across configuration changes; no view or adapter is stored. */
public final class IngredientEditorViewModel extends ViewModel {
    IngredientEditorState state;
    Recipe existing;
    List<Ingredient> dictionary = List.of();
    UUID imageId;
    CreateAttempt createAttempt;
    boolean initialized;
    final MutableLiveData<Boolean> saving = new MutableLiveData<>(false);
    final MutableLiveData<Integer> error = new MutableLiveData<>();
    final MutableLiveData<Boolean> saved = new MutableLiveData<>(false);
}
