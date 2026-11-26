package edu.xjtlu.civa.server.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class PlanAuthorHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void planSlotsExposeTheMemberWhoLastEditedThem() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        UUID recipe = UUID.randomUUID();
        String date = LocalDate.now().plusDays(3).toString();
        try {
            insertUser(first, "Chef One");
            insertUser(second, "Chef Two");
            jdbc.update("INSERT INTO households(id,name,zone_id) VALUES (?,?,'UTC')", home, "Author home");
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'MEMBER',true)",
                    UUID.randomUUID(), home, first);
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'MEMBER',true)",
                    UUID.randomUUID(), home, second);
            jdbc.update("INSERT INTO recipes(id,household_id,author_id,name,steps,duration_minutes,yield_servings)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),10,2)",
                    recipe, home, first, "Author dish", "[\"cook\"]");
            String firstToken = login(username(first));
            String secondToken = login(username(second));

            http.perform(put("/api/v1/households/{id}/plan-slots/{date}/{meal}", home, date, "LUNCH")
                            .header("Authorization", bearer(firstToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("items",
                                    List.of(Map.of("recipeId", recipe.toString(), "servings", "1")),
                                    "expectedVersion", 0))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastEditorId").value(first.toString()))
                    .andExpect(jsonPath("$.lastEditorName").value("Chef One"))
                    .andExpect(jsonPath("$.version").value(1));

            http.perform(put("/api/v1/households/{id}/plan-slots/{date}/{meal}", home, date, "LUNCH")
                            .header("Authorization", bearer(secondToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("items",
                                    List.of(Map.of("recipeId", recipe.toString(), "servings", "2")),
                                    "expectedVersion", 1))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lastEditorId").value(second.toString()))
                    .andExpect(jsonPath("$.lastEditorName").value("Chef Two"))
                    .andExpect(jsonPath("$.version").value(2));
        } finally {
            jdbc.update("DELETE FROM plan_items WHERE household_id=?", home);
            jdbc.update("DELETE FROM plan_slots WHERE household_id=?", home);
            jdbc.update("DELETE FROM recipes WHERE household_id=?", home);
            jdbc.update("DELETE FROM sessions WHERE user_id IN (?,?)", first, second);
            jdbc.update("DELETE FROM memberships WHERE household_id=?", home);
            jdbc.update("DELETE FROM households WHERE id=?", home);
            jdbc.update("DELETE FROM users WHERE id IN (?,?)", first, second);
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
