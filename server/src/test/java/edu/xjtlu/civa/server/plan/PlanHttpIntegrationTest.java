package edu.xjtlu.civa.server.plan;

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

    private String login(String name) throws Exception {
        String body = json.writeValueAsString(Map.of("username", name, "password", "long-password"));
        var result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
