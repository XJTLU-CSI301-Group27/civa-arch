package edu.xjtlu.civa.feature.ac.record;

import edu.xjtlu.civa.core.ui.ApiError;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class IdempotentActionTest {
    private static ApiError error(String code) {
        return new ApiError(code, code, null, Collections.emptyList());
    }

    @Test public void retryAfterTimeoutReusesTheSameKeyAndIntent() {
        IdempotentAction action = IdempotentAction.idle();
        assertTrue(action.begin("cook"));
        String key = action.key();
        action.uncertain();
        assertTrue(action.unconfirmed());
        assertEquals(key, action.key());
        assertEquals("cook", action.summary());
        assertTrue(action.retry());
        assertEquals(key, action.key());
    }

    @Test public void aSecondTapWhileInFlightChangesNothing() {
        IdempotentAction action = IdempotentAction.idle();
        assertTrue(action.begin("cook"));
        String key = action.key();
        assertFalse(action.begin("cook again"));
        assertEquals(key, action.key());
        assertEquals(IdempotentAction.Phase.IN_FLIGHT, action.phase());
    }

    @Test public void aNewIntentAfterSuccessGetsANewKey() {
        IdempotentAction action = IdempotentAction.idle();
        assertTrue(action.begin("first"));
        String first = action.key();
        action.succeeded();
        assertEquals(IdempotentAction.Phase.IDLE, action.phase());
        assertNull(action.key());
        assertTrue(action.begin("second"));
        assertNotEquals(first, action.key());
    }

    @Test public void aRejectedIntentIsClosedAndTheNextOneGetsANewKey() {
        IdempotentAction action = IdempotentAction.idle();
        assertTrue(action.begin("first"));
        String first = action.key();
        action.rejected();
        assertEquals(IdempotentAction.Phase.IDLE, action.phase());
        assertTrue(action.begin("second"));
        assertNotEquals(first, action.key());
    }

    @Test public void aNewIntentIsBlockedUntilTheUnconfirmedOneIsResolved() {
        IdempotentAction action = IdempotentAction.idle();
        assertTrue(action.begin("first"));
        action.uncertain();
        assertFalse(action.begin("second"));
        assertEquals(IdempotentAction.Phase.UNCONFIRMED, action.phase());
    }

    @Test public void abandoningClearsTheIntent() {
        IdempotentAction action = IdempotentAction.idle();
        assertTrue(action.begin("first"));
        String first = action.key();
        action.abandon();
        assertEquals(IdempotentAction.Phase.IDLE, action.phase());
        assertNull(action.key());
        assertTrue(action.begin("second"));
        assertNotEquals(first, action.key());
    }

    @Test public void lostResponsesAreAmbiguous() {
        assertTrue(IdempotentAction.ambiguous(null));
        assertTrue(IdempotentAction.ambiguous(error("TIMEOUT")));
        assertTrue(IdempotentAction.ambiguous(error("NETWORK_ERROR")));
        assertTrue(IdempotentAction.ambiguous(error("TEMPORARILY_UNAVAILABLE")));
        assertTrue(IdempotentAction.ambiguous(error("INTERNAL_ERROR")));
        assertTrue(IdempotentAction.ambiguous(error("INVALID_RESPONSE")));
    }

    @Test public void businessRejectionsAreDefinitive() {
        assertFalse(IdempotentAction.ambiguous(error("VERSION_CONFLICT")));
        assertFalse(IdempotentAction.ambiguous(error("INSUFFICIENT_STOCK")));
        assertFalse(IdempotentAction.ambiguous(error("EXPIRED_BATCH")));
        assertFalse(IdempotentAction.ambiguous(error("VALIDATION_ERROR")));
        assertFalse(IdempotentAction.ambiguous(error("NOT_FOUND")));
        assertFalse(IdempotentAction.ambiguous(error("IDEMPOTENCY_KEY_REUSED")));
        assertFalse(IdempotentAction.ambiguous(error("RATE_LIMITED")));
    }
}
