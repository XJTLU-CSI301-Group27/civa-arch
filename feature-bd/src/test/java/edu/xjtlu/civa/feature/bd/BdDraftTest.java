package edu.xjtlu.civa.feature.bd;

import static org.junit.Assert.*;
import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;

public class BdDraftTest {
    private final String scope = BdDraft.scopeHash("preview");
    private final LocalDate day = LocalDate.of(2026, 11, 20);

    @Test public void multipleDishesSurviveSerializationWithoutCrossingDatesOrMealSlots() {
        BdDraft data = BdDraft.empty(scope)
                .withDish(day, "BREAKFAST", BdDraft.Dish.custom("one", "南瓜粥", null))
                .withDish(day, "BREAKFAST", BdDraft.Dish.custom("two", "鸡蛋饼", null))
                .withDish(day, "LUNCH", BdDraft.Dish.custom("three", "午餐", null))
                .withDish(day.plusDays(1), "BREAKFAST", BdDraft.Dish.custom("four", "明早", null));
        BdDraft restored = BdDraft.decode(new Gson().toJson(data), scope);
        assertEquals(2, restored.dishes(day, "BREAKFAST").size());
        assertEquals("南瓜粥", restored.dishes(day, "BREAKFAST").get(0).name);
        assertEquals("午餐", restored.dishes(day, "LUNCH").get(0).name);
        assertEquals("明早", restored.dishes(day.plusDays(1), "BREAKFAST").get(0).name);
        assertTrue(restored.dishes(day, "DINNER").isEmpty());
    }

    @Test public void repeatedConfirmationIsIdempotentButSeparateAddsRemainSeparate() {
        BdDraft.Dish dish = BdDraft.Dish.custom("request-one", "米饭", null);
        BdDraft data = BdDraft.empty(scope).withDish(day, "BREAKFAST", dish).withDish(day, "BREAKFAST", dish);
        assertEquals(1, data.dishes(day, "BREAKFAST").size());
        data = data.withDish(day, "BREAKFAST", BdDraft.Dish.custom("request-two", "米饭", null));
        assertEquals(2, data.dishes(day, "BREAKFAST").size());
    }

    @Test public void savedRecipeRetainsItsSnapshotAndRejectsAnotherOwner() {
        BdRecipeCatalog.Recipe recipe = new BdRecipeCatalog.Recipe();
        recipe.id = "recipe"; recipe.name = "原菜名"; recipe.ingredients = new ArrayList<>(Arrays.asList("豆腐")); recipe.steps = new ArrayList<>(Arrays.asList("原做法"));
        BdDraft data = BdDraft.empty(scope).withDish(day, "DINNER", BdDraft.Dish.recipe("request", recipe));
        recipe.name = "新菜名"; recipe.steps.set(0, "新做法");
        assertEquals("原菜名", data.dishes(day, "DINNER").get(0).name);
        assertEquals("原做法", data.dishes(day, "DINNER").get(0).steps.get(0));
        assertNotEquals(scope, BdDraft.scopeHash("another-account"));
        assertThrows(IllegalArgumentException.class, () -> BdDraft.decode(new Gson().toJson(data), BdDraft.scopeHash("another-account")));
    }

    @Test public void malformedDataUnsupportedVersionsAndMissingNamesAreRejected() {
        assertThrows(RuntimeException.class, () -> BdDraft.decode("{broken", scope));
        assertThrows(IllegalArgumentException.class, () -> BdDraft.decode("{\"version\":2,\"scope\":\"" + scope + "\",\"meals\":{}}", scope));
        assertThrows(IllegalArgumentException.class, () -> BdDraft.Dish.custom("id", "  ", null));
        assertThrows(IllegalArgumentException.class, () -> BdDraft.key(day, "UNKNOWN"));
    }
}
