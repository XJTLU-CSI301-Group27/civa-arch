package edu.xjtlu.civa.feature.bd;

import static org.junit.Assert.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

public class BdNutritionTest {
    private String payload() { return "{\"basis\":\"per_100g\",\"energyKcal\":18,\"carbsG\":3.9,\"proteinG\":0.9,\"fatG\":0.2,\"fiberG\":1.2,\"sugarsG\":null,\"saltMg\":12.5,\"tips\":\"日常估算，烹调不同会有差异。\"}"; }

    @Test public void unknownStaysNullWhileKnownZeroAndUnitsArePreserved() {
        JsonObject object = JsonParser.parseString(payload()).getAsJsonObject(); object.addProperty("fatG", 0);
        BdNutrition result = BdNutrition.parse(object.toString(), "input");
        assertNull(result.sugarsG); assertEquals(0, result.fatG, 0); assertEquals(12.5, result.saltMg, 0);
        assertEquals(18, result.energyKcal, 0); assertEquals("per_100g", result.basis);
    }

    @Test public void missingMetricsWrongServingAndBlankTipsAreRejected() {
        JsonObject object = JsonParser.parseString(payload()).getAsJsonObject(); object.remove("fiberG");
        assertThrows(IllegalArgumentException.class, () -> BdNutrition.parse(object.toString(), "input"));
        assertThrows(IllegalArgumentException.class, () -> BdNutrition.parse(payload().replace("per_100g", "per_plate"), "input"));
        assertThrows(RuntimeException.class, () -> BdNutrition.parse(" ", "input"));
        object.add("fiberG", com.google.gson.JsonNull.INSTANCE); object.addProperty("tips", " ");
        assertThrows(IllegalArgumentException.class, () -> BdNutrition.parse(object.toString(), "input"));
    }

    @Test public void nonnumericNegativeAndPhysicallyOutOfRangeValuesAreRejected() {
        for (String bad : new String[]{"-1", "1001", "1e999", "\"unknown\""}) {
            String json = payload().replace("\"energyKcal\":18", "\"energyKcal\":" + bad);
            assertThrows(RuntimeException.class, () -> BdNutrition.parse(json, "input"));
        }
        assertThrows(IllegalArgumentException.class, () -> BdNutrition.parse(payload().replace("\"saltMg\":12.5", "\"saltMg\":100001"), "input"));
    }

    @Test public void cacheValidationRequiresTheSameDishAndSurvivesUnknownValues() {
        BdDraft.Dish dish = BdDraft.Dish.custom("one", "番茄", null);
        BdNutrition result = BdNutrition.parse(payload(), BdNutrition.fingerprint(dish));
        assertTrue(result.validFor(dish)); dish.steps.add("加糖"); assertFalse(result.validFor(dish));
    }
}
