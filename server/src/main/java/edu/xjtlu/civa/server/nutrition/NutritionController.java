package edu.xjtlu.civa.server.nutrition;

import edu.xjtlu.civa.server.api.NutritionApi;
import edu.xjtlu.civa.server.api.model.NutritionEstimate;
import edu.xjtlu.civa.server.api.model.NutritionEstimateRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

/** Nutrition estimate endpoint. Transport, caching and provider wiring live in the service. */
@RestController
class NutritionController implements NutritionApi {
    private final NutritionService service;

    NutritionController(NutritionService service) { this.service = service; }

    @Override public ResponseEntity<NutritionEstimate> estimateNutrition(NutritionEstimateRequest nutritionEstimateRequest) {
        return ResponseEntity.ok(service.estimate(actor(), nutritionEstimateRequest));
    }

    private static UUID actor() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
