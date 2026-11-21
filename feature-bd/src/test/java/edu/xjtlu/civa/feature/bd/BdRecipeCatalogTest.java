package edu.xjtlu.civa.feature.bd;

import static org.junit.Assert.*;
import static edu.xjtlu.civa.feature.bd.BdRecipeCatalog.Taste.*;
import java.io.File;
import java.io.FileReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

public class BdRecipeCatalogTest {
    private BdRecipeCatalog catalog() throws Exception {
        try (FileReader reader = new FileReader(System.getProperty("civa.recipeCatalog"), StandardCharsets.UTF_8)) {
            return new BdRecipeCatalog(reader);
        }
    }

    @Test public void allEighteenPreferenceCombinationsKeepSelectedTasteAndOfferRerolls() throws Exception {
        BdRecipeCatalog catalog = catalog();
        for (BdRecipeCatalog.Taste spicy : values()) for (BdRecipeCatalog.Taste meat : values()) for (boolean quick : new boolean[]{false, true}) {
            List<BdRecipeCatalog.Recipe> pool = catalog.recommendationPool(spicy, meat, quick);
            assertTrue("Need more than one matching dish", pool.size() >= 2);
            for (BdRecipeCatalog.Recipe recipe : pool) {
                assertTrue(spicy.accepts(recipe.spicy)); assertTrue(meat.accepts(recipe.meat));
                if (quick) { assertNotNull(recipe.minutes); assertTrue(recipe.minutes <= 30); }
            }
        }
    }

    @Test public void importedRecipeMethodsAndLocalImageFilesArePresent() throws Exception {
        BdRecipeCatalog catalog = catalog();
        assertTrue(catalog.recipes.size() >= 300);
        File assets = new File(System.getProperty("civa.recipeCatalog")).getParentFile().getParentFile();
        int photographs = 0;
        for (BdRecipeCatalog.Recipe recipe : catalog.recipes) {
            assertFalse(recipe.name.trim().isEmpty()); assertFalse(recipe.ingredients.isEmpty()); assertFalse(recipe.steps.isEmpty());
            assertTrue(recipe.sourceUrl.startsWith("https://github.com/Anduin2017/HowToCook/blob/"));
            if (recipe.image != null) { assertTrue(new File(assets, recipe.image.substring(6)).isFile()); photographs++; }
        }
        assertTrue(photographs >= 140);
    }

    @Test public void rerollUsesEveryCandidateBeforeCyclingAndDoesNotImmediatelyRepeat() throws Exception {
        List<BdRecipeCatalog.Recipe> pool = catalog().recommendationPool(NO, NO, true).subList(0, 3);
        Set<String> seen = new HashSet<>(), firstCycle = new HashSet<>();
        Random random = new Random(301); String previous = null;
        for (int i = 0; i < 3; i++) {
            BdRecipeCatalog.Recipe chosen = BdRecipeCatalog.draw(pool, seen, previous, random);
            assertNotEquals(previous, chosen.id); assertTrue(firstCycle.add(chosen.id)); previous = chosen.id;
        }
        assertNotEquals(previous, BdRecipeCatalog.draw(pool, seen, previous, random).id);
    }

    @Test public void unknownDurationIsNotSilentlyClassifiedAsFastAndEmptyFiltersStayEmpty() {
        BdRecipeCatalog catalog = new BdRecipeCatalog(new StringReader("[{\"id\":\"one\",\"name\":\"清汤\",\"spicy\":false,\"meat\":false,\"ingredients\":[\"水\"],\"steps\":[\"煮开\"]}]"));
        assertEquals(1, catalog.matching(NO, NO, false).size());
        assertTrue(catalog.matching(NO, NO, true).isEmpty());
        assertNull(BdRecipeCatalog.draw(catalog.matching(YES, YES, false), new HashSet<>(), null, new Random(1)));
    }

    @Test public void unrestrictedDrawIncludesEveryRecipeAndBothMeatAndVegetables() throws Exception {
        BdRecipeCatalog catalog = catalog();
        List<BdRecipeCatalog.Recipe> pool = catalog.recommendationPool(ANY, ANY, false);
        assertEquals(catalog.recipes.size(), pool.size());
        assertTrue(pool.stream().anyMatch(r -> r.meat));
        assertTrue(pool.stream().anyMatch(r -> !r.meat));
        assertTrue(pool.stream().anyMatch(r -> r.image == null));
        assertTrue(pool.stream().anyMatch(r -> r.image != null));
        Set<String> seen = new HashSet<>(), drawn = new HashSet<>();
        Random random = new Random(301); String previous = null;
        for (int i = 0; i < pool.size(); i++) {
            BdRecipeCatalog.Recipe recipe = BdRecipeCatalog.draw(pool, seen, previous, random);
            assertTrue(drawn.add(recipe.id)); previous = recipe.id;
        }
        assertEquals(catalog.recipes.size(), drawn.size());
    }

    @Test public void eachPreferenceCanBeUnrestrictedIndependently() throws Exception {
        BdRecipeCatalog catalog = catalog();
        Set<String> all = new HashSet<>();
        for (BdRecipeCatalog.Recipe recipe : catalog.matching(ANY, ANY, false)) all.add(recipe.id);
        Set<String> partition = new HashSet<>();
        for (BdRecipeCatalog.Taste spicy : new BdRecipeCatalog.Taste[]{YES, NO}) {
            List<BdRecipeCatalog.Recipe> pool = catalog.matching(spicy, ANY, false);
            assertTrue(pool.stream().anyMatch(r -> r.meat));
            assertTrue(pool.stream().anyMatch(r -> !r.meat));
            for (BdRecipeCatalog.Recipe recipe : pool) { assertTrue(spicy.accepts(recipe.spicy)); assertTrue(partition.add(recipe.id)); }
        }
        assertEquals(all, partition);
        for (BdRecipeCatalog.Taste meat : new BdRecipeCatalog.Taste[]{YES, NO}) {
            List<BdRecipeCatalog.Recipe> pool = catalog.matching(ANY, meat, false);
            assertTrue(pool.stream().anyMatch(r -> r.spicy));
            assertTrue(pool.stream().anyMatch(r -> !r.spicy));
            for (BdRecipeCatalog.Recipe recipe : pool) assertTrue(meat.accepts(recipe.meat));
        }
    }

    @Test public void savedPreferencesRestoreNewValuesAndLegacyBooleans() {
        for (BdRecipeCatalog.Taste taste : values()) assertEquals(taste, BdRecipeCatalog.Taste.restore(taste.name(), false));
        assertEquals(YES, BdRecipeCatalog.Taste.restore(null, true));
        assertEquals(NO, BdRecipeCatalog.Taste.restore(null, false));
        assertEquals(ANY, BdRecipeCatalog.Taste.restore(null, null));
        assertEquals(ANY, BdRecipeCatalog.Taste.restore("invalid", true));
    }

    @Test public void unrestrictedTasteStillHonorsQuickOnlyAndNeverRelaxesEmptyResults() {
        BdRecipeCatalog catalog = new BdRecipeCatalog(new StringReader("[{\"id\":\"one\",\"name\":\"清汤\",\"spicy\":false,\"meat\":false,\"ingredients\":[\"水\"],\"steps\":[\"煮开\"]}]"));
        assertEquals(1, catalog.recommendationPool(ANY, ANY, false).size());
        assertTrue(catalog.recommendationPool(ANY, ANY, true).isEmpty());
        assertTrue(catalog.recommendationPool(YES, ANY, false).isEmpty());
        assertTrue(catalog.recommendationPool(ANY, YES, false).isEmpty());
    }
}
