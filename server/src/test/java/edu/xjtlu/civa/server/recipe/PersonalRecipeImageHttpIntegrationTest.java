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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class PersonalRecipeImageHttpIntegrationTest {
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 7, 8, 9};

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void personalRecipeKeepsAnAccessibleImageAndRejectsForeignOnes() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        try {
            insertUser(owner, "Image Owner");
            insertUser(other, "Image Other");
            String ownerToken = login(username(owner));
            String otherToken = login(username(other));

            var uploaded = http.perform(multipart("/api/v1/media")
                            .file(new MockMultipartFile("file", "dish.png", "image/png", PNG))
                            .param("scope", "PERSONAL")
                            .header("Authorization", bearer(ownerToken))
                            .header("Idempotency-Key", UUID.randomUUID().toString()))
                    .andExpect(status().isCreated()).andReturn();
            String imageId = json.readTree(uploaded.getResponse().getContentAsString()).path("id").asText();

            String body = json.writeValueAsString(Map.of(
                    "name", "Photo dish", "steps", List.of("cook"),
                    "ingredients", List.of(Map.of("name", "beef", "quantity", "1", "unit", "COUNT")),
                    "imageId", imageId));
            String recipeKey = UUID.randomUUID().toString();
            http.perform(post("/api/v1/me/recipes").header("Authorization", bearer(ownerToken))
                            .header("Idempotency-Key", recipeKey)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.imageId").value(imageId));

            String foreignKey = UUID.randomUUID().toString();
            http.perform(post("/api/v1/me/recipes").header("Authorization", bearer(otherToken))
                            .header("Idempotency-Key", foreignKey)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IMAGE"));
        } finally {
            jdbc.update("DELETE FROM user_recipes WHERE user_id IN (?,?)", owner, other);
            jdbc.update("DELETE FROM media WHERE owner_user_id IN (?,?)", owner, other);
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id IN (?,?)", owner, other);
            jdbc.update("DELETE FROM sessions WHERE user_id IN (?,?)", owner, other);
            jdbc.update("DELETE FROM users WHERE id IN (?,?)", owner, other);
        }
    }

    private void insertUser(UUID id, String displayName) {
        jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                id, username(id), displayName, passwords.encode("long-password"), "UTC");
    }

    private String login(String name) throws Exception {
        var result = http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", "long-password"))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private static String username(UUID id) {
        return "u_" + id.toString().replace("-", "").substring(0, 20);
    }

    private static String bearer(String token) { return "Bearer " + token; }
}
