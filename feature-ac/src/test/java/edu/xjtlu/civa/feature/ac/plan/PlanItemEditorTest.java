package edu.xjtlu.civa.feature.ac.plan;

import edu.xjtlu.civa.api.model.PlanItem;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlanItemEditorTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test public void appendingKeepsEveryExistingDish() {
        List<PlanItem> result = PlanItemEditor.append(List.of(item(A, "2"), item(B, "1")), C);

        assertEquals(3, result.size());
        assertEquals("2", servings(result, A));
        assertEquals("1", servings(result, B));
        assertEquals("1", servings(result, C));
    }

    @Test public void appendingAnExistingDishIncreasesServings() {
        List<PlanItem> result = PlanItemEditor.append(List.of(item(A, "2"), item(B, "1")), A);

        assertEquals(2, result.size());
        assertEquals("3", servings(result, A));
        assertEquals("1", servings(result, B));
    }

    @Test public void adjustMovesBothWaysAndRemovesAtZero() {
        assertEquals("4", servings(PlanItemEditor.adjust(List.of(item(A, "2")), A, 2), A));
        assertEquals("1", servings(PlanItemEditor.adjust(List.of(item(A, "2")), A, -1), A));
        assertTrue(PlanItemEditor.adjust(List.of(item(A, "1")), A, -1).isEmpty());
    }

    @Test public void removeDropsOnlyTheSelectedDish() {
        List<PlanItem> result = PlanItemEditor.remove(List.of(item(A, "2"), item(B, "1")), A);

        assertEquals(1, result.size());
        assertEquals("1", servings(result, B));
    }

    @Test public void replaceSwapsTheRecipeAndKeepsServings() {
        List<PlanItem> result = PlanItemEditor.replace(List.of(item(A, "2")), A, B);

        assertEquals(1, result.size());
        assertEquals("2", servings(result, B));
    }

    @Test public void replaceNeverCreatesADuplicateRecipe() {
        List<PlanItem> result = PlanItemEditor.replace(List.of(item(A, "2"), item(B, "1")), A, B);

        assertEquals(2, result.size());
        assertEquals("2", servings(result, A));
        assertEquals("1", servings(result, B));
    }

    @Test public void editingDoesNotMutateTheLoadedSlot() {
        PlanItem original = item(A, "2");
        List<PlanItem> loaded = List.of(original);

        PlanItemEditor.append(loaded, A);
        PlanItemEditor.adjust(loaded, A, 1);
        PlanItemEditor.remove(loaded, A);

        assertEquals("2", original.getServings());
        assertEquals(A, original.getRecipeId());
    }

    private static PlanItem item(UUID recipe, String servings) {
        return new PlanItem().recipeId(recipe).servings(servings);
    }

    private static String servings(List<PlanItem> items, UUID recipe) {
        return items.stream().filter(item -> recipe.equals(item.getRecipeId()))
                .findFirst().orElseThrow().getServings();
    }
}
