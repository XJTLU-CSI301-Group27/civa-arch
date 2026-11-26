package edu.xjtlu.civa.server.profile;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class ProfileContractHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void displayNameIsUpdatedOnServerAndVisibleToHouseholdMembers() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        try {
            insertUser(first, "First One");
            insertUser(second, "Second One");
            jdbc.update("INSERT INTO households(id,name,zone_id,owner_user_id,owner_source) VALUES (?,?,'UTC',?,'CREATED')", home, "Name home", first);
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'MEMBER',true)",
                    UUID.randomUUID(), home, first);
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'MEMBER',true)",
                    UUID.randomUUID(), home, second);
            String token = login(username(first));

            http.perform(put("/api/v1/me/profile").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("displayName", "小笼包"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.displayName").value("小笼包"));
            http.perform(get("/api/v1/me").header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("小笼包"));
            http.perform(put("/api/v1/me/profile").header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("displayName", "   "))))
                    .andExpect(status().isBadRequest());

            var members = http.perform(get("/api/v1/households/{id}/members", home)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andReturn();
            JsonNode page = json.readTree(members.getResponse().getContentAsString());
            String firstId = first.toString();
            String secondId = second.toString();
            boolean sawFirst = false;
            boolean sawSecond = false;
            for (JsonNode member : page.path("items")) {
                if (firstId.equals(member.path("userId").asText())) {
                    sawFirst = "小笼包".equals(member.path("displayName").asText())
                            && username(first).equals(member.path("username").asText());
                }
                if (secondId.equals(member.path("userId").asText())) {
                    sawSecond = "Second One".equals(member.path("displayName").asText());
                }
            }
            assertTrue(sawFirst, "first member should expose the updated display name and username");
            assertTrue(sawSecond, "second member should expose its display name");
        } finally {
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
