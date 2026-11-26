package edu.xjtlu.civa.feature.ac.record;

import androidx.lifecycle.ViewModel;
import java.util.HashMap;
import java.util.Map;

/**
 * Activity-scoped submission state for the record writes. A rotation, a tab switch or a fragment
 * replacement reuses the same instance, so an in-flight or unconfirmed intent keeps its key and
 * its original request instead of starting a second business operation.
 */
public final class RecordSubmissionViewModel extends ViewModel {
    /** Operation ids also stored in PendingWriteStore; they must stay stable. */
    public static final String COOKING = "confirmCooking";
    public static final String MEAL = "createMealEntry";

    private final Map<String, IdempotentAction> actions = new HashMap<>();
    private final Map<String, Object> intents = new HashMap<>();

    public IdempotentAction action(String operation) {
        return actions.computeIfAbsent(operation, ignored -> IdempotentAction.idle());
    }

    /** Keeps the exact request body that produced the current key. */
    public void remember(String operation, Object intent) {
        intents.put(operation, intent);
    }

    public <T> T intent(String operation, Class<T> type) {
        Object value = intents.get(operation);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    /** Drops both the key and the stored body once the outcome is known. */
    public void forget(String operation) {
        actions.remove(operation);
        intents.remove(operation);
    }
}
