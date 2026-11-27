package edu.xjtlu.civa.feature.ac.catalog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import com.google.gson.Gson;
import edu.xjtlu.civa.api.model.CreateRecipeRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public class CreateAttemptStoreCodecTest {
    private final Gson gson = new Gson();
    private final String key = UUID.randomUUID().toString();
    private final String user = UUID.randomUUID().toString();
    private final String home = UUID.randomUUID().toString();
    private final CreateRecipeRequest payload = new CreateRecipeRequest()
            .name("probe").steps(List.of("x")).ingredients(List.of())
            .recipeOnlyIngredients(List.of()).yieldServings("2").durationMinutes(10);
    private final String requestJson = gson.toJson(payload);

    @Test public void validRecordDecodes() {
        CreateAttempt attempt = decode(key, user, home, body(1, CreateAttemptStore.OPERATION,
                requestJson, "IMPORT"));
        assertEquals(CreateAttempt.Phase.UNKNOWN, attempt.phase());
        assertEquals(CreateAttempt.Source.IMPORT, attempt.source);
    }

    @Test public void missingPayloadIsCorrupt() {
        Map<String, Object> body = body(1, CreateAttemptStore.OPERATION, requestJson, "MANUAL");
        body.remove("payload");
        assertNull(decode(key, user, home, body));
    }

    @Test public void fingerprintMismatchIsCorrupt() {
        assertNull(decode(key, user, home, body(1, CreateAttemptStore.OPERATION,
                "different", "MANUAL")));
    }

    @Test public void invalidKeyIsCorrupt() {
        assertNull(decode("not-a-uuid", user, home, body(1, CreateAttemptStore.OPERATION,
                requestJson, "MANUAL")));
    }

    @Test public void operationMismatchIsCorrupt() {
        assertNull(CreateAttemptStore.decode(key, user, home, "SOME_OTHER_OPERATION",
                gson.toJson(body(1, CreateAttemptStore.OPERATION, requestJson, "MANUAL"))));
        assertNull(decode(key, user, home, body(1, "SOME_OTHER_OPERATION", requestJson, "MANUAL")));
    }

    @Test public void invalidIdentityIsCorrupt() {
        assertNull(decode(key, "not-a-uuid", home, body(1, CreateAttemptStore.OPERATION,
                requestJson, "MANUAL")));
        assertNull(decode(key, user, "not-a-uuid", body(1, CreateAttemptStore.OPERATION,
                requestJson, "MANUAL")));
    }

    @Test public void unsupportedVersionIsCorrupt() {
        assertNull(decode(key, user, home, body(2, CreateAttemptStore.OPERATION,
                requestJson, "MANUAL")));
    }

    private CreateAttempt decode(String recordKey, String recordUser, String recordHome,
                                 Map<String, Object> body) {
        return CreateAttemptStore.decode(recordKey, recordUser, recordHome,
                CreateAttemptStore.OPERATION, gson.toJson(body));
    }

    private Map<String, Object> body(int version, String operation, String fingerprint,
                                     String source) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("operation", operation);
        body.put("fingerprint", fingerprint);
        body.put("source", source);
        body.put("payload", gson.fromJson(requestJson, Object.class));
        return body;
    }
}
