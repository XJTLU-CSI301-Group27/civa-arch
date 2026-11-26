package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.ui.ApiError;
import java.util.List;
import org.junit.Test;

public class RecipeViewCallbacksTest {
    private final RecipeViewCallbacks scope = new RecipeViewCallbacks();
    private final ApiError cancelled = new ApiError("CANCELLED", "Cancelled", null, List.of());
    private int successes, errors;
    private String last;
    private ApiCallback<String> callback() {
        return scope.bind(new ApiCallback<String>() {
            @Override public void onSuccess(String value) { successes++; last = value; }
            @Override public void onError(ApiError error) { errors++; }
        });
    }

    @Test public void liveViewReceivesSuccessAndError() {
        scope.startView();
        ApiCallback<String> callback = callback();
        callback.onSuccess("recipe"); callback.onError(cancelled);
        assertEquals(1, successes); assertEquals(1, errors); assertEquals("recipe", last);
    }
    @Test public void cancellationAfterDestroyDoesNotTouchOldUi() {
        scope.startView(); ApiCallback<String> callback = callback();
        scope.endView(); callback.onError(cancelled);
        assertEquals(0, errors);
    }
    @Test public void queuedSuccessAfterDestroyDoesNotRenderOldView() {
        scope.startView(); ApiCallback<String> callback = callback();
        scope.endView(); callback.onSuccess("late");
        assertEquals(0, successes);
    }
    @Test public void oldSuccessCannotRenderRecreatedView() {
        scope.startView(); ApiCallback<String> old = callback();
        scope.endView(); scope.startView(); ApiCallback<String> current = callback();
        old.onSuccess("old"); current.onSuccess("current");
        assertEquals(1, successes); assertEquals("current", last);
    }
    @Test public void oldErrorCannotReplaceRecreatedViewWithOfflineState() {
        scope.startView(); ApiCallback<String> old = callback();
        scope.endView(); scope.startView(); old.onError(cancelled);
        assertEquals(0, errors);
    }
    @Test public void parallelDetailAndFavoriteCallbacksRemainLive() {
        scope.startView(); ApiCallback<String> detail = callback(), favorite = callback();
        detail.onSuccess("detail"); favorite.onSuccess("favorite");
        assertEquals(2, successes); assertEquals("favorite", last);
    }
    @Test public void callbackCreatedWithoutAViewCannotBecomeActiveLater() {
        ApiCallback<String> old = callback(); scope.startView();
        old.onSuccess("late"); old.onError(cancelled);
        assertEquals(0, successes); assertEquals(0, errors);
    }
    @Test public void nullSuccessStillReachesTheLiveViewsValidation() {
        scope.startView(); callback().onSuccess(null);
        assertEquals(1, successes); assertEquals(null, last);
    }
}
