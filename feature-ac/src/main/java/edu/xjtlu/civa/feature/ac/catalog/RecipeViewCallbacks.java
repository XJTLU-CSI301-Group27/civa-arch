package edu.xjtlu.civa.feature.ac.catalog;

import androidx.annotation.Nullable;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;

/** Main-thread callbacks belong to one recipe view, not the retained Fragment. */
final class RecipeViewCallbacks {
    private long generation;
    private boolean active;

    void startView() {
        generation++;
        active = true;
    }

    void endView() {
        active = false;
        generation++;
    }

    <T> ApiCallback<T> bind(ApiCallback<T> callback) {
        long owner = generation;
        return new ApiCallback<T>() {
            @Override public void onSuccess(@Nullable T value) {
                if (active && owner == generation) callback.onSuccess(value);
            }

            @Override public void onError(ApiError error) {
                if (active && owner == generation) callback.onError(error);
            }
        };
    }
}
