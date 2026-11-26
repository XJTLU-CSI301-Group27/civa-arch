package edu.xjtlu.civa.feature.bd;

import static org.junit.Assert.*;
import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class BdPersonalDraftMigrationTest {
    private final String personal = BdDraft.scopeHash("account:alice");
    private final String first = BdDraft.scopeHash("account:alice/home-one");
    private final String second = BdDraft.scopeHash("account:alice/home-two");
    private final LocalDate day = LocalDate.of(2026, 11, 26);

    @Test public void combinesHouseholdsAndUnjoinedDaysWithoutMixingDatesOrMeals() {
        BdDraft a = BdDraft.empty(first).withDish(day,"BREAKFAST",BdDraft.Dish.custom("a","早餐",null));
        BdDraft b = BdDraft.empty(second).withDish(day,"LUNCH",BdDraft.Dish.custom("b","午餐",null));
        BdDraft unjoined = BdDraft.empty(BdDraft.scopeHash("account:alice/null"))
                .withDish(day.plusDays(1),"DINNER",BdDraft.Dish.custom("c","明晚",null));
        BdDraft result = BdDraft.empty(personal).importLegacy(a,x->x).importLegacy(b,x->x).importLegacy(unjoined,x->x);
        assertEquals("早餐",result.dishes(day,"BREAKFAST").get(0).name);
        assertEquals("午餐",result.dishes(day,"LUNCH").get(0).name);
        assertTrue(result.dishes(day,"DINNER").isEmpty());
        assertEquals("明晚",result.dishes(day.plusDays(1),"DINNER").get(0).name);
        assertEquals(personal,result.scope);
        assertEquals(first,a.scope);
    }

    @Test public void currentEditsWinAndImportedDeletionDoesNotResurrectAfterRestart() {
        BdDraft old = BdDraft.empty(first).withDish(day,"BREAKFAST",BdDraft.Dish.custom("same","旧名称",null));
        BdDraft current = BdDraft.empty(personal).withDish(day,"BREAKFAST",BdDraft.Dish.custom("same","已修改",null));
        BdDraft imported = current.importLegacy(old,x->x);
        assertEquals(1,imported.dishes(day,"BREAKFAST").size());
        assertEquals("已修改",imported.dishes(day,"BREAKFAST").get(0).name);
        BdDraft removed = imported.withoutDish(day,"BREAKFAST","same");
        BdDraft reopened = BdDraft.decode(new Gson().toJson(removed),personal).importLegacy(old,x->x);
        assertTrue(reopened.dishes(day,"BREAKFAST").isEmpty());
    }

    @Test public void photosAreReownedExactlyOnceAndSourceSnapshotStaysUnchanged() {
        String oldPhoto="local:home-dish-photos/"+first+"/photo.jpg";
        String newPhoto="local:home-dish-photos/"+personal+"/copied.jpg";
        BdDraft old=BdDraft.empty(first).withDish(day,"DINNER",BdDraft.Dish.custom("photo","照片菜",oldPhoto));
        AtomicInteger copies=new AtomicInteger();
        BdDraft migrated=BdDraft.empty(personal).importLegacy(old,x->{assertEquals(oldPhoto,x);copies.incrementAndGet();return newPhoto;});
        migrated=migrated.importLegacy(old,x->{fail("Repeated photo copy");return x;});
        assertEquals(1,copies.get());assertEquals(newPhoto,migrated.dishes(day,"DINNER").get(0).image);
        assertEquals(oldPhoto,old.dishes(day,"DINNER").get(0).image);
    }

    @Test public void failedPhotoCopyLeavesNoCompletedMigrationAndCanBeRetried() {
        BdDraft old=BdDraft.empty(first).withDish(day,"DINNER",BdDraft.Dish.custom("photo","菜","local:home-dish-photos/"+first+"/a.jpg"));
        BdDraft current=BdDraft.empty(personal);
        assertThrows(IllegalStateException.class,()->current.importLegacy(old,x->{throw new IllegalStateException("disk");}));
        assertFalse(current.migratedScopes.contains(first));assertTrue(current.meals.isEmpty());
        assertEquals(1,current.importLegacy(old,x->"local:home-dish-photos/"+personal+"/a.jpg").dishes(day,"DINNER").size());
    }

    @Test public void otherAccountsCannotDecodePersonalPlanAndOldVersionOneStillLoads() {
        BdDraft migrated=BdDraft.empty(personal).importLegacy(BdDraft.empty(first),x->x);
        assertThrows(IllegalArgumentException.class,()->BdDraft.decode(new Gson().toJson(migrated),BdDraft.scopeHash("account:bob")));
        String legacy="{\"version\":1,\"scope\":\""+first+"\",\"meals\":{}}";
        assertNotNull(BdDraft.decode(legacy,first).migratedScopes);
        assertEquals(1,BdDraft.empty(personal).importLegacy(BdDraft.decode(legacy,first),x->x).migratedScopes.size());
    }
}
