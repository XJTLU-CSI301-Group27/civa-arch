package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class HouseholdRecipeOnlyHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void recipeOnlyIngredientsRoundTripAndSnapshot() throws Exception {
        Session session = seed("OnlySnap");
        try {
            ObjectNode body = recipeBody("有辅料的番茄蛋", List.of(tracked(session.ingredient)),
                    List.of(only("食盐", "适量", null), only("酱油", "2 勺", "生抽"),
                            only("香菜", null, "根据口味")), 20, null);
            JsonNode created = create(session, body, 201);
            String recipeId = created.path("id").asText();

            assertEquals(1, created.path("ingredients").size());
            assertEquals(3, created.path("recipeOnlyIngredients").size());
            assertEquals("食盐", created.path("recipeOnlyIngredients").get(0).path("name").asText());
            assertEquals("适量", created.path("recipeOnlyIngredients").get(0).path("amountText").asText());
            assertEquals("酱油", created.path("recipeOnlyIngredients").get(1).path("name").asText());
            assertEquals("2 勺", created.path("recipeOnlyIngredients").get(1).path("amountText").asText());
            assertEquals("香菜", created.path("recipeOnlyIngredients").get(2).path("name").asText());
            assertTrue(created.path("recipeOnlyIngredients").get(2).path("amountText").isNull());

            http.perform(get("/api/v1/households/{id}/recipes/{r}", session.home, recipeId)
                            .header("Authorization", bearer(session.token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recipeOnlyIngredients[1].amountText").value("2 勺"));
            http.perform(get("/api/v1/households/{id}/recipes", session.home)
                            .header("Authorization", bearer(session.token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].recipeOnlyIngredients.length()").value(3));

            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM recipe_ingredients WHERE recipe_id=?",
                    Integer.class, UUID.fromString(recipeId)));
            assertEquals(3, jdbc.queryForObject(
                    "SELECT jsonb_array_length(recipe_only_ingredients) FROM recipes WHERE id=?",
                    Integer.class, UUID.fromString(recipeId)));
            String snapshot = jdbc.queryForObject("SELECT ingredients::text FROM user_recipes WHERE user_id=?",
                    String.class, session.user);
            assertTrue(snapshot.contains("食盐") && snapshot.contains("适量"));
            assertTrue(snapshot.contains("酱油") && snapshot.contains("2 勺"));
            assertTrue(snapshot.contains("香菜"));
        } finally {
            cleanup(session);
        }
    }

    @Test void updatePreservesNullAndReplacesEmptyRecipeOnlyArray() throws Exception {
        Session session = seed("OnlyUpdate");
        try {
            ObjectNode create = recipeBody("更新辅料", List.of(tracked(session.ingredient)),
                    List.of(only("食盐", "适量", null), only("酱油", "2 勺", null)), 20, null);
            JsonNode created = create(session, create, 201);
            UUID recipeId = UUID.fromString(created.path("id").asText());
            long version = created.path("version").asLong();

            // Old clients omit the field entirely: preserve descriptive rows.
            ObjectNode absent = updateBody("更新辅料", List.of(tracked(session.ingredient)),
                    null, null, 25, version);
            JsonNode afterAbsent = update(session, recipeId, absent, 200);
            assertEquals(2, afterAbsent.path("recipeOnlyIngredients").size());
            version = afterAbsent.path("version").asLong();

            // Explicit null means preserve.
            ObjectNode explicitNull = updateBody("更新辅料", List.of(tracked(session.ingredient)),
                    null, true, 26, version);
            JsonNode afterNull = update(session, recipeId, explicitNull, 200);
            assertEquals(2, afterNull.path("recipeOnlyIngredients").size());
            version = afterNull.path("version").asLong();

            // Empty array clears all descriptive rows.
            ObjectNode clear = updateBody("更新辅料", List.of(tracked(session.ingredient)),
                    List.of(), false, 27, version);
            JsonNode afterClear = update(session, recipeId, clear, 200);
            assertEquals(0, afterClear.path("recipeOnlyIngredients").size());
            version = afterClear.path("version").asLong();

            // Non-empty array replaces and preserves order.
            ObjectNode replace = updateBody("更新辅料", List.of(tracked(session.ingredient)),
                    List.of(only("香菜", "一把", null), only("胡椒", "少许", null)), false, 28, version);
            JsonNode afterReplace = update(session, recipeId, replace, 200);
            assertEquals("香菜", afterReplace.path("recipeOnlyIngredients").get(0).path("name").asText());
            assertEquals("胡椒", afterReplace.path("recipeOnlyIngredients").get(1).path("name").asText());
        } finally {
            cleanup(session);
        }
    }

    @Test void recipeWithOnlyDescriptiveRowsHasNoStockRequirement() throws Exception {
        Session session = seed("OnlyNoStock");
        try {
            ObjectNode body = recipeBody("只有辅料的茶", List.of(),
                    List.of(only("食盐", "适量", null)), 10, null);
            JsonNode created = create(session, body, 201);
            String recipeId = created.path("id").asText();
            assertEquals(0, created.path("ingredients").size());
            assertEquals(1, created.path("recipeOnlyIngredients").size());

            String matchBody = json.writeValueAsString(Map.of(
                    "entries", List.of(Map.of("recipeId", recipeId, "servings", "1"))));
            http.perform(post("/api/v1/households/{id}/recipe-matches", session.home)
                            .header("Authorization", bearer(session.token))
                            .contentType(MediaType.APPLICATION_JSON).content(matchBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].lines").isEmpty())
                    .andExpect(jsonPath("$[0].coverage").value("1"));

            String previewBody = json.writeValueAsString(Map.of("recipeId", recipeId, "servings", "1"));
            http.perform(post("/api/v1/households/{id}/cooking-previews", session.home)
                            .header("Authorization", bearer(session.token))
                            .contentType(MediaType.APPLICATION_JSON).content(previewBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allocations").isEmpty())
                    .andExpect(jsonPath("$.shortages").isEmpty());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM inventory_movements WHERE household_id=?",
                    Integer.class, session.home));
        } finally {
            cleanup(session);
        }
    }

    @Test void recipeOnlyValidationOwnershipAndVersionConflict() throws Exception {
        Session session = seed("OnlyGuard");
        Session stranger = seed("OnlyStranger");
        try {
            ObjectNode body = recipeBody("校验", List.of(tracked(session.ingredient)),
                    List.of(only("食盐", "适量", null)), 20, null);
            JsonNode created = create(session, body, 201);
            UUID recipeId = UUID.fromString(created.path("id").asText());
            long version = created.path("version").asLong();

            ObjectNode blank = recipeBody("校验", List.of(tracked(session.ingredient)),
                    List.of(only(" ", "适量", null)), 20, null);
            create(session, blank, 400);

            ObjectNode tooLong = recipeBody("校验", List.of(tracked(session.ingredient)),
                    List.of(only("盐", "x".repeat(201), null)), 20, null);
            create(session, tooLong, 400);

            ObjectNode unknown = recipeBody("校验", List.of(tracked(session.ingredient)),
                    List.of(only("盐", "适量", null)), 20, null);
            ((ObjectNode) unknown.path("recipeOnlyIngredients").get(0)).put("unknown", true);
            create(session, unknown, 400);

            http.perform(get("/api/v1/households/{id}/recipes/{r}", session.home, recipeId)
                            .header("Authorization", bearer(stranger.token)))
                    .andExpect(status().isNotFound());
            ObjectNode foreignUpdate = updateBody("校验", List.of(tracked(session.ingredient)),
                    List.of(), false, 21, version);
            http.perform(put("/api/v1/households/{id}/recipes/{r}", stranger.home, recipeId)
                            .header("Authorization", bearer(stranger.token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(foreignUpdate)))
                    .andExpect(status().isNotFound());

            ObjectNode conflict = updateBody("校验", List.of(tracked(session.ingredient)),
                    List.of(), false, 21, version + 5);
            update(session, recipeId, conflict, 409);
        } finally {
            cleanup(session);
            cleanup(stranger);
        }
    }

    @Test void failedReplacementDoesNotPartiallyChangeRecipeOnlyRows() throws Exception {
        Session session = seed("OnlyAtomic");
        try {
            ObjectNode body = recipeBody("原子性", List.of(tracked(session.ingredient)),
                    List.of(only("食盐", "适量", null)), 20, null);
            JsonNode created = create(session, body, 201);
            UUID recipeId = UUID.fromString(created.path("id").asText());
            long version = created.path("version").asLong();

            ObjectNode duplicate = updateBody("原子性", List.of(tracked(session.ingredient),
                            tracked(session.ingredient)), List.of(only("酱油", "2 勺", null)), false, 21, version);
            update(session, recipeId, duplicate, 500);

            http.perform(get("/api/v1/households/{id}/recipes/{r}", session.home, recipeId)
                            .header("Authorization", bearer(session.token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recipeOnlyIngredients.length()").value(1))
                    .andExpect(jsonPath("$.recipeOnlyIngredients[0].name").value("食盐"));
        } finally {
            cleanup(session);
        }
    }

    @Test void historicalFalseRowIsPreservedAndIgnoredByMatching() throws Exception {
        Session session = seed("OnlyLegacy");
        try {
            String second = jdbc.queryForObject(
                    "SELECT id::text FROM ingredients WHERE unit='G' ORDER BY name OFFSET 1 LIMIT 1", String.class);
            ObjectNode body = recipeBody("旧数据", List.of(tracked(session.ingredient),
                    Map.of("ingredientId", second, "quantity", "3", "unit", "G", "trackStock", false)),
                    List.of(only("食盐", "适量", null)), 20, null);
            JsonNode created = create(session, body, 201);
            assertEquals(2, created.path("ingredients").size());
            assertEquals(false, created.path("ingredients").get(1).path("trackStock").asBoolean());
            String recipeId = created.path("id").asText();

            String matchBody = json.writeValueAsString(Map.of(
                    "entries", List.of(Map.of("recipeId", recipeId, "servings", "1"))));
            http.perform(post("/api/v1/households/{id}/recipe-matches", session.home)
                            .header("Authorization", bearer(session.token))
                            .contentType(MediaType.APPLICATION_JSON).content(matchBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].lines.length()").value(1));
        } finally {
            cleanup(session);
        }
    }

    @Test void failedCreateLeavesNoRecipeOrSnapshot() throws Exception {
        Session session = seed("OnlyCreateFail");
        try {
            ObjectNode duplicate = recipeBody("创建失败", List.of(tracked(session.ingredient),
                    tracked(session.ingredient)), List.of(only("食盐", "适量", null)), 20, null);
            create(session, duplicate, 500);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recipes WHERE household_id=?",
                    Integer.class, session.home));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM user_recipes WHERE user_id=?",
                    Integer.class, session.user));
        } finally {
            cleanup(session);
        }
    }

    private JsonNode create(Session session, ObjectNode body, int status) throws Exception {
        var result = http.perform(post("/api/v1/households/{id}/recipes", session.home)
                        .header("Authorization", bearer(session.token))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(status)).andReturn();
        return status == 201 ? json.readTree(result.getResponse().getContentAsString()) : null;
    }

    private JsonNode update(Session session, UUID recipeId, ObjectNode body, int status) throws Exception {
        var result = http.perform(put("/api/v1/households/{id}/recipes/{r}", session.home, recipeId)
                        .header("Authorization", bearer(session.token))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().is(status)).andReturn();
        return status == 200 ? json.readTree(result.getResponse().getContentAsString()) : null;
    }

    private ObjectNode recipeBody(String name, List<Map<String, Object>> ingredients,
                                  List<Map<String, Object>> recipeOnly, int minutes, Long expectedVersion) {
        ObjectNode body = baseBody(name, ingredients, recipeOnly, minutes, expectedVersion);
        return body;
    }

    private ObjectNode updateBody(String name, List<Map<String, Object>> ingredients,
                                  List<Map<String, Object>> recipeOnly, Boolean explicitNull,
                                  int minutes, long expectedVersion) {
        ObjectNode body = baseBody(name, ingredients, null, minutes, expectedVersion);
        if (Boolean.TRUE.equals(explicitNull)) body.putNull("recipeOnlyIngredients");
        else if (recipeOnly != null) body.set("recipeOnlyIngredients", json.valueToTree(recipeOnly));
        return body;
    }

    private ObjectNode baseBody(String name, List<Map<String, Object>> ingredients,
                                List<Map<String, Object>> recipeOnly, int minutes, Long expectedVersion) {
        ObjectNode body = json.createObjectNode();
        body.put("name", name);
        body.set("steps", json.valueToTree(List.of("做")));
        body.set("ingredients", json.valueToTree(ingredients));
        body.put("yieldServings", "2");
        body.put("durationMinutes", minutes);
        body.set("tags", json.valueToTree(List.of("家常菜")));
        if (recipeOnly != null) body.set("recipeOnlyIngredients", json.valueToTree(recipeOnly));
        if (expectedVersion != null) body.put("expectedVersion", expectedVersion);
        return body;
    }

    private Map<String, Object> tracked(String ingredientId) {
        return Map.of("ingredientId", ingredientId, "quantity", "2", "unit", "G", "trackStock", true);
    }

    private Map<String, Object> only(String name, String amount, String note) {
        java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("name", name);
        value.put("amountText", amount);
        value.put("note", note);
        return value;
    }

    private Session seed(String prefix) throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        String username = "u_" + user.toString().replace("-", "").substring(0, 18);
        jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                user, username, prefix, passwords.encode("long-password"), "UTC");
        jdbc.update("INSERT INTO households(id,name,owner_user_id,owner_source) VALUES (?,?,?,'CREATED')",
                home, prefix + " home", user);
        jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                UUID.randomUUID(), home, user);
        String ingredient = jdbc.queryForObject(
                "SELECT id::text FROM ingredients WHERE unit='G' ORDER BY name LIMIT 1", String.class);
        return new Session(user, home, ingredient, login(username));
    }

    private String login(String name) throws Exception {
        var result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", "long-password"))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private void cleanup(Session session) {
        jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id IN (SELECT id FROM recipes WHERE household_id=?)", session.home);
        jdbc.update("DELETE FROM recipes WHERE household_id=?", session.home);
        jdbc.update("DELETE FROM user_recipes WHERE user_id=?", session.user);
        jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", session.user);
        jdbc.update("DELETE FROM memberships WHERE household_id=?", session.home);
        jdbc.update("DELETE FROM households WHERE id=?", session.home);
        jdbc.update("DELETE FROM sessions WHERE user_id=?", session.user);
        jdbc.update("DELETE FROM users WHERE id=?", session.user);
    }

    private record Session(UUID user, UUID home, String ingredient, String token) { }
}
