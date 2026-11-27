package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public class CreateAttemptTest {
    private final UUID key = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID home = UUID.randomUUID();

    @Test public void keyAndPayloadStayBoundForTheAttemptLifetime() {
        CreateRecipeRequest payload = request();
        CreateAttempt attempt = CreateAttempt.start(key, payload, user, home);

        assertSame(key, attempt.key);
        assertSame(payload, attempt.payload);
        assertEquals(CreateAttempt.Phase.SENDING, attempt.phase());
        assertFalse(attempt.canStartNew());
        assertFalse(attempt.canRetryOriginal());
    }

    @Test public void uncertainResultAllowsOnlyOriginalRetry() {
        CreateAttempt attempt = CreateAttempt.start(key, request(), user, home);
        attempt.unknown();

        assertTrue(attempt.canRetryOriginal());
        assertFalse(attempt.canStartNew());
        attempt.retrying();
        assertEquals(CreateAttempt.Phase.SENDING, attempt.phase());
        attempt.unknown();
        attempt.failedNotExecuted();
        assertTrue(attempt.canStartNew());
        assertFalse(attempt.canRetryOriginal());
    }

    @Test public void identityIsBoundToTheAttempt() {
        CreateAttempt attempt = CreateAttempt.start(key, request(), user, home);
        assertTrue(attempt.matches(user, home));
        assertFalse(attempt.matches(UUID.randomUUID(), home));
        assertFalse(attempt.matches(user, UUID.randomUUID()));
    }

    @Test public void recoveredSendingIntentIsUnknownAndNotAutoStarted() {
        CreateAttempt attempt = CreateAttempt.recovered(key, request(), "fingerprint", user, home);
        assertEquals(CreateAttempt.Phase.UNKNOWN, attempt.phase());
        assertTrue(attempt.canRetryOriginal());
        assertFalse(attempt.canStartNew());
        assertEquals("fingerprint", attempt.fingerprint);
    }

    private static CreateRecipeRequest request() {
        return new CreateRecipeRequest().name("probe").steps(List.of("x"))
                .ingredients(List.of()).recipeOnlyIngredients(List.of())
                .yieldServings("2").durationMinutes(10).tags(Set.of());
    }
}
