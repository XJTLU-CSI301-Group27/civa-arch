package edu.xjtlu.civa.feature.ac.record;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RecordSubmissionViewModelTest {
    @Test public void keepsTheIntentAndItsKeyForARetry() {
        RecordSubmissionViewModel submissions = new RecordSubmissionViewModel();
        IdempotentAction action = submissions.action(RecordSubmissionViewModel.COOKING);
        assertTrue(action.begin("cook"));
        String key = action.key();
        submissions.remember(RecordSubmissionViewModel.COOKING, "payload");
        action.uncertain();

        assertEquals(key, submissions.action(RecordSubmissionViewModel.COOKING).key());
        assertEquals("payload", submissions.intent(RecordSubmissionViewModel.COOKING, String.class));
    }

    @Test public void forgettingDropsTheKeyAndTheIntent() {
        RecordSubmissionViewModel submissions = new RecordSubmissionViewModel();
        IdempotentAction action = submissions.action(RecordSubmissionViewModel.MEAL);
        assertTrue(action.begin("meal"));
        submissions.remember(RecordSubmissionViewModel.MEAL, "payload");
        submissions.forget(RecordSubmissionViewModel.MEAL);

        assertNull(submissions.intent(RecordSubmissionViewModel.MEAL, String.class));
        IdempotentAction next = submissions.action(RecordSubmissionViewModel.MEAL);
        assertEquals(IdempotentAction.Phase.IDLE, next.phase());
        assertTrue(next.begin("next meal"));
        assertNotNull(next.key());
    }

    @Test public void cookingAndMealIntentsAreIndependent() {
        RecordSubmissionViewModel submissions = new RecordSubmissionViewModel();
        assertTrue(submissions.action(RecordSubmissionViewModel.COOKING).begin("cook"));
        assertTrue(submissions.action(RecordSubmissionViewModel.MEAL).begin("meal"));
        submissions.action(RecordSubmissionViewModel.COOKING).uncertain();
        assertFalse(submissions.action(RecordSubmissionViewModel.COOKING).inFlight());
        assertTrue(submissions.action(RecordSubmissionViewModel.MEAL).inFlight());
        assertTrue(submissions.action(RecordSubmissionViewModel.COOKING).unconfirmed());
        assertFalse(submissions.action(RecordSubmissionViewModel.MEAL).unconfirmed());
    }
}
