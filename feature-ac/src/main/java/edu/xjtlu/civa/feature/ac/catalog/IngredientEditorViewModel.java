package edu.xjtlu.civa.feature.ac.catalog;

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
    boolean initialized;
}
