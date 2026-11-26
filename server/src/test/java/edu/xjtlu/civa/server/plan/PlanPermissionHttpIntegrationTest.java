package edu.xjtlu.civa.server.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class PlanPermissionHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;

    @Test void memberCanAddSlotsAndItemsAndIncreaseServings() throws Exception {
        Fixture f = fixture(2);
        try {
            String date = day(1);
            assertEquals(200, status(replace(f.memberToken, f.home, date, "BREAKFAST", List.of(), 0)));
            assertEquals(0, items(f.home, date, "BREAKFAST").size());

            assertEquals(200, status(replace(f.memberToken, f.home, date, "LUNCH",
                    List.of(item(f.a, "2")), 0)));
            assertEquals(200, status(replace(f.memberToken, f.home, date, "LUNCH",
                    List.of(item(f.a, "2"), item(f.b, "1")), 1)));
            assertEquals(200, status(replace(f.memberToken, f.home, date, "LUNCH",
                    List.of(item(f.a, "4"), item(f.b, "1")), 2)));
            assertEquals("4", items(f.home, date, "LUNCH").get(f.a));
            assertEquals("1", items(f.home, date, "LUNCH").get(f.b));
        } finally {
            f.cleanup();
        }
    }

    @Test void memberCannotDecreaseDeleteReplaceOrClear() throws Exception {
        Fixture f = fixture(3);
        try {
            String date = day(2);
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2"), item(f.b, "1")), 0)));

            assertDenied(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.a, "1"), item(f.b, "1")), 1));
            assertDenied(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2")), 1));
            assertDenied(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.c, "2"), item(f.b, "1")), 1));
            assertDenied(replace(f.memberToken, f.home, date, "DINNER", List.of(), 1));
            assertEquals(1L, slotVersion(f.home, date, "DINNER"));
            assertEquals(Map.of(f.a, "2", f.b, "1"), items(f.home, date, "DINNER"));

            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "1"), item(f.b, "1")), 1)));
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "1")), 2)));
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.c, "1")), 3)));
            assertEquals(Map.of(f.c, "1"), items(f.home, date, "DINNER"));
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER", List.of(), 4)));
            assertTrue(items(f.home, date, "DINNER").isEmpty());
        } finally {
            f.cleanup();
        }
    }

    @Test void memberCannotLockOrUnlock() throws Exception {
        Fixture f = fixture(2);
        try {
            String date = day(3);
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2")), 0)));
            assertDenied(lock(f.memberToken, f.home, date, "DINNER", true, 1));
            assertDenied(lock(f.memberToken, f.home, date, "DINNER", false, 1));
            assertEquals(false, jdbc.queryForObject("SELECT locked FROM plan_slots"
                    + " WHERE household_id=? AND date=CAST(? AS DATE) AND meal_type=?", Boolean.class,
                    f.home, date, "DINNER"));

            assertEquals(200, status(lock(f.ownerToken, f.home, date, "DINNER", true, 1)));
            assertDenied(lock(f.memberToken, f.home, date, "DINNER", false, 2));
            assertEquals(200, status(lock(f.ownerToken, f.home, date, "DINNER", false, 2)));

            String fresh = day(4);
            assertDenied(lock(f.memberToken, f.home, fresh, "LUNCH", false, 0));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM plan_slots"
                    + " WHERE household_id=? AND date=CAST(? AS DATE) AND meal_type=? AND locked=false",
                    Integer.class, f.home, fresh, "LUNCH"));
        } finally {
            f.cleanup();
        }
    }

    @Test void memberCannotBypassThroughBatchCommit() throws Exception {
        Fixture f = fixture(3);
        try {
            String first = day(5);
            String second = day(6);
            assertEquals(200, status(replace(f.ownerToken, f.home, first, "LUNCH",
                    List.of(item(f.a, "3")), 0)));
            assertEquals(200, status(replace(f.ownerToken, f.home, second, "LUNCH",
                    List.of(item(f.b, "2")), 0)));

            MvcResult denied = commit(f.memberToken, f.home, List.of(
                    slot(first, "LUNCH", 1, item(f.a, "1")),
                    slot(second, "LUNCH", 1, item(f.b, "4"))), UUID.randomUUID());
            assertDenied(denied);
            assertEquals("3", items(f.home, first, "LUNCH").get(f.a));
            assertEquals("2", items(f.home, second, "LUNCH").get(f.b));
            assertEquals(1L, slotVersion(f.home, first, "LUNCH"));
            assertEquals(1L, slotVersion(f.home, second, "LUNCH"));

            String third = day(7);
            assertEquals(200, status(commit(f.memberToken, f.home, List.of(
                    slot(third, "DINNER", 0, item(f.c, "1")),
                    slot(second, "LUNCH", 1, item(f.b, "4"))), UUID.randomUUID())));
            assertEquals("4", items(f.home, second, "LUNCH").get(f.b));

            assertEquals(200, status(commit(f.ownerToken, f.home, List.of(
                    slot(second, "LUNCH", 2, item(f.b, "2"))), UUID.randomUUID())));
        } finally {
            f.cleanup();
        }
    }

    @Test void aSameNameRecipeCannotBeUsedToReplaceAnExistingDish() throws Exception {
        Fixture f = fixture(3, "Shared dish name");
        try {
            String date = day(8);
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2")), 0)));
            assertDenied(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.c, "2")), 1));
            assertEquals("2", items(f.home, date, "DINNER").get(f.a));

            assertEquals(200, status(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2"), item(f.c, "1")), 1)));
            assertDenied(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.c, "1")), 2));
            assertEquals(Map.of(f.a, "2", f.c, "1"), items(f.home, date, "DINNER"));
        } finally {
            f.cleanup();
        }
    }

    @Test void concurrentIncreasesConflictOnVersionWithoutLosingData() throws Exception {
        Fixture f = fixture(4);
        String date = day(9);
        var pool = Executors.newFixedThreadPool(2);
        try {
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "1")), 0)));
            long version = slotVersion(f.home, date, "DINNER");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> one = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return status(replace(f.memberToken, f.home, date, "DINNER",
                        List.of(item(f.a, "2")), version));
            });
            Future<Integer> two = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return status(replace(f.memberTwoToken, f.home, date, "DINNER",
                        List.of(item(f.a, "2")), version));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int first = one.get(15, TimeUnit.SECONDS);
            int second = two.get(15, TimeUnit.SECONDS);
            assertTrue((first == 200 && second == 409) || (first == 409 && second == 200),
                    "one write must win: " + first + "/" + second);
            assertEquals("2", items(f.home, date, "DINNER").get(f.a));
            assertEquals(version + 1, slotVersion(f.home, date, "DINNER"));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            f.cleanup();
        }
    }

    @Test void ownershipTransferChangesPlanPermissionsImmediately() throws Exception {
        Fixture f = fixture(2);
        try {
            String date = day(10);
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2")), 0)));
            UUID requestId = transferRequest(f);
            assertEquals(200, accept(f.memberToken, f.home, requestId));

            assertDenied(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "1")), 1));
            assertDenied(lock(f.ownerToken, f.home, date, "DINNER", true, 1));
            assertDenied(commit(f.ownerToken, f.home, List.of(
                    slot(date, "DINNER", 1, item(f.a, "1"))), UUID.randomUUID()));
            assertEquals(200, status(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.a, "1")), 1)));
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "1"), item(f.b, "1")), 2)));
        } finally {
            f.cleanup();
        }
    }

    @Test void pendingClaimFamilyHasNoOwnerPowers() throws Exception {
        Fixture f = fixture(2);
        try {
            String date = day(11);
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2")), 0)));
            long version = slotVersion(f.home, date, "DINNER");
            jdbc.update("UPDATE households SET owner_user_id=NULL, owner_source='PENDING_CLAIM'"
                    + " WHERE id=?", f.home);

            assertEquals(200, status(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.a, "3"), item(f.b, "1")), version)));
            assertDenied(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.a, "1")), version + 1));
            assertDenied(lock(f.memberToken, f.home, date, "DINNER", true, version + 1));
        } finally {
            f.cleanup();
        }
    }

    @Test void nonMembersCannotReadOrWritePlans() throws Exception {
        Fixture f = fixture(2);
        try {
            String date = day(12);
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2")), 0)));
            assertEquals(404, status(call(get("/api/v1/households/" + f.home + "/plan-slots")
                    .param("from", date).param("to", date)
                    .header("Authorization", f.outsiderToken))));
            assertEquals(404, status(replace(f.outsiderToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2")), 1)));
            assertEquals(404, status(lock(f.outsiderToken, f.home, date, "DINNER", true, 1)));
            assertEquals(404, status(commit(f.outsiderToken, f.home, List.of(
                    slot(date, "DINNER", 1, item(f.a, "2"))), UUID.randomUUID())));
        } finally {
            f.cleanup();
        }
    }

    @Test void versionAndLockedErrorsPrecedePermissionChecks() throws Exception {
        Fixture f = fixture(2);
        try {
            String date = day(13);
            assertEquals(200, status(replace(f.ownerToken, f.home, date, "DINNER",
                    List.of(item(f.a, "2")), 0)));
            assertEquals(409, status(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.a, "1")), 0)));
            assertEquals(409, status(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.a, "3")), 0)));

            assertEquals(200, status(lock(f.ownerToken, f.home, date, "DINNER", true, 1)));
            assertEquals(409, status(replace(f.memberToken, f.home, date, "DINNER",
                    List.of(item(f.a, "3")), 2)));
            assertEquals("2", items(f.home, date, "DINNER").get(f.a));
        } finally {
            f.cleanup();
        }
    }

    @Test void commitReplayNeverReExecutesAndRespectsCurrentMembership() throws Exception {
        Fixture f = fixture(2);
        try {
            String date = day(14);
            UUID key = UUID.randomUUID();
            MvcResult first = commit(f.ownerToken, f.home, List.of(
                    slot(date, "LUNCH", 0, item(f.a, "2"))), key);
            assertEquals(200, status(first));
            assertEquals(1L, slotVersion(f.home, date, "LUNCH"));
            assertEquals(200, status(commit(f.ownerToken, f.home, List.of(
                    slot(date, "LUNCH", 0, item(f.a, "2"))), key)));
            assertEquals(1L, slotVersion(f.home, date, "LUNCH"));

            UUID requestId = transferRequest(f);
            assertEquals(200, accept(f.memberToken, f.home, requestId));
            assertEquals(200, status(commit(f.ownerToken, f.home, List.of(
                    slot(date, "LUNCH", 0, item(f.a, "2"))), key)));
            assertEquals(1L, slotVersion(f.home, date, "LUNCH"));
            assertDenied(commit(f.ownerToken, f.home, List.of(
                    slot(date, "LUNCH", 1, item(f.a, "1"))), UUID.randomUUID()));

            long membershipVersion = jdbc.queryForObject("SELECT version FROM memberships"
                    + " WHERE household_id=? AND user_id=? AND active", Long.class, f.home, f.owner);
            assertEquals(204, status(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/households/" + f.home + "/members/" + f.owner)
                            .header("Authorization", f.ownerToken)
                            .param("expectedVersion", Long.toString(membershipVersion)))
                    .andReturn()));
            assertEquals(404, status(commit(f.ownerToken, f.home, List.of(
                    slot(date, "LUNCH", 0, item(f.a, "2"))), key)));
        } finally {
            f.cleanup();
        }
    }

    // ---- fixtures ----

    private final class Fixture {
        final UUID owner, member, memberTwo, outsider, home, a, b, c;
        final String ownerToken, memberToken, memberTwoToken, outsiderToken;

        Fixture(UUID owner, UUID member, UUID memberTwo, UUID outsider, UUID home, UUID a, UUID b, UUID c,
                String ownerToken, String memberToken, String memberTwoToken, String outsiderToken) {
            this.owner = owner; this.member = member; this.memberTwo = memberTwo; this.outsider = outsider;
            this.home = home; this.a = a; this.b = b; this.c = c;
            this.ownerToken = ownerToken; this.memberToken = memberToken;
            this.memberTwoToken = memberTwoToken; this.outsiderToken = outsiderToken;
        }

        void cleanup() {
            jdbc.update("DELETE FROM ownership_transfer_requests WHERE household_id=?", home);
            for (UUID user : users()) {
                jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
                jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            }
            jdbc.update("DELETE FROM plan_items WHERE household_id=?", home);
            jdbc.update("DELETE FROM plan_slots WHERE household_id=?", home);
            jdbc.update("DELETE FROM recipes WHERE household_id=?", home);
            jdbc.update("DELETE FROM invitations WHERE household_id=?", home);
            for (UUID user : users()) {
                jdbc.update("DELETE FROM memberships WHERE user_id=?", user);
            }
            jdbc.update("DELETE FROM households WHERE id=?", home);
            for (UUID user : users()) {
                jdbc.update("DELETE FROM users WHERE id=?", user);
            }
        }

        private List<UUID> users() {
            List<UUID> users = new ArrayList<>(List.of(owner, member, outsider));
            if (memberTwo != null) users.add(memberTwo);
            return users;
        }
    }

    private Fixture fixture(int members) throws Exception {
        return fixture(members, null);
    }

    private Fixture fixture(int members, String sameNameDish) throws Exception {
        UUID owner = user("pown");
        UUID member = user("pmem");
        UUID memberTwo = members >= 4 ? user("pmem2") : null;
        UUID outsider = user("pout");
        UUID home = UUID.randomUUID();
        jdbc.update("INSERT INTO households(id,name,zone_id,owner_user_id,owner_source)"
                + " VALUES (?,?,'UTC',?,'CREATED')", home, "Plan permission home", owner);
        jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                UUID.randomUUID(), home, owner);
        for (UUID extra : memberTwo == null ? List.of(member) : List.of(member, memberTwo)) {
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'MEMBER',true)",
                    UUID.randomUUID(), home, extra);
        }
        UUID a = recipe(home, owner, sameNameDish == null ? "Dish A" : sameNameDish);
        UUID b = recipe(home, owner, "Dish B");
        UUID c = recipe(home, owner, sameNameDish == null ? "Dish C" : sameNameDish);
        return new Fixture(owner, member, memberTwo, outsider, home, a, b, c,
                login(owner), login(member), memberTwo == null ? null : login(memberTwo), login(outsider));
    }

    private UUID user(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                id, prefix + "_" + id.toString().replace("-", "").substring(0, 16), "Plan permission",
                passwords.encode("fixture-password"), "UTC");
        return id;
    }

    private String login(UUID user) throws Exception {
        String name = jdbc.queryForObject("SELECT username FROM users WHERE id=?", String.class, user);
        MvcResult result = http.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", "fixture-password"))))
                .andReturn();
        assertEquals(200, status(result));
        return "Bearer " + json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private UUID recipe(UUID home, UUID author, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,duration_minutes,yield_servings)"
                        + " VALUES (?,?,?,?,CAST(? AS jsonb),20,2)",
                id, home, author, name, "[\"cook\"]");
        return id;
    }

    private Map<String, Object> item(UUID recipe, String servings) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("recipeId", recipe.toString());
        value.put("servings", servings);
        return value;
    }

    private Map<String, Object> slot(String date, String meal, long expectedVersion, Map<String, Object>... items) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("date", date);
        value.put("mealType", meal);
        value.put("expectedVersion", expectedVersion);
        value.put("items", List.of(items));
        return value;
    }

    private MvcResult replace(String token, UUID home, String date, String meal,
                              List<Map<String, Object>> items, long expectedVersion) throws Exception {
        return call(put("/api/v1/households/" + home + "/plan-slots/" + date + "/" + meal)
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("items", items, "expectedVersion", expectedVersion))));
    }

    private MvcResult lock(String token, UUID home, String date, String meal, boolean locked, long expectedVersion)
            throws Exception {
        return call(put("/api/v1/households/" + home + "/plan-slots/" + date + "/" + meal + "/lock")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("locked", locked, "expectedVersion", expectedVersion))));
    }

    private MvcResult commit(String token, UUID home, List<Map<String, Object>> slots, UUID key) throws Exception {
        return call(post("/api/v1/households/" + home + "/plan-commits")
                .header("Authorization", token)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slots", slots))));
    }

    private UUID transferRequest(Fixture f) throws Exception {
        MvcResult result = call(post("/api/v1/households/" + f.home + "/owner-transfer-requests")
                .header("Authorization", f.ownerToken)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("targetUserId", f.member.toString()))));
        assertEquals(201, status(result));
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private int accept(String token, UUID home, UUID requestId) throws Exception {
        return status(call(post("/api/v1/households/" + home + "/owner-transfer-requests/" + requestId + "/accept")
                .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID())));
    }

    private Map<UUID, String> items(UUID home, String date, String meal) {
        Map<UUID, String> result = new LinkedHashMap<>();
        jdbc.query("SELECT i.recipe_id, i.servings FROM plan_items i JOIN plan_slots s ON s.id=i.slot_id"
                        + " WHERE s.household_id=? AND s.date=CAST(? AS DATE) AND s.meal_type=?"
                        + " ORDER BY i.position",
                (rs, n) -> Map.entry(rs.getObject(1, UUID.class),
                        rs.getBigDecimal(2).stripTrailingZeros().toPlainString()),
                home, date, meal).forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private long slotVersion(UUID home, String date, String meal) {
        return jdbc.queryForObject("SELECT version FROM plan_slots"
                + " WHERE household_id=? AND date=CAST(? AS DATE) AND meal_type=?", Long.class, home, date, meal);
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return http.perform(request).andReturn();
    }

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private void assertDenied(MvcResult result) throws Exception {
        assertEquals(403, status(result), result.getResponse().getContentAsString());
        assertEquals("PLAN_PERMISSION_DENIED",
                json.readTree(result.getResponse().getContentAsString()).path("code").asText());
    }

    private static String day(int offset) {
        return LocalDate.now().plusDays(offset).toString();
    }
}
