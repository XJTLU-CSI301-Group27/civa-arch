package edu.xjtlu.civa.feature.ac.diary;

import edu.xjtlu.civa.api.model.CreateMealEntryRequest;
import edu.xjtlu.civa.api.model.EntryMealType;
import edu.xjtlu.civa.api.model.KcalBasis;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.core.data.PlannedDish;
import java.time.LocalDate;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class PlannedMealsTest {
    private static PlannedDish dish(String meal, String name) {
        return new PlannedDish(meal, name, null, null, List.of(), List.of());
    }

    /** Recording a plan item is a personal meal entry: no cooking event, no stock involvement. */
    @Test public void planMealRequestNeverCarriesACookingEvent() {
        CreateMealEntryRequest request = PlannedMeals.mealRequest(dish("LUNCH", "番茄蛋"),
                LocalDate.of(2026, 11, 27));

        assertNull(request.getCookingEventId());
        assertEquals("2026-11-27", request.getDate());
        assertEquals(EntryMealType.LUNCH, request.getMealType());
        assertEquals("番茄蛋", request.getName());
        assertEquals("1", request.getAmount());
        assertEquals(KcalBasis.UNKNOWN, request.getBasis());
    }

    @Test public void planMealRequestFallsBackToLunchForAnUnknownMeal() {
        CreateMealEntryRequest request = PlannedMeals.mealRequest(dish("BRUNCH", "面包"),
                LocalDate.of(2026, 11, 27));

        assertEquals(EntryMealType.LUNCH, request.getMealType());
    }

    @Test public void householdRecipeMatchesOnlyAnExactName() {
        Recipe tomatoEgg = new Recipe().name("番茄蛋");
        Recipe other = new Recipe().name("番茄蛋汤");

        assertSame(tomatoEgg, PlannedMeals.householdRecipe(List.of(tomatoEgg, other), " 番茄蛋 "));
        assertSame(tomatoEgg, PlannedMeals.householdRecipe(List.of(tomatoEgg), "番茄蛋"));
    }

    @Test public void householdRecipeDoesNotGuessWhenTheNameIsDifferent() {
        Recipe other = new Recipe().name("番茄蛋汤");

        assertNull(PlannedMeals.householdRecipe(List.of(other), "番茄蛋"));
        assertNull(PlannedMeals.householdRecipe(List.of(other), " "));
        assertNull(PlannedMeals.householdRecipe(null, "番茄蛋"));
        assertNull(PlannedMeals.householdRecipe(List.of(), "番茄蛋"));
    }

    @Test public void mealTypeIsParsedOrFallsBackWithoutThrowing() {
        assertEquals(EntryMealType.BREAKFAST, PlannedMeals.mealType("BREAKFAST"));
        assertEquals(EntryMealType.DINNER, PlannedMeals.mealType("DINNER"));
        assertEquals(EntryMealType.LUNCH, PlannedMeals.mealType("unknown"));
        assertEquals(EntryMealType.LUNCH, PlannedMeals.mealType(null));
    }
}
