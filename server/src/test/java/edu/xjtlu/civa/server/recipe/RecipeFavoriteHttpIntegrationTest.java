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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Favourites cover the catalogue, personal recipes and household recipes. */
@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class RecipeFavoriteHttpIntegrationTest {
    private static final String API = "/api/v1/me/recipe-favorites";

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void favouritesSpanTheThreeRecipeSpaces() throws Exception {
        UUID user = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID householdRecipe = UUID.randomUUID();
        try {
            String name = username(user);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Test", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO households(id,name) VALUES (?,?)", home, "Test home");
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                    UUID.randomUUID(), home, user);
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,yield_servings,duration_minutes)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),2,30)",
                    householdRecipe, home, user, "家庭红烧肉", "[\"切块\",\"炖煮\"]");
            String token = login(user, name);

            String catalogId = jdbc.queryForObject("SELECT id::text FROM recipe_catalog ORDER BY name LIMIT 1", String.class);
            http.perform(put(API + "/CATALOG/{id}", catalogId).header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());
            http.perform(get(API + "/CATALOG/{id}", catalogId).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("CATALOG"))
                    .andExpect(jsonPath("$.name").isNotEmpty());

            String personalBody = json.writeValueAsString(Map.of("name", "私人小炒",
                    "steps", List.of("备料", "快炒"),
                    "ingredients", List.of(Map.of("name", "青椒", "quantity", "2", "unit", "个"))));
            var personal = http.perform(post("/api/v1/me/recipes").header("Authorization", bearer(token))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content(personalBody))
                    .andExpect(status().isCreated()).andReturn();
            String personalId = json.readTree(personal.getResponse().getContentAsString()).path("id").asText();
            http.perform(put(API + "/PERSONAL/{id}", personalId).header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            http.perform(put(API + "/HOUSEHOLD/{id}", householdRecipe).header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());

            http.perform(get(API).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(3))
                    .andExpect(jsonPath("$.items.length()").value(3));
            http.perform(get(API).param("kind", "PERSONAL").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].name").value("私人小炒"));
            http.perform(get(API).param("kind", "HOUSEHOLD").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].name").value("家庭红烧肉"));

            http.perform(put(API + "/CATALOG/{id}", catalogId).header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());
            http.perform(delete(API + "/CATALOG/{id}", catalogId).header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());
            http.perform(get(API + "/CATALOG/{id}", catalogId).header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound());
            http.perform(put(API + "/CATALOG/{id}", UUID.randomUUID()).header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            http.perform(get(API)).andExpect(status().isUnauthorized());
        } finally {
            jdbc.update("DELETE FROM recipe_favorites WHERE user_id=?", user);
            jdbc.update("DELETE FROM user_recipes WHERE user_id=?", user);
            jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id=?", householdRecipe);
            jdbc.update("DELETE FROM recipes WHERE id=?", householdRecipe);
            jdbc.update("DELETE FROM memberships WHERE household_id=?", home);
            jdbc.update("DELETE FROM households WHERE id=?", home);
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
    }

    private String login(UUID id, String name) throws Exception {
        String body = json.writeValueAsString(Map.of("username", name, "password", "long-password"));
        var result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String username(UUID id) { return "u_" + id.toString().replace("-", "").substring(0, 20); }
    private static String bearer(String token) { return "Bearer " + token; }
}
