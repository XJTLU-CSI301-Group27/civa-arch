package edu.xjtlu.civa.server.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.RecordsApi;
import edu.xjtlu.civa.server.api.model.ConfirmCookingRequest;
import edu.xjtlu.civa.server.api.model.CookingEvent;
import edu.xjtlu.civa.server.api.model.CookingEventPage;
import edu.xjtlu.civa.server.api.model.CookingPreview;
import edu.xjtlu.civa.server.api.model.CookingPreviewRequest;
import edu.xjtlu.civa.server.api.model.CreateMealEntryRequest;
import edu.xjtlu.civa.server.api.model.MealEntry;
import edu.xjtlu.civa.server.api.model.MealEntryPage;
import edu.xjtlu.civa.server.api.model.PeriodSummary;
import edu.xjtlu.civa.server.api.model.UpdateMealEntryRequest;
import edu.xjtlu.civa.server.application.CanonicalRequestDigest;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import edu.xjtlu.civa.server.web.NotImplementedException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Food diary entries are user-scoped; cooking confirmation is a household flow still queued. */
@RestController
class MealEntryController implements RecordsApi {
    private final MealEntryService entries;
    private final CanonicalRequestDigest digest;
    private final ObjectMapper json;
    private final JsonNode schemas;

    MealEntryController(MealEntryService entries, CanonicalRequestDigest digest, ObjectMapper json, JsonNode schemas) {
        this.entries = entries;
        this.digest = digest;
        this.json = json;
        this.schemas = schemas;
    }

    @Override public ResponseEntity<MealEntryPage> listMealEntries(String from, String to, Integer page, Integer size) {
        return ResponseEntity.ok(entries.list(actor(), date(from), date(to), page, size));
    }

    @Override public ResponseEntity<MealEntry> createMealEntry(UUID idempotencyKey, CreateMealEntryRequest body) {
        IdempotencyExecutor.Result result = entries.create(actor(), idempotencyKey,
                hash("/api/v1/me/meal-entries", body, "CreateMealEntryRequest"), body);
        try {
            return ResponseEntity.status(result.status).body(json.readValue(result.body, MealEntry.class));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid stored meal entry", invalid);
        }
    }

    @Override public ResponseEntity<MealEntry> updateMealEntry(UUID entryId, UpdateMealEntryRequest body) {
        return ResponseEntity.ok(entries.update(actor(), entryId, body));
    }

    @Override public ResponseEntity<Void> deleteMealEntry(UUID entryId, Long version) {
        entries.delete(actor(), entryId, version);
        return ResponseEntity.noContent().build();
    }

    @Override public ResponseEntity<PeriodSummary> getMealSummary(String from, String to) {
        return ResponseEntity.ok(entries.summary(actor(), date(from), date(to)));
    }

    @Override public ResponseEntity<CookingEventPage> listCookingEvents(UUID householdId, UUID recipeId, String from,
            String to, Integer page, Integer size) {
        throw new NotImplementedException();
    }

    @Override public ResponseEntity<CookingPreview> previewCooking(UUID householdId, CookingPreviewRequest body) {
        throw new NotImplementedException();
    }

    @Override public ResponseEntity<CookingEvent> confirmCooking(UUID householdId, UUID key,
            ConfirmCookingRequest body) {
        throw new NotImplementedException();
    }

    private byte[] hash(String path, Object body, String schema) {
        try {
            return digest.digestJson("POST", path, json.writeValueAsString(body), schemas.get(schema));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid request", error);
        }
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) {
            throw new RecordFault("INVALID_RANGE");
        }
    }

    private static UUID actor() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
