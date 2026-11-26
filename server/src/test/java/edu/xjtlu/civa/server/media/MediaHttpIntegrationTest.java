package edu.xjtlu.civa.server.media;

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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class MediaHttpIntegrationTest {
    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3, 4};

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;

    @Test void uploadsHouseholdAndPersonalImagesWithOwnerOnlyReads() throws Exception {
        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        try {
            String name = "u_" + user.toString().replace("-", "").substring(0, 20);
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    user, name, "Media", passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                    other, "u_" + other.toString().replace("-", "").substring(0, 20), "Other",
                    passwords.encode("long-password"), "UTC");
            jdbc.update("INSERT INTO households(id,name,zone_id) VALUES (?,?,'UTC')", home, "Media home");
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",
                    UUID.randomUUID(), home, user);
            String token = login(name);
            String otherToken = login("u_" + other.toString().replace("-", "").substring(0, 20));

            var uploaded = http.perform(multipart("/api/v1/media")
                            .file(new MockMultipartFile("file", "cat.png", "image/png", PNG))
                            .param("scope", "HOUSEHOLD").param("householdId", home.toString())
                            .header("Authorization", bearer(token)).header("Idempotency-Key", UUID.randomUUID().toString()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.scope").value("HOUSEHOLD"))
                    .andExpect(jsonPath("$.mimeType").value("image/png"))
                    .andExpect(jsonPath("$.sizeBytes").value(PNG.length))
                    .andReturn();
            String id = json.readTree(uploaded.getResponse().getContentAsString()).path("id").asText();

            var content = http.perform(get("/api/v1/media/{id}/content", id)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk()).andReturn();
            assertArrayEquals(PNG, content.getResponse().getContentAsByteArray());
            assertEquals("image/png", content.getResponse().getContentType());
            http.perform(get("/api/v1/media/{id}/content", id).header("Authorization", bearer(otherToken)))
                    .andExpect(status().isNotFound());
            http.perform(get("/api/v1/media/{id}/content", id)).andExpect(status().isUnauthorized());

            var personal = http.perform(multipart("/api/v1/media")
                            .file(new MockMultipartFile("file", "cat.jpg", "image/jpeg", PNG))
                            .param("scope", "PERSONAL")
                            .header("Authorization", bearer(token)).header("Idempotency-Key", UUID.randomUUID().toString()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.scope").value("PERSONAL"))
                    .andReturn();
            String personalId = json.readTree(personal.getResponse().getContentAsString()).path("id").asText();
            http.perform(get("/api/v1/media/{id}/content", personalId).header("Authorization", bearer(otherToken)))
                    .andExpect(status().isNotFound());

            http.perform(multipart("/api/v1/media")
                            .file(new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes()))
                            .param("scope", "PERSONAL")
                            .header("Authorization", bearer(token)).header("Idempotency-Key", UUID.randomUUID().toString()))
                    .andExpect(status().isBadRequest());
        } finally {
            jdbc.update("DELETE FROM media WHERE owner_user_id IN (?,?)", user, other);
            jdbc.update("DELETE FROM memberships WHERE household_id=?", home);
            jdbc.update("DELETE FROM households WHERE id=?", home);
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id IN (?,?)", user, other);
            jdbc.update("DELETE FROM sessions WHERE user_id IN (?,?)", user, other);
            jdbc.update("DELETE FROM users WHERE id IN (?,?)", user, other);
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
