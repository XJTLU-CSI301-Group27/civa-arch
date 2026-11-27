package edu.xjtlu.civa.feature.ac.catalog;

import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import com.google.gson.Gson;
import java.util.UUID;

/**
 * Immutable key+payload snapshot plus an explicit phase. A key is never paired with a different
 * payload; an uncertain result can only retry the original snapshot.
 */
final class CreateAttempt {
    enum Phase { SENDING, CONFIRMED, FAILED_NOT_EXECUTED, UNKNOWN }

    final UUID key;
    final CreateRecipeRequest payload;
    final String fingerprint;
    final UUID userId;
    final UUID householdId;
    private Phase phase;

    private CreateAttempt(UUID key, CreateRecipeRequest payload, String fingerprint, UUID userId,
                          UUID householdId, Phase phase) {
        this.key = key;
        this.payload = payload;
        this.fingerprint = fingerprint;
        this.userId = userId;
        this.householdId = householdId;
        this.phase = phase;
    }

    static CreateAttempt start(UUID key, CreateRecipeRequest payload, UUID userId, UUID householdId) {
        return new CreateAttempt(key, payload, new Gson().toJson(payload), userId, householdId,
                Phase.SENDING);
    }

    /** A persisted SENDING intent is recovered conservatively as UNKNOWN, never auto-replayed. */
    static CreateAttempt recovered(UUID key, CreateRecipeRequest payload, String fingerprint,
                                   UUID userId, UUID householdId) {
        return new CreateAttempt(key, payload, fingerprint, userId, householdId, Phase.UNKNOWN);
    }

    Phase phase() { return phase; }

    void confirmed() { phase = Phase.CONFIRMED; }

    void failedNotExecuted() { phase = Phase.FAILED_NOT_EXECUTED; }

    void unknown() { phase = Phase.UNKNOWN; }

    void retrying() { phase = Phase.SENDING; }

    boolean canRetryOriginal() { return phase == Phase.UNKNOWN; }

    boolean canStartNew() { return phase == Phase.FAILED_NOT_EXECUTED; }

    boolean matches(UUID user, UUID home) {
        return user != null && home != null && user.equals(userId) && home.equals(householdId);
    }
}
