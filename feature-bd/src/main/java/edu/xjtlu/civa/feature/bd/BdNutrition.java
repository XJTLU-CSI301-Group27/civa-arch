package edu.xjtlu.civa.feature.bd;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;

/** App-private AI estimate, explicitly per 100 g. Null means unknown, never zero. */
final class BdNutrition {
    int schemaVersion = 1;
    String inputHash, basis, tips;
    long generatedAtMillis;
    Double energyKcal, carbsG, proteinG, fatG, fiberG, sugarsG, saltMg;

    static String fingerprint(BdDraft.Dish dish) {
        return BdDraft.scopeHash(new Gson().toJson(Arrays.asList(dish.name, dish.ingredients, dish.steps)));
    }

    static BdNutrition parse(String json, String inputHash) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        if (!object.has("basis") || !"per_100g".equals(object.get("basis").getAsString())) throw new IllegalArgumentException("Wrong serving basis");
        BdNutrition result = new BdNutrition();
        result.inputHash = inputHash; result.basis = "per_100g";
        result.generatedAtMillis = System.currentTimeMillis();
        result.energyKcal = number(object, "energyKcal", 1000);
        result.carbsG = number(object, "carbsG", 100);
        result.proteinG = number(object, "proteinG", 100);
        result.fatG = number(object, "fatG", 100);
        result.fiberG = number(object, "fiberG", 100);
        result.sugarsG = number(object, "sugarsG", 100);
        result.saltMg = number(object, "saltMg", 100000);
        JsonElement tips = object.get("tips");
        if (tips == null || !tips.isJsonPrimitive() || !tips.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Missing tips");
        result.tips = tips.getAsString().trim();
        if (result.tips.isEmpty() || result.tips.length() > 600) throw new IllegalArgumentException("Invalid tips");
        return result;
    }

    boolean validFor(BdDraft.Dish dish) {
        if (schemaVersion != 1 || !fingerprint(dish).equals(inputHash) || !"per_100g".equals(basis) || generatedAtMillis <= 0) return false;
        try { parse(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(this), inputHash); return true; }
        catch (RuntimeException error) { return false; }
    }

    private static Double number(JsonObject object, String key, double maximum) {
        if (!object.has(key)) throw new IllegalArgumentException("Missing nutrient");
        JsonElement element = object.get(key);
        if (element.isJsonNull()) return null;
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Not numeric");
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value < 0 || value > maximum) throw new IllegalArgumentException("Out-of-range nutrient");
        return value;
    }
}
