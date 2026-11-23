package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class HouseholdRecipeHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void householdRecipesAreSharedVersionedAndSnapshotIntoTheAuthor() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        try {
            String name = "u_" + user.toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Test", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO households(id,name) VALUES (?,?)", home, "Test home");
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                    UUID.randomUUID(), home, user);
            String token = login(name);
            String ingredientId = jdbc.queryForObject(
                    "SELECT id::text FROM ingredients WHERE unit='G' ORDER BY name LIMIT 1", String.class);

            String body = json.writeValueAsString(Map.of(
                    "name", "家庭番茄蛋", "steps", List.of("切块", "合炒"),
                    "ingredients", List.of(Map.of("ingredientId", ingredientId, "quantity", "2",
                            "unit", "G", "trackStock", true)),
                    "yieldServings", "2", "durationMinutes", 20, "tags", List.of("家常菜")));
            String key = UUID.randomUUID().toString();
            var created = http.perform(post("/api/v1/households/{id}/recipes", home)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("家庭番茄蛋"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andReturn();
            String recipeId = json.readTree(created.getResponse().getContentAsString()).path("id").asText();

            http.perform(post("/api/v1/households/{id}/recipes", home).header("Authorization", bearer(token))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(recipeId));

            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM user_recipes WHERE user_id=?",
                    Integer.class, user));

            http.perform(get("/api/v1/households/{id}/recipes", home).header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
            http.perform(get("/api/v1/households/{id}/recipes/{r}", home, recipeId)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.ingredients[0].ingredientId").value(ingredientId));

            String update = json.writeValueAsString(Map.of(
                    "name", "改名家庭番茄蛋", "steps", List.of("切块", "合炒"),
                    "ingredients", List.of(Map.of("ingredientId", ingredientId, "quantity", "3",
                            "unit", "G", "trackStock", true)),
                    "yieldServings", "2", "durationMinutes", 25, "tags", List.of("家常菜"),
                    "expectedVersion", 1));
            http.perform(put("/api/v1/households/{id}/recipes/{r}", home, recipeId)
                            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(update))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("改名家庭番茄蛋"))
                    .andExpect(jsonPath("$.version").value(2));
            http.perform(put("/api/v1/households/{id}/recipes/{r}", home, recipeId)
                            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON).content(update))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

            http.perform(put("/api/v1/households/{id}/recipes/{r}/archive", home, recipeId)
                            .header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("archived", true, "expectedVersion", 2))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));

            http.perform(put("/api/v1/me/wishlist/{r}", recipeId).header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());
            http.perform(get("/api/v1/me/wishlist").header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
            http.perform(delete("/api/v1/me/wishlist/{r}", recipeId).header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            http.perform(get("/api/v1/households/{id}/recipes", home)).andExpect(status().isUnauthorized());
            UUID outsider = UUID.randomUUID();
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    outsider, "u_" + outsider.toString().replace("-", "").substring(0, 20), "Out",
                    passwords.encode("long-password"), "UTC");
            String outsiderToken = login("u_" + outsider.toString().replace("-", "").substring(0, 20));
            http.perform(get("/api/v1/households/{id}/recipes", home).header("Authorization", bearer(outsiderToken)))
                    .andExpect(status().isNotFound());
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", outsider);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", outsider);
            jdbc.update("DELETE FROM users WHERE id=?", outsider);
        } finally {
            jdbc.update("DELETE FROM wishlists WHERE user_id=?", user);
            jdbc.update("DELETE FROM recipe_favorites WHERE user_id=?", user);
            jdbc.update("DELETE FROM user_recipes WHERE user_id=?", user);
            jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id IN (SELECT id FROM recipes WHERE household_id=?)", home);
            jdbc.update("DELETE FROM recipes WHERE household_id=?", home);
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
            jdbc.update("DELETE FROM memberships WHERE household_id=?", home);
            jdbc.update("DELETE FROM households WHERE id=?", home);
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
