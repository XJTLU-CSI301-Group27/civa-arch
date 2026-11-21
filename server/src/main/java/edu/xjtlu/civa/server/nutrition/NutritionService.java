package edu.xjtlu.civa.server.nutrition;

import edu.xjtlu.civa.server.api.model.NutritionBasis;
import edu.xjtlu.civa.server.api.model.NutritionEstimate;
import edu.xjtlu.civa.server.api.model.NutritionEstimateRequest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Nutrition estimates are shared, content-addressed results: the same dish content yields one
 * cached row, so repeated requests from any account are free. Inputs are validated here as well
 * as by bean validation; the cache stores only the digest and the metrics, never the dish text.
 */
final class NutritionService {
    private static final int NAME_MAX = 60;
    private static final int INGREDIENTS_MAX = 30;
    private static final int INGREDIENT_MAX = 120;
    private static final int STEPS_MAX = 60;
    private static final int STEP_MAX = 200;

    private final JdbcTemplate jdbc;
    private final NutritionProvider provider;
    private final NutritionRateLimiter rateLimiter;
    private final Clock clock;
    private final int cacheDays;

    NutritionService(JdbcTemplate jdbc, NutritionProvider provider, NutritionRateLimiter rateLimiter, Clock clock, int cacheDays) {
        if (cacheDays < 1) throw new IllegalArgumentException("Invalid nutrition cache window");
        this.jdbc = jdbc;
        this.provider = provider;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.cacheDays = cacheDays;
    }

    NutritionEstimate estimate(UUID actor, NutritionEstimateRequest request) {
        NutritionProvider.Input input = validate(request);
        String hash = NutritionDigest.hash(input);
        NutritionEstimate cached = read(hash);
        if (cached != null) return cached;

        // Only provider calls are limited; a cache hit must stay fast and free.
        rateLimiter.acquire(actor);
        NutritionProvider.Estimate estimate = provider.estimate(input);
        write(hash, estimate, provider.name(), provider.version());
        return model(hash, estimate, provider.version(), false);
    }

    private NutritionProvider.Input validate(NutritionEstimateRequest request) {
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > NAME_MAX) throw new IllegalArgumentException("Invalid dish name");
        List<String> ingredients = NutritionDigest.normalize(request.getIngredients());
        if (ingredients.isEmpty() || ingredients.size() > INGREDIENTS_MAX) throw new IllegalArgumentException("Invalid ingredient count");
        for (String ingredient : ingredients) {
            if (ingredient.codePointCount(0, ingredient.length()) > INGREDIENT_MAX) throw new IllegalArgumentException("Ingredient too long");
        }
        List<String> steps = NutritionDigest.normalize(request.getSteps());
        if (steps.size() > STEPS_MAX) throw new IllegalArgumentException("Too many steps");
        for (String step : steps) {
            if (step.codePointCount(0, step.length()) > STEP_MAX) throw new IllegalArgumentException("Step too long");
        }
        return new NutritionProvider.Input(name, ingredients, steps);
    }

    private NutritionEstimate read(String hash) {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(Duration.ofDays(cacheDays)));
        List<NutritionEstimate> rows = jdbc.query(
                "SELECT energy_kcal,carbs_g,protein_g,fat_g,fiber_g,sugars_g,salt_mg,tips,provider_version"
                        + " FROM nutrition_estimates WHERE input_hash=? AND created_at>?",
                (rs, row) -> row(rs, hash), hash, cutoff);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static NutritionEstimate row(ResultSet rs, String hash) throws SQLException {
        NutritionEstimate estimate = new NutritionEstimate(NutritionBasis.PER_100_G, rs.getString(8), hash, rs.getString(9), true);
        estimate.setEnergyKcal(rs.getBigDecimal(1));
        estimate.setCarbsG(rs.getBigDecimal(2));
        estimate.setProteinG(rs.getBigDecimal(3));
        estimate.setFatG(rs.getBigDecimal(4));
        estimate.setFiberG(rs.getBigDecimal(5));
        estimate.setSugarsG(rs.getBigDecimal(6));
        estimate.setSaltMg(rs.getBigDecimal(7));
        return estimate;
    }

    private void write(String hash, NutritionProvider.Estimate estimate, String providerName, String version) {
        jdbc.update("INSERT INTO nutrition_estimates(input_hash,basis,energy_kcal,carbs_g,protein_g,fat_g,fiber_g,sugars_g,salt_mg,tips,provider,provider_version)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (input_hash) DO NOTHING",
                hash, "PER_100G", estimate.energyKcal(), estimate.carbsG(), estimate.proteinG(), estimate.fatG(),
                estimate.fiberG(), estimate.sugarsG(), estimate.saltMg(), estimate.tips(), providerName, version);
    }

    private static NutritionEstimate model(String hash, NutritionProvider.Estimate estimate, String version, boolean cached) {
        NutritionEstimate model = new NutritionEstimate(NutritionBasis.PER_100_G, estimate.tips(), hash, version, cached);
        model.setEnergyKcal(estimate.energyKcal());
        model.setCarbsG(estimate.carbsG());
        model.setProteinG(estimate.proteinG());
        model.setFatG(estimate.fatG());
        model.setFiberG(estimate.fiberG());
        model.setSugarsG(estimate.sugarsG());
        model.setSaltMg(estimate.saltMg());
        return model;
    }
}
