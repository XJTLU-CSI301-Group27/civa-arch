package edu.xjtlu.civa.server.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Idempotent replays must re-evaluate the caller's current rights instead of trusting the key.
 * The stored success payload is only returned to the actor that produced it.
 */
@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class IdempotentReplayAuthorizationHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void cookingReplayAfterMembershipRemovalIsRejected() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        UUID ingredient = UUID.randomUUID();
        UUID batch = UUID.randomUUID();
        try {
            String name = user("u_", user);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Replay", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO households(id,name,zone_id,owner_user_id,owner_source) VALUES (?,?,'UTC',?,'CREATED')", home, "Replay home", user);
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                    UUID.randomUUID(), home, user);
            jdbc.update("INSERT INTO ingredients(id,name,unit,active) VALUES (?,?,'G',true)",
                    ingredient, "replay-gram-" + ingredient.toString().substring(0, 8));
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,yield_servings,duration_minutes)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),?,?)",
                    recipe, home, user, "重放测试菜", "[\"炒\"]", 2, 20);
            jdbc.update("INSERT INTO recipe_ingredients(recipe_id,ingredient_id,position,quantity,unit,track_stock)"
                    + " VALUES (?,?,0,100,'G',true)", recipe, ingredient);
            jdbc.update("INSERT INTO inventory_batches(id,household_id,ingredient_id,quantity,unit,location)"
                    + " VALUES (?,?,?,200,'G','冷藏')", batch, home, ingredient);
            String token = login(name);

            String body = json.writeValueAsString(Map.of(
                    "recipeId", recipe.toString(), "recipeVersion", 1, "servings", "2",
                    "cookedAt", "2026-11-26T04:00:00.000Z",
                    "allocations", List.of(Map.of("batchId", batch.toString(), "expectedVersion", 1,
                            "ingredientId", ingredient.toString(), "quantity", "100", "unit", "G"))));
            String key = UUID.randomUUID().toString();
            var created = http.perform(post("/api/v1/households/{id}/cooking-events", home)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andReturn();
            String eventId = json.readTree(created.getResponse().getContentAsString()).path("id").asText();

            jdbc.update("UPDATE memberships SET active=false WHERE household_id=? AND user_id=?", home, user);

            var replay = http.perform(post("/api/v1/households/{id}/cooking-events", home)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andReturn();
            assertFalse(replay.getResponse().getContentAsString().contains(eventId),
                    "a removed member must not receive the stored cooking event");
            assertFalse(replay.getResponse().getContentAsString().contains("重放测试菜"),
                    "a removed member must not receive the stored recipe snapshot");
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM cooking_events WHERE household_id=?",
                    Integer.class, home));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE household_id=?",
                    Integer.class, home));
        } finally {
            jdbc.update("DELETE FROM inventory_movements WHERE household_id=?", home);
            jdbc.update("DELETE FROM cooking_events WHERE household_id=?", home);
            jdbc.update("DELETE FROM inventory_batches WHERE household_id=?", home);
            jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id=?", recipe);
            jdbc.update("DELETE FROM recipes WHERE household_id=?", home);
            jdbc.update("DELETE FROM memberships WHERE household_id=?", home);
            jdbc.update("DELETE FROM households WHERE id=?", home);
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
            jdbc.update("DELETE FROM ingredients WHERE id=?", ingredient);
        }
    }

    @Test void planCommitReplayAfterMembershipRemovalIsRejected() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        try {
            String name = user("u_", user);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Replay", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO households(id,name,zone_id,owner_user_id,owner_source) VALUES (?,?,'UTC',?,'CREATED')", home, "Replay plan home", user);
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                    UUID.randomUUID(), home, user);
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,yield_servings,duration_minutes)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),?,?)",
                    recipe, home, user, "重放计划菜", "[\"炒\"]", 2, 20);
            String token = login(name);

            String commit = json.writeValueAsString(Map.of("slots", List.of(Map.of(
                    "date", LocalDate.now().plusDays(1).toString(), "mealType", "LUNCH", "expectedVersion", 0,
                    "items", List.of(Map.of("recipeId", recipe.toString(), "servings", "1"))))));
            String key = UUID.randomUUID().toString();
            http.perform(post("/api/v1/households/{id}/plan-commits", home)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(commit))
                    .andExpect(status().isOk());

            jdbc.update("UPDATE memberships SET active=false WHERE household_id=? AND user_id=?", home, user);

            var replay = http.perform(post("/api/v1/households/{id}/plan-commits", home)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(commit))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andReturn();
            assertFalse(replay.getResponse().getContentAsString().contains("重放计划菜"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM plan_slots WHERE household_id=?",
                    Integer.class, home));
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

    @Test void planCommitReplayIsScopedToTheActorAndCurrentHousehold() throws Exception {
        UUID first = UUID.randomUUID();
        UUID firstHome = UUID.randomUUID();
        UUID firstRecipe = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID secondHome = UUID.randomUUID();
        UUID secondRecipe = UUID.randomUUID();
        try {
            String firstName = user("u_", first);
            String secondName = user("u_", second);
            seedActor(first, firstName, firstHome, "A home");
            seedActor(second, secondName, secondHome, "B home");
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,yield_servings,duration_minutes)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),?,?)",
                    firstRecipe, firstHome, first, "A 的菜", "[\"炒\"]", 2, 20);
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,yield_servings,duration_minutes)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),?,?)",
                    secondRecipe, secondHome, second, "B 的菜", "[\"炒\"]", 2, 20);
            String firstToken = login(firstName);
            String secondToken = login(secondName);

            String firstCommit = commitBody(firstRecipe, LocalDate.now().plusDays(2));
            String key = UUID.randomUUID().toString();
            http.perform(post("/api/v1/households/{id}/plan-commits", firstHome)
                            .header("Authorization", bearer(firstToken)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(firstCommit))
                    .andExpect(status().isOk());

            // The second actor cannot use the first actor's key on the first household.
            var crossHome = http.perform(post("/api/v1/households/{id}/plan-commits", firstHome)
                            .header("Authorization", bearer(secondToken)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(firstCommit))
                    .andExpect(status().isNotFound())
                    .andReturn();
            assertFalse(crossHome.getResponse().getContentAsString().contains("A 的菜"));
            http.perform(get("/api/v1/households/{id}/plan-slots", firstHome)
                            .param("from", LocalDate.now().plusDays(2).toString())
                            .param("to", LocalDate.now().plusDays(2).toString())
                            .header("Authorization", bearer(secondToken)))
                    .andExpect(status().isNotFound());

            // The same key is a fresh operation for the second actor in their own household.
            String secondCommit = commitBody(secondRecipe, LocalDate.now().plusDays(3));
            http.perform(post("/api/v1/households/{id}/plan-commits", secondHome)
                            .header("Authorization", bearer(secondToken)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(secondCommit))
                    .andExpect(status().isOk());
            // Retrying the same intent returns the stored result, a changed intent is rejected.
            http.perform(post("/api/v1/households/{id}/plan-commits", secondHome)
                            .header("Authorization", bearer(secondToken)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(secondCommit))
                    .andExpect(status().isOk());
            http.perform(post("/api/v1/households/{id}/plan-commits", secondHome)
                            .header("Authorization", bearer(secondToken)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(commitBody(secondRecipe, LocalDate.now().plusDays(4))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM plan_slots WHERE household_id=?",
                    Integer.class, secondHome));
        } finally {
            for (UUID home : List.of(firstHome, secondHome)) {
                jdbc.update("DELETE FROM plan_items WHERE household_id=?", home);
                jdbc.update("DELETE FROM plan_slots WHERE household_id=?", home);
                jdbc.update("DELETE FROM recipes WHERE household_id=?", home);
                jdbc.update("DELETE FROM memberships WHERE household_id=?", home);
                jdbc.update("DELETE FROM households WHERE id=?", home);
            }
            for (UUID actor : List.of(first, second)) {
                jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", actor);
                jdbc.update("DELETE FROM sessions WHERE user_id=?", actor);
                jdbc.update("DELETE FROM users WHERE id=?", actor);
            }
        }
    }

    @Test void mealEntryReplayStaysPersonalAndVerifiesTheStoredOwner() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try {
            String firstName = user("u_", first);
            String secondName = user("u_", second);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    first, firstName, "Diary A", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    second, secondName, "Diary B", passwords.encode("long-password"), "UTC");
            String firstToken = login(firstName);
            String secondToken = login(secondName);

            String body = json.writeValueAsString(Map.of(
                    "date", LocalDate.now().toString(), "mealType", "LUNCH", "name", "面条",
                    "amount", "1", "basis", "MANUAL", "basisKcal", "400", "note", ""));
            String key = UUID.randomUUID().toString();
            var created = http.perform(post("/api/v1/me/meal-entries")
                            .header("Authorization", bearer(firstToken)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userId").value(first.toString()))
                    .andReturn();
            String firstEntry = json.readTree(created.getResponse().getContentAsString()).path("id").asText();

            // The same intent replays to the same entry.
            http.perform(post("/api/v1/me/meal-entries")
                            .header("Authorization", bearer(firstToken)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(firstEntry));

            // Another actor reusing the same key creates their own entry, never the first one.
            var other = http.perform(post("/api/v1/me/meal-entries")
                            .header("Authorization", bearer(secondToken)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userId").value(second.toString()))
                    .andReturn();
            assertNotEquals(firstEntry, json.readTree(other.getResponse().getContentAsString()).path("id").asText());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM meal_entries WHERE user_id=?",
                    Integer.class, first));

            // A stored payload that no longer belongs to the caller must never be replayed.
            jdbc.update("UPDATE idempotency_requests SET response_body = jsonb_set(response_body, '{userId}',"
                            + " to_jsonb(CAST(? AS text))) WHERE actor_id=? AND key=?",
                    second.toString(), first, UUID.fromString(key));
            var tampered = http.perform(post("/api/v1/me/meal-entries")
                            .header("Authorization", bearer(firstToken)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                    .andReturn();
            assertFalse(tampered.getResponse().getContentAsString().contains(second.toString()));
        } finally {
            jdbc.update("DELETE FROM meal_entries WHERE user_id IN (?,?)", first, second);
            for (UUID actor : List.of(first, second)) {
                jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", actor);
                jdbc.update("DELETE FROM sessions WHERE user_id=?", actor);
                jdbc.update("DELETE FROM users WHERE id=?", actor);
            }
        }
    }

    private void seedActor(UUID user, String username, UUID home, String homeName) {
        jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                user, username, "Replay", passwords.encode("long-password"), "UTC");
        jdbc.update("INSERT INTO households(id,name,zone_id,owner_user_id,owner_source) VALUES (?,?,'UTC',?,'CREATED')", home, homeName, user);
        jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                UUID.randomUUID(), home, user);
    }

    private String commitBody(UUID recipe, LocalDate date) throws Exception {
        return json.writeValueAsString(Map.of("slots", List.of(Map.of(
                "date", date.toString(), "mealType", "LUNCH", "expectedVersion", 0,
                "items", List.of(Map.of("recipeId", recipe.toString(), "servings", "1"))))));
    }

    private static String user(String prefix, UUID id) {
        return prefix + id.toString().replace("-", "").substring(0, 20);
    }

    private String login(String name) throws Exception {
        String body = json.writeValueAsString(Map.of("username", name, "password", "long-password"));
        var result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
