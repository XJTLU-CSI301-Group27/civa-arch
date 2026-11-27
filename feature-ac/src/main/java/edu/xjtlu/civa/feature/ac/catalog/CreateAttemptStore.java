package edu.xjtlu.civa.feature.ac.catalog;

import com.google.gson.Gson;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.core.data.ApiCallback;
import edu.xjtlu.civa.core.data.ApiCalls;
import edu.xjtlu.civa.core.data.PendingWrite;
import edu.xjtlu.civa.core.data.PendingWriteStore;
import edu.xjtlu.civa.core.ui.ApiError;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Minimal create-request adapter over the existing pending-write store. */
final class CreateAttemptStore {
    static final String OPERATION = "CREATE_HOUSEHOLD_RECIPE";
    private static final Gson GSON = new Gson();
    private final PendingWriteStore store;

    CreateAttemptStore(ApiCalls calls) {
        this.store = calls.pendingWrites();
    }

    void record(CreateAttempt attempt, ApiCallback<Void> callback) {
        store.record(attempt.key.toString(), attempt.userId.toString(),
                attempt.householdId.toString(), OPERATION, encode(attempt), null, null, callback);
    }

    void markUnknown(CreateAttempt attempt, ApiCallback<Void> callback) {
        store.markForReview(attempt.userId.toString(), attempt.key.toString(), callback);
    }

    void confirm(CreateAttempt attempt, ApiCallback<Void> callback) {
        store.confirm(attempt.userId.toString(), attempt.key.toString(), callback);
    }

    /** Account-scoped; returns at most one matching create for the current household. Never sends. */
    void recover(String userId, UUID householdId, ApiCallback<CreateAttempt> callback) {
        recover(userId, householdId, null, callback);
    }

    void recover(String userId, UUID householdId, CreateAttempt.Source required,
                 ApiCallback<CreateAttempt> callback) {
        try {
            store.listPending(userId, new ApiCallback<List<PendingWrite>>() {
                @Override public void onSuccess(List<PendingWrite> value) {
                    if (value != null) {
                        for (PendingWrite write : value) {
                            if (!OPERATION.equals(write.operationId)) continue;
                            CreateAttempt attempt = decode(write);
                            if (attempt == null) {
                                callback.onError(new ApiError("LOCAL_PENDING_CORRUPT",
                                        "Pending create record is corrupt", null, Collections.emptyList()));
                                return;
                            }
                            if (required != null && attempt.source != required) continue;
                            if (householdId != null && householdId.equals(attempt.householdId)) {
                                callback.onSuccess(attempt);
                                return;
                            }
                        }
                    }
                    callback.onSuccess(null);
                }

                @Override public void onError(ApiError error) { callback.onError(error); }
            });
        } catch (RuntimeException ex) {
            callback.onError(storageError());
        }
    }

    private static String encode(CreateAttempt attempt) {
        Persisted persisted = new Persisted();
        persisted.version = 1;
        persisted.operation = OPERATION;
        persisted.fingerprint = attempt.fingerprint;
        persisted.source = attempt.source == null ? CreateAttempt.Source.MANUAL.name() : attempt.source.name();
        persisted.payload = attempt.payload;
        return GSON.toJson(persisted);
    }

    private static CreateAttempt decode(PendingWrite write) {
        return decode(write.key, write.userId, write.householdId, write.operationId,
                write.canonicalPayload);
    }

    /** Package-visible codec seam for the six corrupt-record tests. */
    static CreateAttempt decode(String key, String userId, String householdId, String operationId,
                                String canonicalPayload) {
        if (!OPERATION.equals(operationId)) return null;
        if (userId == null || householdId == null || canonicalPayload == null) return null;
        Persisted persisted;
        try {
            persisted = GSON.fromJson(canonicalPayload, Persisted.class);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (persisted == null || persisted.version != 1 || persisted.payload == null
                || persisted.fingerprint == null) return null;
        if (!OPERATION.equals(persisted.operation)) return null;
        // The fingerprint is the integrity binding between this key and this payload.
        if (!GSON.toJson(persisted.payload).equals(persisted.fingerprint)) return null;
        try {
            CreateAttempt.Source source;
            try {
                source = persisted.source == null ? CreateAttempt.Source.MANUAL
                        : CreateAttempt.Source.valueOf(persisted.source);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
            return CreateAttempt.recovered(UUID.fromString(key), persisted.payload,
                    persisted.fingerprint, UUID.fromString(userId),
                    UUID.fromString(householdId), source);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static ApiError storageError() {
        return new ApiError("LOCAL_STORAGE_ERROR", "Pending create storage is unavailable", null,
                Collections.emptyList());
    }

    private static final class Persisted {
        int version;
        String operation;
        String fingerprint;
        String source;
        CreateRecipeRequest payload;
    }
}
