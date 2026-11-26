package edu.xjtlu.civa.server.profile;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class PreferencesHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void preferencesStartEmptyAndAreVersioned() throws Exception {
        UUID user = UUID.randomUUID();
        try {
            String name = "u_" + user.toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Pref", passwords.encode("long-password"), "UTC");
            String token = login(name);
            UUID ingredient = jdbc.queryForObject("SELECT id FROM ingredients ORDER BY id LIMIT 1", UUID.class);

            http.perform(get("/api/v1/me/preferences").header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.likedTags").isEmpty());

            String update = json.writeValueAsString(Map.of("likedTags", List.of("清淡"),
                    "excludedIngredientIds", List.of(ingredient.toString()), "expectedVersion", 0));
            var first = http.perform(put("/api/v1/me/preferences").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(update))
                    .andReturn();
            org.junit.jupiter.api.Assertions.assertEquals(200, first.getResponse().getStatus(),
                    "update failed: " + first.getResponse().getContentAsString());

            http.perform(put("/api/v1/me/preferences").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(update))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

            String invalid = json.writeValueAsString(Map.of("likedTags", List.of(" "),
                    "excludedIngredientIds", List.of(), "expectedVersion", 1));
            http.perform(put("/api/v1/me/preferences").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());

            http.perform(get("/api/v1/me/preferences")).andExpect(status().isUnauthorized());
        } finally {
            jdbc.update("DELETE FROM preferences WHERE user_id=?", user);
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
