package edu.xjtlu.civa.core.data;

import androidx.annotation.Nullable;
import java.util.List;

/** One dish planned for a date in the personal "today" plan (local draft). */
public final class PlannedDish {
    private final String meal;
    private final String name;
    @Nullable private final String recipeId;
    @Nullable private final String image;
    private final List<String> ingredients;
    private final List<String> steps;

    public PlannedDish(String meal, String name, @Nullable String recipeId, @Nullable String image,
                       List<String> ingredients, List<String> steps) {
        this.meal = meal;
        this.name = name;
        this.recipeId = recipeId;
        this.image = image;
        this.ingredients = ingredients;
        this.steps = steps;
    }

    /** BREAKFAST, LUNCH or DINNER. */
    public String meal() { return meal; }

    public String name() { return name; }

    @Nullable public String recipeId() { return recipeId; }

    @Nullable public String image() { return image; }

    public List<String> ingredients() { return ingredients; }

    public List<String> steps() { return steps; }
}
