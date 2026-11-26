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

    /**
     * The confirmation may only deduct what the recipe consumes, from batches that really hold that
     * ingredient, that are not expired and that are still at the expected version. Every rejected
     * attempt must leave cooking events, inventory movements and batch quantities untouched.
     */
    @Test void unsafeCookingAllocationsAreRejectedWithoutSideEffects() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        try {
            String name = "u_" + user.toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Guard", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO households(id,name,zone_id) VALUES (?,?,'UTC')", home, "Guard home");
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                    UUID.randomUUID(), home, user);
            String token = login(name);

            UUID grams = jdbc.queryForObject(
                    "SELECT id FROM ingredients WHERE unit='G' ORDER BY name LIMIT 1", UUID.class);
            UUID count = jdbc.queryForObject(
                    "SELECT id FROM ingredients WHERE unit='COUNT' ORDER BY name LIMIT 1", UUID.class);
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,yield_servings,duration_minutes)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),?,?)",
                    recipe, home, user, "校验测试菜", "[\"切\"]", 2, 20);

            jdbc.update("INSERT INTO recipe_ingredients(recipe_id,ingredient_id,position,quantity,unit,track_stock)"
                    + " VALUES (?,?,0,100,'G',true)", recipe, grams);
            jdbc.update("INSERT INTO recipe_ingredients(recipe_id,ingredient_id,position,quantity,unit,track_stock)"
                    + " VALUES (?,?,1,2,'COUNT',true)", recipe, count);

            UUID stockG = UUID.randomUUID();
            UUID stockC = UUID.randomUUID();
            UUID stockC2 = UUID.randomUUID();
            UUID stockExpired = UUID.randomUUID();
            insertBatch(stockG, home, grams, "200", "G", null);
            insertBatch(stockC, home, count, "10", "COUNT", null);
            insertBatch(stockC2, home, count, "10", "COUNT", null);
            insertBatch(stockExpired, home, grams, "100", "G", LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1));

            String key = UUID.randomUUID().toString();
            // 1. No allocations at all: the tracked ingredients would never be deducted.
            confirm(home, token, key, confirmBody(recipe, 1, List.of()))
                    .andExpect(status().isBadRequest());
            // 2. Incomplete allocation: half of the required grams.
            confirm(home, token, UUID.randomUUID().toString(), confirmBody(recipe, 1, List.of(
                    allocation(stockG, 1, grams, "50", "G"),
                    allocation(stockC, 1, count, "2", "COUNT"))))
                    .andExpect(status().isBadRequest());
            // 3. The batch belongs to another ingredient.
            confirm(home, token, UUID.randomUUID().toString(), confirmBody(recipe, 1, List.of(
                    allocation(stockC, 1, grams, "100", "G"),
                    allocation(stockC2, 1, count, "2", "COUNT"))))
                    .andExpect(status().isBadRequest());
            // 4. The batch expired before the household's today.
            confirm(home, token, UUID.randomUUID().toString(), confirmBody(recipe, 1, List.of(
                    allocation(stockExpired, 1, grams, "100", "G"),
                    allocation(stockC, 1, count, "2", "COUNT"))))
                    .andExpect(status().isConflict());
            // 5. A missing batch version is rejected as a malformed confirmation.
            confirm(home, token, UUID.randomUUID().toString(), confirmBody(recipe, 1, List.of(
                    Map.of("batchId", stockG.toString(), "ingredientId", grams.toString(),
                            "quantity", "100", "unit", "G"),
                    allocation(stockC, 1, count, "2", "COUNT"))))
                    .andExpect(status().isBadRequest());

            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM cooking_events WHERE household_id=?",
                    Integer.class, home));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE household_id=?",
                    Integer.class, home));
            assertQuantity(stockG, "200");
            assertQuantity(stockC, "10");
            assertQuantity(stockC2, "10");
            assertQuantity(stockExpired, "100");

            // 5. The valid confirmation still works and deducts exactly once per batch.
            confirm(home, token, UUID.randomUUID().toString(), confirmBody(recipe, 1, List.of(
                    allocation(stockG, 1, grams, "100", "G"),
                    allocation(stockC, 1, count, "2", "COUNT"))))
                    .andExpect(status().isCreated());
            assertQuantity(stockG, "100");
            assertQuantity(stockC, "8");
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM inventory_movements"
                    + " WHERE household_id=? AND reason='COOK'", Integer.class, home));

            // 6. A tracked line whose requirement is zero needs no allocation and consumes nothing.
            UUID zeroRecipe = UUID.randomUUID();
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,yield_servings,duration_minutes)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),?,?)",
                    zeroRecipe, home, user, "零用量菜", "[\"切\"]", 2, 20);
            jdbc.update("INSERT INTO recipe_ingredients(recipe_id,ingredient_id,position,quantity,unit,track_stock)"
                    + " VALUES (?,?,0,0,'G',true)", zeroRecipe, grams);
            confirm(home, token, UUID.randomUUID().toString(), confirmBody(zeroRecipe, 1, List.of()))
                    .andExpect(status().isCreated());
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM inventory_movements"
                    + " WHERE household_id=? AND reason='COOK'", Integer.class, home));
        } finally {
            jdbc.update("DELETE FROM inventory_movements WHERE household_id=?", home);
            jdbc.update("DELETE FROM cooking_events WHERE household_id=?", home);
            jdbc.update("DELETE FROM inventory_batches WHERE household_id=?", home);
            jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id IN (SELECT id FROM recipes WHERE household_id=?)", home);
            jdbc.update("DELETE FROM recipes WHERE household_id=?", home);
            jdbc.update("DELETE FROM memberships WHERE household_id=?", home);
            jdbc.update("DELETE FROM households WHERE id=?", home);
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
    }

    private void insertBatch(UUID id, UUID home, UUID ingredient, String quantity, String unit, LocalDate expiresOn) {
        jdbc.update("INSERT INTO inventory_batches(id,household_id,ingredient_id,quantity,unit,location,expires_on)"
                        + " VALUES (?,?,?,?,?,'冷藏',?)",
                id, home, ingredient, new java.math.BigDecimal(quantity), unit, expiresOn);
    }

    private void assertQuantity(UUID batch, String expected) {
        assertEquals(0, jdbc.queryForObject("SELECT quantity FROM inventory_batches WHERE id=?",
                        java.math.BigDecimal.class, batch).compareTo(new java.math.BigDecimal(expected)),
                "batch " + batch + " quantity changed");
    }

    private Map<String, Object> allocation(UUID batchId, long version, UUID ingredientId, String quantity, String unit) {
        return Map.of("batchId", batchId.toString(), "expectedVersion", version,
                "ingredientId", ingredientId.toString(), "quantity", quantity, "unit", unit);
    }

    private String confirmBody(UUID recipe, int recipeVersion, List<Map<String, Object>> allocations)
            throws Exception {
        return json.writeValueAsString(Map.of(
                "recipeId", recipe.toString(), "recipeVersion", recipeVersion, "servings", "2",
                "cookedAt", "2026-11-26T04:00:00.000Z", "allocations", allocations));
    }

    private org.springframework.test.web.servlet.ResultActions confirm(UUID home, String token, String key,
                                                                      String body) throws Exception {
        return http.perform(post("/api/v1/households/{id}/cooking-events", home)
                .header("Authorization", bearer(token)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String login(String name) throws Exception {
        String body = json.writeValueAsString(Map.of("username", name, "password", "long-password"));
        var result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
