package edu.xjtlu.civa.feature.ac.diary;

import androidx.annotation.Nullable;
import edu.xjtlu.civa.api.model.CreateMealEntryRequest;
import edu.xjtlu.civa.api.model.EntryMealType;
import edu.xjtlu.civa.api.model.KcalBasis;
import edu.xjtlu.civa.api.model.Recipe;
import edu.xjtlu.civa.core.data.PlannedDish;
import java.time.LocalDate;
import java.util.List;

/**
 * Pure helpers for acting on an item of the personal plan. Recording a meal is independent from
 * cooking: the request it builds never carries a cooking event and never touches inventory.
 */
final class PlannedMeals {
    private PlannedMeals() { }

    /** "I ate this planned dish": a personal meal entry, no cooking event, no stock deduction. */
    static CreateMealEntryRequest mealRequest(PlannedDish dish, LocalDate date) {
        return new CreateMealEntryRequest()
                .date(date.toString())
                .mealType(mealType(dish.meal()))
                .name(dish.name())
                .amount("1")
                .basis(KcalBasis.UNKNOWN)
                .note("")
                .cookingEventId(null);
    }

    /**
     * Resolves the household recipe a plan item refers to. Only an exact (case-insensitive, trimmed)
     * name match is accepted, so a vague or missing name can never silently start a stock deduction.
     */
    @Nullable static Recipe householdRecipe(@Nullable List<Recipe> items, @Nullable String name) {
        if (items == null || name == null) return null;
        String wanted = name.trim();
        if (wanted.isEmpty()) return null;
        for (Recipe recipe : items) {
            if (recipe.getName() != null && recipe.getName().trim().equalsIgnoreCase(wanted)) return recipe;
        }
        return null;
    }

    static EntryMealType mealType(@Nullable String meal) {
        try {
            return EntryMealType.fromValue(meal);
        } catch (RuntimeException invalid) {
            return EntryMealType.LUNCH;
        }
    }
}
