package edu.xjtlu.civa.server.recipe;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeCatalogQueryTest {
    @Test void defaultsToTheFirstPageOfTwenty() {
        RecipeCatalogQuery query = RecipeCatalogQuery.of(null, null, null, false, null, null);
        assertEquals("WHERE 1=1", query.where());
        assertEquals(0, query.page());
        assertEquals(20, query.size());
        assertEquals(0, query.offset());
        assertTrue(query.params().isEmpty());
    }

    @Test void appendsSearchCategoryTagAndHighProteinFilters() {
        RecipeCatalogQuery query = RecipeCatalogQuery.of(" 咖喱 ", "川菜", "辣", true, 2, 10);
        assertTrue(query.where().contains("search_text LIKE ?"));
        assertTrue(query.where().contains("category = ?"));
        assertTrue(query.where().contains("unnest(flavors)"));
        assertTrue(query.where().contains("protein_per100g >= 10"));
        assertEquals("%咖喱%", query.params().get(0));
        assertEquals("川菜", query.params().get(1));
        assertEquals(5, query.params().size()); // q + category + tag (three LIKE params)
        assertEquals(2, query.page());
        assertEquals(20, query.offset());
    }

    @Test void rejectsInvalidPagingAndOverlongFilters() {
        assertThrows(IllegalArgumentException.class, () -> RecipeCatalogQuery.of(null, null, null, false, -1, 20));
        assertThrows(IllegalArgumentException.class, () -> RecipeCatalogQuery.of(null, null, null, false, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> RecipeCatalogQuery.of(null, null, null, false, 0, 101));
        assertThrows(IllegalArgumentException.class, () -> RecipeCatalogQuery.of("x".repeat(101), null, null, false, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> RecipeCatalogQuery.of(null, "x".repeat(41), null, false, 0, 20));
        assertThrows(IllegalArgumentException.class, () -> RecipeCatalogQuery.of(null, null, "x".repeat(41), false, 0, 20));
    }
}
