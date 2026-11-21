package edu.xjtlu.civa.feature.bd;

import static org.junit.Assert.*;
import com.google.gson.Gson;
import java.time.LocalDate;
import org.junit.Test;

public class BdDishDetailsTest {
    private final LocalDate date = LocalDate.of(2026, 11, 20);
    private final String scope = BdDraft.scopeHash("details-owner");
    private BdDraft original() {
        return BdDraft.empty(scope)
                .withDish(date, "BREAKFAST", BdDraft.Dish.custom("one", "番茄", null))
                .withDish(date, "BREAKFAST", BdDraft.Dish.custom("two", "番茄", null))
                .withDish(date, "LUNCH", BdDraft.Dish.custom("three", "番茄", null))
                .withDish(date.plusDays(1), "BREAKFAST", BdDraft.Dish.custom("four", "番茄", null));
    }

    @Test public void removalTargetsOnlyTheSelectedDateMealAndOperationAndIsIdempotent() {
        BdDraft before = original();
        BdDraft after = before.withoutDish(date, "BREAKFAST", "one").withoutDish(date, "BREAKFAST", "one");
        assertEquals(2, before.dishes(date, "BREAKFAST").size());
        assertNull(after.find(date, "BREAKFAST", "one"));
        assertNotNull(after.find(date, "BREAKFAST", "two"));
        assertNotNull(after.find(date, "LUNCH", "three"));
        assertNotNull(after.find(date.plusDays(1), "BREAKFAST", "four"));
        assertFalse(after.withoutDish(date, "BREAKFAST", "two").meals.containsKey(BdDraft.key(date, "BREAKFAST")));
    }

    @Test public void personalRecipePersistsParagraphsAndDoesNotChangeOtherSnapshots() {
        BdDraft before = original();
        BdDraft edited = before.withRecipe(date, "BREAKFAST", "one", "番茄 100g\n\n盐 1g", "洗净\n切片");
        BdDraft restored = BdDraft.decode(new Gson().toJson(edited), scope);
        assertEquals(3, restored.find(date, "BREAKFAST", "one").ingredients.size());
        assertEquals("", restored.find(date, "BREAKFAST", "one").ingredients.get(1));
        assertEquals("切片", restored.find(date, "BREAKFAST", "one").steps.get(1));
        assertTrue(restored.find(date, "BREAKFAST", "two").steps.isEmpty());
        assertTrue(before.find(date, "BREAKFAST", "one").steps.isEmpty());
    }

    @Test public void legacyDraftsLoadAndInvalidRecipeEditsLeaveOriginalDataUntouched() {
        BdDraft before = original(); String json = new Gson().toJson(before);
        assertNull(BdDraft.decode(json, scope).find(date, "BREAKFAST", "one").nutrition);
        assertThrows(IllegalArgumentException.class, () -> before.withRecipe(date, "BREAKFAST", "one", " ", "做法"));
        assertThrows(IllegalArgumentException.class, () -> before.withRecipe(date, "BREAKFAST", "missing", "番茄", "切片"));
        assertThrows(IllegalArgumentException.class, () -> before.withRecipe(date, "BREAKFAST", "one", "番茄", "x".repeat(8001)));
        assertEquals(json, new Gson().toJson(before));
    }

    @Test public void nutritionCacheIsBoundToTheRecipeSnapshotAndInvalidatedAfterEditing() {
        BdDraft before = original(); BdDraft.Dish dish = before.find(date, "BREAKFAST", "one");
        String payload = "{\"basis\":\"per_100g\",\"energyKcal\":null,\"carbsG\":null,\"proteinG\":null,\"fatG\":null,\"fiberG\":null,\"sugarsG\":null,\"saltMg\":null,\"tips\":\"请补充用量\"}";
        BdNutrition estimate = BdNutrition.parse(payload, BdNutrition.fingerprint(dish));
        BdDraft cached = before.withNutrition(date, "BREAKFAST", "one", estimate);
        BdDraft restored = BdDraft.decode(new Gson().toJson(cached), scope);
        assertTrue(restored.find(date, "BREAKFAST", "one").nutrition.validFor(dish));
        assertNull(restored.find(date, "BREAKFAST", "one").nutrition.energyKcal);
        BdDraft edited = cached.withRecipe(date, "BREAKFAST", "one", "番茄 200g", "切片");
        assertNull(edited.find(date, "BREAKFAST", "one").nutrition);
        assertThrows(IllegalArgumentException.class, () -> edited.withNutrition(date, "BREAKFAST", "one", estimate));
    }
}
