package edu.xjtlu.civa.server.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class PlanHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;

    @Test void planSlotsAreVersionedLockableAndCommitIsIdempotent() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        try {
            String name = "u_" + user.toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Plan", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO households(id,name,zone_id) VALUES (?,?,'UTC')", home, "Plan home");
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                    UUID.randomUUID(), home, user);
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,duration_minutes,yield_servings)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),20,2)",
                    recipe, home, user, "Plan dish", "[\"cook\"]");
            String token = login(name);
            String date = LocalDate.now().plusDays(1).toString();

            http.perform(get("/api/v1/households/{id}/plan-slots", home)
                            .param("from", date).param("to", date)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));

            String replace = json.writeValueAsString(Map.of("items",
                    List.of(Map.of("recipeId", recipe.toString(), "servings", "2")), "expectedVersion", 0));
            http.perform(put("/api/v1/households/{id}/plan-slots/{date}/{meal}", home, date, "DINNER")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(replace))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(1))
                    .andExpect(jsonPath("$.items[0].recipeId").value(recipe.toString()))
                    .andExpect(jsonPath("$.locked").value(false));

            http.perform(put("/api/v1/households/{id}/plan-slots/{date}/{meal}/lock", home, date, "DINNER")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("locked", true, "expectedVersion", 1))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2))
                    .andExpect(jsonPath("$.locked").value(true));

            http.perform(put("/api/v1/households/{id}/plan-slots/{date}/{meal}", home, date, "DINNER")
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("items", List.of(), "expectedVersion", 2))))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_SLOT_LOCKED"));

            // The locked slot survives a generated commit; the unlocked new slot is created.
            String other = LocalDate.now().plusDays(2).toString();
            String commit = json.writeValueAsString(Map.of("slots", List.of(
                    Map.of("date", date, "mealType", "DINNER", "expectedVersion", 2,
                            "items", List.of(Map.of("recipeId", recipe.toString(), "servings", "1"))),
                    Map.of("date", other, "mealType", "LUNCH", "expectedVersion", 0,
                            "items", List.of(Map.of("recipeId", recipe.toString(), "servings", "3"))))));
            String key = UUID.randomUUID().toString();
            http.perform(post("/api/v1/households/{id}/plan-commits", home)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(commit))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].locked").value(true))
                    .andExpect(jsonPath("$[1].version").value(1));
            Long versionOfLunch = jdbc.queryForObject("SELECT version FROM plan_slots"
                    + " WHERE household_id=? AND date=CAST(? AS DATE) AND meal_type=?", Long.class, home,
                    other, "LUNCH");
            assertEquals(1L, versionOfLunch);
            http.perform(post("/api/v1/households/{id}/plan-commits", home)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(commit))
                    .andExpect(status().isOk());
            assertEquals(1L, jdbc.queryForObject("SELECT version FROM plan_slots"
                    + " WHERE household_id=? AND date=CAST(? AS DATE) AND meal_type=?", Long.class, home,
                    other, "LUNCH"));

            UUID outsider = UUID.randomUUID();
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    outsider, "u_" + outsider.toString().replace("-", "").substring(0, 20), "Out",
                    passwords.encode("long-password"), "UTC");
            http.perform(get("/api/v1/households/{id}/plan-slots", home)
                            .param("from", date).param("to", date)
                            .header("Authorization", bearer(login("u_"
                                    + outsider.toString().replace("-", "").substring(0, 20)))))
                    .andExpect(status().isNotFound());
            http.perform(get("/api/v1/households/{id}/plan-slots", home)
                    .param("from", date).param("to", date)).andExpect(status().isUnauthorized());
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", outsider);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", outsider);
            jdbc.update("DELETE FROM users WHERE id=?", outsider);
        } finally {
            jdbc.update("DELETE FROM plan_items WHERE household_id=?", home);
            jdbc.update("DELETE FROM plan_slots WHERE household_id=?", home);
            jdbc.update("DELETE FROM recipes WHERE household_id=?", home);
            jdbc.update("DELETE FROM memberships WHERE household_id=?", home);
            jdbc.update("DELETE FROM households WHERE id=?", home);
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
    }

    /**
     * Two members writing the same empty slot at the same time must resolve as a version
     * conflict. Before the fix the losing INSERT surfaced the unique violation as an HTTP 500.
     */
    @Test void concurrentFirstWriteOnTheSameSlotReportsConflict() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        CountDownLatch slotHeld = new CountDownLatch(1);
        CountDownLatch releaseSlot = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        try {
            String name = "u_" + user.toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Race", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO households(id,name,zone_id) VALUES (?,?,'UTC')", home, "Race home");
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                    UUID.randomUUID(), home, user);
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,duration_minutes,yield_servings)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),20,2)",
                    recipe, home, user, "Race dish", "[\"cook\"]");
            String token = login(name);
            String date = LocalDate.now().plusDays(4).toString();

            String replace = json.writeValueAsString(Map.of("items",
                    List.of(Map.of("recipeId", recipe.toString(), "servings", "1")), "expectedVersion", 0));

            // Hold an uncommitted slot row so the HTTP write has to wait on the unique index.
            Thread holder = new Thread(() -> {
                try {
                    new TransactionTemplate(transactions).execute(status -> {
                        jdbc.update("INSERT INTO plan_slots(id, household_id, date, meal_type, locked)"
                                        + " VALUES (?,?,CAST(? AS DATE),'DINNER',false)",
                                UUID.randomUUID(), home, date);
                        slotHeld.countDown();
                        try {
                            releaseSlot.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Throwable failure) {
                    holderFailure.set(failure);
                    slotHeld.countDown();
                }
            }, "plan-slot-holder");
            holder.start();
            assertTrue(slotHeld.await(10, TimeUnit.SECONDS), "the first writer never inserted the slot");

            AtomicReference<MvcResult> result = new AtomicReference<>();
            AtomicReference<Throwable> requestFailure = new AtomicReference<>();
            CountDownLatch requestStarted = new CountDownLatch(1);
            Thread writer = new Thread(() -> {
                try {
                    requestStarted.countDown();
                    result.set(http.perform(put("/api/v1/households/{id}/plan-slots/{date}/{meal}",
                                    home, date, "DINNER")
                                    .header("Authorization", bearer(token))
                                    .contentType(MediaType.APPLICATION_JSON).content(replace))
                            .andReturn());
                } catch (Throwable failure) {
                    requestFailure.set(failure);
                }
            }, "plan-slot-writer");
            writer.start();
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            Thread.sleep(700);
            releaseSlot.countDown();
            holder.join(10_000);
            writer.join(20_000);

            assertNull(holderFailure.get(), "the first writer failed");
            assertNull(requestFailure.get(), "the concurrent request failed");
            assertNotNull(result.get(), "the concurrent request produced no response");
            assertEquals(409, result.get().getResponse().getStatus(),
                    "a concurrent first write must be reported as a conflict, not a server error");
            assertEquals("VERSION_CONFLICT",
                    json.readTree(result.get().getResponse().getContentAsString()).path("code").asText());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM plan_slots"
                            + " WHERE household_id=? AND date=CAST(? AS DATE) AND meal_type='DINNER'",
                    Integer.class, home, date));
        } finally {
            releaseSlot.countDown();
            jdbc.update("DELETE FROM plan_items WHERE household_id=?", home);
            jdbc.update("DELETE FROM plan_slots WHERE household_id=?", home);
            jdbc.update("DELETE FROM recipes WHERE household_id=?", home);
            jdbc.update("DELETE FROM memberships WHERE household_id=?", home);
            jdbc.update("DELETE FROM households WHERE id=?", home);
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
    }

    private String login(String name) throws Exception {
        String body = json.writeValueAsString(Map.of("username", name, "password", "long-password"));
        var result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
