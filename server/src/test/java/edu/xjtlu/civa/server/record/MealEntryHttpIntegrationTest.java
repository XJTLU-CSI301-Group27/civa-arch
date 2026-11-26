package edu.xjtlu.civa.server.record;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class MealEntryHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void mealEntriesAreUserScopedIdempotentSummaryAndVersioned() throws Exception {
        UUID user = UUID.randomUUID();
        try {
            String name = "u_" + user.toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Test", passwords.encode("long-password"), "Asia/Shanghai");
            String token = login(name);
            String key = UUID.randomUUID().toString();
            String body = json.writeValueAsString(Map.of(
                    "date", "2026-11-24", "mealType", "LUNCH", "name", "番茄炒蛋",
                    "amount", "1.5", "basis", "SERVING", "basisKcal", "200", "note", "好吃"));

            var created = http.perform(post("/api/v1/me/meal-entries").header("Authorization", bearer(token))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.name").value("番茄炒蛋"))
                    .andExpect(jsonPath("$.totalKcal").value("300"))
                    .andExpect(jsonPath("$.zoneId").value("Asia/Shanghai"))
                    .andExpect(jsonPath("$.version").value(1))
                    .andReturn();
            String id = json.readTree(created.getResponse().getContentAsString()).path("id").asText();

            http.perform(post("/api/v1/me/meal-entries").header("Authorization", bearer(token))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));

            http.perform(post("/api/v1/me/meal-entries").header("Authorization", bearer(token))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "date", "2026-11-24", "mealType", "DINNER", "name", "外食",
                                    "amount", "1", "basis", "UNKNOWN", "note", ""))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.totalKcal").doesNotExist());

            http.perform(get("/api/v1/me/meal-entries?from=2026-11-01&to=2026-11-30")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.items[0].date").value("2026-11-24"));
            http.perform(get("/api/v1/me/meal-summary?from=2026-11-01&to=2026-11-30")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.knownKcal").value("300"))
                    .andExpect(jsonPath("$.unknownCount").value(1))
                    .andExpect(jsonPath("$.days[0].date").value("2026-11-24"))
                    .andExpect(jsonPath("$.days[0].entryCount").value(2));

            http.perform(post("/api/v1/me/meal-entries").header("Authorization", bearer(token))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "date", "2026-11-24", "mealType", "BREAKFAST", "name", "称重",
                                    "amount", "200", "basis", "GRAM", "basisKcal", "100", "note", ""))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.totalKcal").value("200"));
            http.perform(post("/api/v1/me/meal-entries").header("Authorization", bearer(token))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "date", "2026-11-24", "mealType", "SNACK", "name", "未知",
                                    "amount", "1", "basis", "UNKNOWN", "basisKcal", "100", "note", ""))))
                    .andExpect(status().isBadRequest());
            http.perform(post("/api/v1/me/meal-entries").header("Authorization", bearer(token))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of(
                                    "date", "2026-11-24", "mealType", "SNACK", "name", "手填",
                                    "amount", "2", "basis", "MANUAL", "basisKcal", "100", "note", ""))))
                    .andExpect(status().isBadRequest());

            String update = json.writeValueAsString(Map.of(
                    "date", "2026-11-25", "mealType", "LUNCH", "name", "番茄炒蛋",
                    "amount", "2", "basis", "SERVING", "basisKcal", "200", "note", "改量",
                    "expectedVersion", 1));
            http.perform(put("/api/v1/me/meal-entries/{id}", id).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(update))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalKcal").value("400"))
                    .andExpect(jsonPath("$.version").value(2));
            http.perform(put("/api/v1/me/meal-entries/{id}", id).header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content(update))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

            http.perform(delete("/api/v1/me/meal-entries/{id}", id).param("expectedVersion", "2")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isNoContent());
            http.perform(get("/api/v1/me/meal-entries?from=2026-11-01&to=2026-11-30")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));

            http.perform(get("/api/v1/me/meal-entries?from=2026-11-01&to=2026-11-30"))
                    .andExpect(status().isUnauthorized());
            http.perform(get("/api/v1/me/meal-entries?from=2026-12-01&to=2026-11-01")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isBadRequest());
        } finally {
            jdbc.update("DELETE FROM meal_entries WHERE user_id=?", user);
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
