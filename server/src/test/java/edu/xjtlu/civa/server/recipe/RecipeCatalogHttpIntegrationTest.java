package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seeded catalogue is deterministic (1307 merged rows), so the suite pins the totals the
 * client relies on: unfiltered paging, category/tag/high-protein filters, search, detail and 404.
 */
@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class RecipeCatalogHttpIntegrationTest {
    private static final String API = "/api/v1/recipe-catalog";
    private static final long TOTAL = 1307;

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void catalogueIsReadOnlyPaginatedFilterableAndPublicToSignedInUsers() throws Exception {
        UUID user = UUID.randomUUID();
        try {
            http.perform(get(API)).andExpect(status().isUnauthorized());
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, username(user), "Test", passwords.encode("long-password"), "UTC");
            String token = login(user);

            http.perform(get(API).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(TOTAL))
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(20))
                    .andExpect(jsonPath("$.items.length()").value(20))
                    .andExpect(jsonPath("$.items[0].name").isNotEmpty())
                    .andExpect(jsonPath("$.items[0].source").exists());

            http.perform(get(API).header("Authorization", bearer(token)).param("page", "1").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(1))
                    .andExpect(jsonPath("$.items.length()").value(10));

            http.perform(get(API).header("Authorization", bearer(token)).param("category", "家常菜"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(900));

            http.perform(get(API).header("Authorization", bearer(token)).param("tag", "辣"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(194));

            http.perform(get(API).header("Authorization", bearer(token)).param("highProtein", "true")
                            .param("size", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(342))
                    .andExpect(jsonPath("$.items[0].proteinPer100g").exists());

            http.perform(get(API).header("Authorization", bearer(token)).param("q", "咖喱"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(8)); // name/ingredient/step matches

            String listBody = http.perform(get(API).header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String firstId = json.readTree(listBody).path("items").get(0).path("id").asText();
            http.perform(get(API + "/{id}", firstId).header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(firstId))
                    .andExpect(jsonPath("$.steps").isArray())
                    .andExpect(jsonPath("$.ingredients").isArray());

            http.perform(get(API + "/{id}", UUID.randomUUID()).header("Authorization", bearer(token)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));

            http.perform(get(API).header("Authorization", bearer(token)).param("size", "101"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            http.perform(get(API + "/{id}", firstId)).andExpect(status().isUnauthorized());
        } finally {
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
