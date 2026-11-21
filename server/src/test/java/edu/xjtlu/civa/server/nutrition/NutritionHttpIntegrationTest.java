package edu.xjtlu.civa.server.nutrition;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint is exercised with the deterministic stub provider, so CI needs no provider key:
 * the first request fills the cache, the repeat is a cache hit, further uncached dishes hit the
 * rate limit, and invalid or unauthenticated requests keep their documented error codes.
 */
@SpringBootTest(classes = CivaServer.class, properties = {
        "civa.nutrition.provider=stub",
        "civa.nutrition.hourly-limit=2",
        "civa.nutrition.daily-limit=5"
})
@AutoConfigureMockMvc
class NutritionHttpIntegrationTest {
    private static final String API = "/api/v1/nutrition/estimates";

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void estimatesAreCachedRateLimitedAndValidated() throws Exception {
        UUID user = UUID.randomUUID();
        List<String> hashes = new ArrayList<>();
        try {
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, username(user), "Test", passwords.encode("long-password"), "UTC");
            String token = login(user);
            String suffix = user.toString().substring(0, 8);

            var first = http.perform(post(API).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(
                                    body("番茄炒蛋 " + suffix, List.of("番茄 2 个", "鸡蛋 3 个"), List.of("切块", "合炒"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.basis").value("PER_100G"))
                    .andExpect(jsonPath("$.cached").value(false))
                    .andExpect(jsonPath("$.providerVersion").value("stub:v1"))
                    .andExpect(jsonPath("$.tips").isNotEmpty())
                    .andExpect(jsonPath("$.inputHash").isNotEmpty())
                    .andReturn();
            String hash = json.readTree(first.getResponse().getContentAsString()).path("inputHash").asText();
            hashes.add(hash);
            assertEquals(64, hash.length());
            assertEquals(hash, hash.matches("[0-9a-f]{64}") ? hash : "not-hex");

            http.perform(post(API).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(
                                    body("番茄炒蛋 " + suffix, List.of("番茄 2 个", "鸡蛋 3 个"), List.of("切块", "合炒"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cached").value(true))
                    .andExpect(jsonPath("$.inputHash").value(hash));

            var second = http.perform(post(API).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(
                                    body("青椒肉丝 " + suffix, List.of("青椒 2 个", "猪肉 100g"), List.of("切丝", "快炒"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cached").value(false))
                    .andReturn();
            hashes.add(json.readTree(second.getResponse().getContentAsString()).path("inputHash").asText());

            http.perform(post(API).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(
                                    body("土豆丝 " + suffix, List.of("土豆 2 个"), List.of("切丝", "快炒"))))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

            http.perform(post(API).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \",\"ingredients\":[]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

            http.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(
                            body("番茄炒蛋 " + suffix, List.of("番茄 2 个"), List.of("合炒"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        } finally {
            for (String hash : hashes) jdbc.update("DELETE FROM nutrition_estimates WHERE input_hash=?", hash);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
            jdbc.update("DELETE FROM users WHERE id=?", user);
        }
    }

    private String body(String name, List<String> ingredients, List<String> steps) throws Exception {
        return json.writeValueAsString(Map.of("name", name, "ingredients", ingredients, "steps", steps));
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
