package edu.xjtlu.civa.feature.bd;

import edu.xjtlu.civa.api.model.NutritionBasis;
import edu.xjtlu.civa.api.model.NutritionEstimate;
import edu.xjtlu.civa.api.model.NutritionEstimateRequest;
import java.math.BigDecimal;
import java.util.ArrayList;

/**
 * Maps the contract DTOs to the local estimate model. Transport, authentication, caching and
 * the provider key all live on the server now; this class owns only the request shape and the
 * defensive re-validation of the response (unknown metrics stay null, never zero).
 */
final class BdNutritionClient {
    enum Problem { AUTHORIZATION, BALANCE, RATE_LIMIT, NETWORK, INVALID_RESPONSE, SERVER, CONFIGURATION }

    private BdNutritionClient() { }

    static NutritionEstimateRequest request(BdDraft.Dish dish) {
        return new NutritionEstimateRequest()
                .name(dish.name)
                .ingredients(new ArrayList<>(dish.ingredients))
                .steps(new ArrayList<>(dish.steps));
    }

    static BdNutrition map(BdDraft.Dish dish, NutritionEstimate estimate) {
        if (estimate == null || estimate.getBasis() != NutritionBasis.PER_100_G) {
            throw new IllegalArgumentException("Wrong serving basis");
        }
        String tips = estimate.getTips() == null ? "" : estimate.getTips().trim();
        if (tips.isEmpty() || tips.length() > 600) throw new IllegalArgumentException("Invalid tips");
        BdNutrition result = new BdNutrition();
        result.inputHash = BdNutrition.fingerprint(dish);
        result.basis = "per_100g";
        result.generatedAtMillis = System.currentTimeMillis();
        result.tips = tips;
        result.energyKcal = number(estimate.getEnergyKcal(), 1000);
        result.carbsG = number(estimate.getCarbsG(), 100);
        result.proteinG = number(estimate.getProteinG(), 100);
        result.fatG = number(estimate.getFatG(), 100);
        result.fiberG = number(estimate.getFiberG(), 100);
        result.sugarsG = number(estimate.getSugarsG(), 100);
        result.saltMg = number(estimate.getSaltMg(), 100000);
        return result;
    }

    static Problem problemFor(String errorCode) {
        if (errorCode == null) return Problem.SERVER;
        switch (errorCode) {
            case "UNAUTHENTICATED": return Problem.AUTHORIZATION;
            case "RATE_LIMITED": return Problem.RATE_LIMIT;
            case "NUTRITION_UNAVAILABLE": return Problem.SERVER;
            case "INVALID_RESPONSE": return Problem.INVALID_RESPONSE;
            case "NETWORK_ERROR":
            case "TIMEOUT":
            case "CANCELLED": return Problem.NETWORK;
            default: return Problem.SERVER;
        }
    }

    private static Double number(BigDecimal value, double maximum) {
        if (value == null) return null;
        double number = value.doubleValue();
        if (!Double.isFinite(number) || number < 0 || number > maximum) throw new IllegalArgumentException("Out-of-range nutrient");
        return number;
    }
}
