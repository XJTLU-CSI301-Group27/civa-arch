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
import org.springframework.test.web.servlet.MvcResult;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class PersonalRecipeHttpIntegrationTest {
    private static final String API = "/api/v1/me/recipes";

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void personalRecipesAreUserScopedVersionedIdempotentAndDeletable() throws Exception {
        UUID user = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, username(user), "Test", passwords.encode("long-password"), "UTC");
            String token = login(user);
            String key = UUID.randomUUID().toString();
            String body = json.writeValueAsString(Map.of(
                    "name", "我的番茄蛋", "category", "家常菜", "steps", List.of("切块", "合炒"),
                    "ingredients", List.of(Map.of("name", "番茄", "quantity", "2", "unit", "个"),
                            Map.of("name", "鸡蛋", "quantity", "3", "unit", "个"))));

            MvcResult created = http.perform(post(API).header("Authorization", bearer(token))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn();
            assertEquals(201, created.getResponse().getStatus(),
                    "create failed: " + created.getResponse().getContentAsString()
                            + " exception=" + created.getResolvedException());
            String id = json.readTree(created.getResponse().getContentAsString()).path("id").asText();

            http.perform(post(API).header("Authorization", bearer(token))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(id));

            http.perform(get(API).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.items[0].id").value(id));
            http.perform(get(API + "/{id}", id).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.steps[0]").value("切块"));

            String update = json.writeValueAsString(Map.of("name", "改名番茄蛋", "steps", List.of("切块", "合炒"),
                    "ingredients", List.of(Map.of("name", "番茄", "quantity", "2", "unit", "个")),
                    "expectedVersion", 1));
            http.perform(put(API + "/{id}", id).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(update))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("改名番茄蛋"))
                    .andExpect(jsonPath("$.version").value(2));

            http.perform(put(API + "/{id}", id).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(update))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

            http.perform(put(API + "/{id}/archive", id).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("archived", true, "expectedVersion", 2))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true));

            http.perform(delete(API + "/{id}", id).header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());
            http.perform(get(API + "/{id}", id).header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound());

            http.perform(post(API).header("Authorization", bearer(token))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"  \",\"steps\":[],\"ingredients\":[]}"))
                    .andExpect(status().isBadRequest());
            http.perform(get(API)).andExpect(status().isUnauthorized());
        } finally {
            jdbc.update("DELETE FROM recipe_favorites WHERE user_id=?", user);
            jdbc.update("DELETE FROM user_recipes WHERE user_id=?", user);
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
    }

    private String login(UUID id) throws Exception {
        String body = json.writeValueAsString(Map.of("username", username(id), "password", "long-password"));
        var result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String username(UUID id) { return "u_" + id.toString().replace("-", "").substring(0, 20); }
    private static String bearer(String token) { return "Bearer " + token; }
}
