package edu.xjtlu.civa.server.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.PlanningApi;
import edu.xjtlu.civa.server.api.model.PlanCommitRequest;
import edu.xjtlu.civa.server.api.model.PlanMealType;
import edu.xjtlu.civa.server.api.model.PlanSlot;
import edu.xjtlu.civa.server.api.model.PlanSlotPage;
import edu.xjtlu.civa.server.api.model.ReplacePlanSlotRequest;
import edu.xjtlu.civa.server.api.model.SetPlanLockRequest;
import edu.xjtlu.civa.server.application.CanonicalRequestDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PlanningController implements PlanningApi {
    private final PlanningService plans;
    private final CanonicalRequestDigest digest;
    private final ObjectMapper json;
    private final JsonNode schemas;

    PlanningController(PlanningService plans, CanonicalRequestDigest digest, ObjectMapper json, JsonNode schemas) {
        this.plans = plans;
        this.digest = digest;
        this.json = json;
        this.schemas = schemas;
    }

    @Override public ResponseEntity<PlanSlotPage> listPlanSlots(UUID householdId, String from, String to,
            Integer page, Integer size) {
        return ResponseEntity.ok(plans.list(actor(), householdId, from, to, page, size));
    }

    @Override public ResponseEntity<PlanSlot> replacePlanSlot(UUID householdId, String date, PlanMealType mealType,
            ReplacePlanSlotRequest body) {
        return ResponseEntity.ok(plans.replace(actor(), householdId, date, mealType, body.getItems(),
                body.getExpectedVersion()));
    }

    @Override public ResponseEntity<PlanSlot> setPlanSlotLock(UUID householdId, String date, PlanMealType mealType,
            SetPlanLockRequest body) {
        return ResponseEntity.ok(plans.lock(actor(), householdId, date, mealType, body.getLocked(),
                body.getExpectedVersion()));
    }

    @Override public ResponseEntity<List<PlanSlot>> commitPlan(UUID householdId, UUID idempotencyKey,
            PlanCommitRequest body) {
        return ResponseEntity.ok(plans.commit(actor(), householdId, idempotencyKey,
                hash("/api/v1/households/" + householdId + "/plan-commits", body, "PlanCommitRequest"), body));
    }

    private byte[] hash(String path, Object body, String schema) {
        try {
            return digest.digestJson("POST", path, json.writeValueAsString(body), schemas.get(schema));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid request", error);
        }
    }

    private static UUID actor() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
