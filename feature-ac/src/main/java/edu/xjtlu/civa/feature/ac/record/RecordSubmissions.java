package edu.xjtlu.civa.feature.ac.record;

import androidx.annotation.Nullable;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.data.ApiCalls;
import edu.xjtlu.civa.core.ui.ApiError;

/**
 * Glue between an {@link IdempotentAction} and the durable pending-write log: the intent is stored
 * before the first network attempt and removed only when the outcome is settled or explicitly
 * abandoned. An ambiguous outcome deliberately leaves the row in place.
 */
public final class RecordSubmissions {
    private static final ApiCallback<Void> IGNORE = new ApiCallback<>() {
        @Override public void onSuccess(@Nullable Void value) { }

        @Override public void onError(ApiError error) { }
    };

    private RecordSubmissions() { }

    public static void recordIntent(ApiCalls calls, String userId, @Nullable String householdId, String operation,
                                    IdempotentAction action, String summary, Runnable send,
                                    Runnable storageFailed) {
        String key = action.key();
        if (key == null) {
            storageFailed.run();
            return;
        }
        calls.pendingWrites().record(key, userId, householdId, operation, summary, null, null,
                new ApiCallback<Void>() {
                    @Override public void onSuccess(@Nullable Void value) {
                        send.run();
                    }

                    @Override public void onError(ApiError error) {
                        storageFailed.run();
                    }
                });
    }

    /** The server confirmed the write. */
    public static void settled(ApiCalls calls, @Nullable String userId, IdempotentAction action) {
        String key = action.key();
        action.succeeded();
        forget(calls, userId, key);
    }

    /** The server rejected the write and rolled it back. */
    public static void rejected(ApiCalls calls, @Nullable String userId, IdempotentAction action) {
        String key = action.key();
        action.rejected();
        forget(calls, userId, key);
    }

    /** The user gave up; the pending row is dropped without claiming a rollback. */
    public static void abandoned(ApiCalls calls, @Nullable String userId, IdempotentAction action) {
        String key = action.key();
        action.abandon();
        forget(calls, userId, key);
    }

    private static void forget(ApiCalls calls, @Nullable String userId, @Nullable String key) {
        if (key == null || userId == null || userId.isEmpty()) return;
        calls.pendingWrites().confirm(userId, key, IGNORE);
    }
}
