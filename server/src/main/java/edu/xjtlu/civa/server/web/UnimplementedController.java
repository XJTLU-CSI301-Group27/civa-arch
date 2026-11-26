package edu.xjtlu.civa.server.web;

import edu.xjtlu.civa.server.api.MediaApi;
import edu.xjtlu.civa.server.api.PlanningApi;
import edu.xjtlu.civa.server.api.model.*;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Explicit 501 placeholders for the generated contracts that do not yet have implementations. */
@RestController
class UnimplementedController implements PlanningApi, MediaApi {

    @Override public ResponseEntity<List<PlanSlot>> commitPlan(UUID householdId, UUID key, PlanCommitRequest body) { throw new NotImplemented(); }
    @Override public ResponseEntity<PlanSlotPage> listPlanSlots(UUID householdId, String from, String to, Integer page, Integer size) { throw new NotImplemented(); }
    @Override public ResponseEntity<PlanSlot> replacePlanSlot(UUID householdId, String date, PlanMealType mealType, ReplacePlanSlotRequest body) { throw new NotImplemented(); }
    @Override public ResponseEntity<PlanSlot> setPlanSlotLock(UUID householdId, String date, PlanMealType mealType, SetPlanLockRequest body) { throw new NotImplemented(); }


    @Override public ResponseEntity<Resource> getMediaContent(UUID mediaId) { throw new NotImplemented(); }
    @Override public ResponseEntity<Media> uploadMedia(UUID key, MultipartFile file, MediaScope scope, UUID householdId) { throw new NotImplemented(); }

    static final class NotImplemented extends RuntimeException { }
}
