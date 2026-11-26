package edu.xjtlu.civa.server.record;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
import java.time.LocalDate;
import java.util.ArrayList;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class CookingEventHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void cookingPreviewConfirmsOnceAndDeductsFridgeBatches() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        try {
            String name = "u_" + user.toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Cook", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO households(id,name,zone_id) VALUES (?,?,'UTC')", home, "Cook home");
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                    UUID.randomUUID(), home, user);
            String token = login(name);

            UUID ingredient = jdbc.queryForObject(
                    "SELECT id FROM ingredients WHERE unit='G' ORDER BY name LIMIT 1", UUID.class);
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,duration_minutes,"
                            + "yield_servings,total_kcal) VALUES (?,?,?,?,CAST(? AS jsonb),?,?,?)",
                    recipe, home, user, "冰箱联动测试菜", "[\"切\",\"炒\"]", 20, 2, 500);
            jdbc.update("INSERT INTO recipe_ingredients(recipe_id,ingredient_id,position,quantity,unit,track_stock)"
                    + " VALUES (?,?,0,100,'G',true)", recipe, ingredient);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            jdbc.update("INSERT INTO inventory_batches(id,household_id,ingredient_id,quantity,unit,location,expires_on)"
                            + " VALUES (?,?,?,60,'G','冷藏',?)",
                    first, home, ingredient, LocalDate.now().plusDays(2));
            jdbc.update("INSERT INTO inventory_batches(id,household_id,ingredient_id,quantity,unit,location,expires_on)"
                            + " VALUES (?,?,?,100,'G','冷藏',?)",
                    second, home, ingredient, LocalDate.now().plusDays(5));

            String previewBody = json.writeValueAsString(Map.of("recipeId", recipe.toString(), "servings", "2"));
            var preview = http.perform(post("/api/v1/households/{id}/cooking-previews", home)
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(previewBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recipeVersion").value(1))
                    .andExpect(jsonPath("$.shortages").isEmpty())
                    .andReturn();
            JsonNode allocations = json.readTree(preview.getResponse().getContentAsString()).path("allocations");
            assertEquals(2, allocations.size());

            List<Map<String, Object>> lines = new ArrayList<>();
            for (JsonNode allocation : allocations) {
                lines.add(Map.of("batchId", allocation.path("batchId").asText(),
                        "expectedVersion", allocation.path("expectedVersion").asLong(),
                        "ingredientId", allocation.path("ingredientId").asText(),
                        "quantity", allocation.path("quantity").asText(),
                        "unit", allocation.path("unit").asText()));
            }
            String confirmBody = json.writeValueAsString(Map.of(
                    "recipeId", recipe.toString(), "recipeVersion", 1, "servings", "2",
                    "cookedAt", "2026-11-26T04:00:00.000Z", "allocations", lines));
            String key = UUID.randomUUID().toString();
            var confirmed = http.perform(post("/api/v1/households/{id}/cooking-events", home)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.recipe.recipeName").value("冰箱联动测试菜"))
                    .andExpect(jsonPath("$.servings").value("2"))
                    .andExpect(jsonPath("$.requestId").value(key))
                    .andReturn();
            String eventId = json.readTree(confirmed.getResponse().getContentAsString()).path("id").asText();

            http.perform(post("/api/v1/households/{id}/cooking-events", home)
                            .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(eventId));

            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM inventory_movements"
                    + " WHERE household_id=? AND reason='COOK'", Integer.class, home));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inventory_movements"
                    + " WHERE household_id=? AND cooking_event_id IS NULL", Integer.class, home));
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM inventory_movements"
                    + " WHERE household_id=? AND reason='COOK' AND cooking_event_id=?", Integer.class,
                    home, UUID.fromString(eventId)));
            assertEquals(0, jdbc.queryForObject("SELECT quantity FROM inventory_batches WHERE id=?",
                    java.math.BigDecimal.class, first).compareTo(java.math.BigDecimal.ZERO));
            assertEquals(0, jdbc.queryForObject("SELECT quantity FROM inventory_batches WHERE id=?",
                    java.math.BigDecimal.class, second).compareTo(new java.math.BigDecimal("60")));

            // The empty batch disappears from the fridge list, the remaining one stays.
            http.perform(get("/api/v1/households/{id}/inventory-batches", home)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));

            http.perform(get("/api/v1/households/{id}/cooking-events", home)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].recipe.recipeName").value("冰箱联动测试菜"));

            // Stale allocations (the batches changed) must not deduct twice.
            http.perform(post("/api/v1/households/{id}/cooking-events", home)
                            .header("Authorization", bearer(token))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content(confirmBody))
                    .andExpect(status().isConflict());

            http.perform(get("/api/v1/households/{id}/cooking-events", home))
                    .andExpect(status().isUnauthorized());
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
