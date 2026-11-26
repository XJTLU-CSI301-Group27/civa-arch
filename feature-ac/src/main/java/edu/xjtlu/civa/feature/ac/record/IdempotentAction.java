package edu.xjtlu.civa.feature.ac.record;

import androidx.annotation.Nullable;
import edu.xjtlu.civa.core.ui.ApiError;
import java.util.UUID;

/**
 * One user intent and its idempotency key.
 *
 * <p>The key is created when the intent starts, reused by every retry of that same intent and
 * discarded once the outcome is known. A new intent always gets a new key. The state machine
 * exists so a duplicate tap, a timeout retry or a recreated screen can never silently turn one
 * business operation into two records.</p>
 */
public final class IdempotentAction {
    public enum Phase { IDLE, IN_FLIGHT, UNCONFIRMED }

    private Phase phase = Phase.IDLE;
    private String key;
    private String summary;

    public static IdempotentAction idle() {
        return new IdempotentAction();
    }

    /**
     * Starts a new intent. Returns false when another intent of the same kind is still running or
     * still has an unconfirmed outcome: the caller must resolve that one first.
     */
    public boolean begin(String summary) {
        if (phase != Phase.IDLE) return false;
        this.key = UUID.randomUUID().toString();
        this.summary = summary;
        phase = Phase.IN_FLIGHT;
        return true;
    }

    /** Re-sends the stored intent with its original key after an ambiguous outcome. */
    public boolean retry() {
        if (phase != Phase.UNCONFIRMED) return false;
        phase = Phase.IN_FLIGHT;
        return true;
    }

    /** The server confirmed the write; the intent is closed. */
    public void succeeded() {
        clear();
    }

    /** The request was rejected and the server guarantees no business side effect. */
    public void rejected() {
        clear();
    }

    /** The outcome is unknown (timeout, lost response); the key and content are kept. */
    public void uncertain() {
        if (phase != Phase.IDLE) phase = Phase.UNCONFIRMED;
    }

    /** The user explicitly gives up; the server may still have applied the write. */
    public void abandon() {
        clear();
    }

    public Phase phase() {
        return phase;
    }

    public boolean inFlight() {
        return phase == Phase.IN_FLIGHT;
    }

    public boolean unconfirmed() {
        return phase == Phase.UNCONFIRMED;
    }

    @Nullable public String key() {
        return key;
    }

    @Nullable public String summary() {
        return summary;
    }

    /**
     * A timeout or a server-side failure says nothing about whether the transaction committed, so
     * the original key and payload must survive. Only explicit business rejections are definitive.
     */
    public static boolean ambiguous(@Nullable ApiError error) {
        if (error == null) return true;
        String code = error.code;
        return "TIMEOUT".equals(code)
                || "NETWORK_ERROR".equals(code)
                || "TEMPORARILY_UNAVAILABLE".equals(code)
                || "INTERNAL_ERROR".equals(code)
                || "INVALID_RESPONSE".equals(code);
    }

    private void clear() {
        phase = Phase.IDLE;
        key = null;
        summary = null;
    }
}
