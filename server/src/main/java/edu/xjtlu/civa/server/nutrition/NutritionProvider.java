package edu.xjtlu.civa.server.nutrition;

import java.math.BigDecimal;
import java.util.List;

/**
 * Provider abstraction for the nutrition estimate. The estimate is an AI approximation:
 * unknown metrics stay null, never zero. Implementations must not leak credentials or
 * raw dish text into logs.
 */
interface NutritionProvider {
    /** Stable provider identifier stored with the cached row (for example {@code stub}). */
    String name();

    /** Provider and prompt version stored with the cached row (for example {@code stub:v1}). */
    String version();

    Estimate estimate(Input input);

    record Input(String name, List<String> ingredients, List<String> steps) { }

    record Estimate(BigDecimal energyKcal, BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG,
                    BigDecimal fiberG, BigDecimal sugarsG, BigDecimal saltMg, String tips) { }
}
