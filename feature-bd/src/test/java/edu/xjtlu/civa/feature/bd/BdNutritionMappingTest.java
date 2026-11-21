package edu.xjtlu.civa.feature.bd;

import edu.xjtlu.civa.api.model.NutritionBasis;
import edu.xjtlu.civa.api.model.NutritionEstimate;
import java.math.BigDecimal;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/** The DTO mapping re-validates the server response: unknown metrics stay null, never zero. */
public class BdNutritionMappingTest {
    private static BdDraft.Dish dish() {
        BdDraft.Dish dish = BdDraft.Dish.custom("one", "番茄炒蛋", null);
        dish.ingredients = java.util.List.of("番茄 2 个", "鸡蛋 3 个");
        dish.steps = java.util.List.of("切块", "合炒");
        return dish;
    }

    private static NutritionEstimate response() {
        return new NutritionEstimate()
                .basis(NutritionBasis.PER_100_G)
                .energyKcal(new BigDecimal("92"))
                .proteinG(new BigDecimal("7.2"))
                .fatG(BigDecimal.ZERO)
                .tips(" 估算受用量与烹调方式影响。 ")
                .inputHash("ignored-by-the-client")
                .providerVersion("stub:v1")
                .cached(false);
    }

    @Test public void mapsKnownMetricsAndKeepsUnknownsNull() {
        BdDraft.Dish dish = dish();
        BdNutrition mapped = BdNutritionClient.map(dish, response());
        assertEquals(Double.valueOf(92), mapped.energyKcal);
        assertEquals(Double.valueOf(7.2), mapped.proteinG);
        assertEquals(Double.valueOf(0), mapped.fatG);
        assertNull(mapped.fiberG);
        assertNull(mapped.sugarsG);
        assertNull(mapped.saltMg);
        assertEquals("per_100g", mapped.basis);
        assertEquals("估算受用量与烹调方式影响。", mapped.tips);
        assertEquals(BdNutrition.fingerprint(dish), mapped.inputHash);
    }

    @Test public void mappedEstimateIsValidForTheSameDish() {
        BdDraft.Dish dish = dish();
        BdNutrition mapped = BdNutritionClient.map(dish, response());
        org.junit.Assert.assertTrue(mapped.validFor(dish));
    }

    @Test public void rejectsWrongBasisMissingTipsAndOutOfRangeValues() {
        BdDraft.Dish dish = dish();
        assertThrows(IllegalArgumentException.class,
                () -> BdNutritionClient.map(dish, response().basis(null)));
        assertThrows(IllegalArgumentException.class,
                () -> BdNutritionClient.map(dish, response().tips(" ")));
        assertThrows(IllegalArgumentException.class,
                () -> BdNutritionClient.map(dish, response().fatG(new BigDecimal("-1"))));
        assertThrows(IllegalArgumentException.class,
                () -> BdNutritionClient.map(dish, response().energyKcal(new BigDecimal("1001"))));
    }

    @Test public void mapsErrorCodesToTheUserFacingProblems() {
        assertEquals(BdNutritionClient.Problem.AUTHORIZATION, BdNutritionClient.problemFor("UNAUTHENTICATED"));
        assertEquals(BdNutritionClient.Problem.RATE_LIMIT, BdNutritionClient.problemFor("RATE_LIMITED"));
        assertEquals(BdNutritionClient.Problem.SERVER, BdNutritionClient.problemFor("NUTRITION_UNAVAILABLE"));
        assertEquals(BdNutritionClient.Problem.INVALID_RESPONSE, BdNutritionClient.problemFor("INVALID_RESPONSE"));
        assertEquals(BdNutritionClient.Problem.NETWORK, BdNutritionClient.problemFor("NETWORK_ERROR"));
        assertEquals(BdNutritionClient.Problem.NETWORK, BdNutritionClient.problemFor("TIMEOUT"));
        assertEquals(BdNutritionClient.Problem.SERVER, BdNutritionClient.problemFor("SOMETHING_ELSE"));
    }
}
