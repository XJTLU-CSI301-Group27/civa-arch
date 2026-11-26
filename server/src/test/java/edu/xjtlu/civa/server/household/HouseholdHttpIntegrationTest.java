package edu.xjtlu.civa.server.household;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=CivaServer.class) @AutoConfigureMockMvc
class HouseholdHttpIntegrationTest {
    @Autowired MockMvc http;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired PasswordEncoder passwords;
    @Test void fixedInvitationIsAutomaticReusableAndEqualForEveryMember()throws Exception {
        UUID creator=UUID.randomUUID(),member=UUID.randomUUID(),other=UUID.randomUUID();UUID[] users={creator,member,other};UUID home=null;
        try {
            for(UUID user:users)jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",user,name(user),"Shared fixture",passwords.encode("fixture-password"),"UTC");
            String a=login(creator),b=login(member),c=login(other);UUID key=UUID.randomUUID();
            String created=http.perform(post("/api/v1/households").header("Authorization",a).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" Shared home \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Shared home")).andReturn().getResponse().getContentAsString();
            home=UUID.fromString(json.readTree(created).path("id").asText());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM invitations WHERE household_id=? AND permanent",Integer.class,home));
            assertEquals("MEMBER",jdbc.queryForObject("SELECT role FROM memberships WHERE household_id=? AND user_id=? AND active",String.class,home,creator));
            http.perform(post("/api/v1/households").header("Authorization",a).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Shared home\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(home.toString()));
            http.perform(post("/api/v1/households").header("Authorization",a).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Other\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
            http.perform(post("/api/v1/households").header("Authorization",a).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\"}"))
                .andExpect(status().isBadRequest());
            http.perform(post("/api/v1/households").header("Authorization",a).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"name\":\"Y\"}"))
                .andExpect(status().isBadRequest());
            String path="/api/v1/households/"+home+"/invitations";
            String invite=code(a,path);
            assertEquals(26,invite.length());assertEquals(invite,code(a,path));
            http.perform(get(path).header("Authorization",c)).andExpect(status().isNotFound());
            UUID invitationKey=UUID.randomUUID();
            http.perform(post(path).header("Authorization",a).header("Idempotency-Key",invitationKey)).andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(invite)).andExpect(jsonPath("$.expiresAt").isEmpty());
            http.perform(post(path).header("Authorization",a).header("Idempotency-Key",invitationKey)).andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(invite));
            String receipt=jdbc.queryForObject("SELECT response_body::text FROM idempotency_requests WHERE actor_id=? AND key=?",String.class,creator,invitationKey);
            assertFalse(receipt.contains(invite));assertTrue(receipt.contains("sealed-v1:"));
            String sealed=jdbc.queryForObject("SELECT sealed_code FROM invitations WHERE household_id=? AND permanent",String.class,home);assertFalse(sealed.contains(invite));
            UUID joinKey=UUID.randomUUID();join(b," "+invite.toLowerCase(Locale.ROOT)+" ",joinKey,home);
            join(b,invite,joinKey,home);
            assertEquals(invite,code(b,path));
            http.perform(post(path).header("Authorization",b).header("Idempotency-Key",UUID.randomUUID())).andExpect(status().isCreated()).andExpect(jsonPath("$.code").value(invite));
            join(c,invite,UUID.randomUUID(),home);
            http.perform(get("/api/v1/households/"+home+"/members").header("Authorization",b)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM memberships WHERE household_id=? AND active AND role<>'MEMBER'",Integer.class,home));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM invitations WHERE household_id=? AND permanent AND used_at IS NULL AND expires_at IS NULL",Integer.class,home));
            http.perform(delete("/api/v1/households/"+home+"/members/"+member).header("Authorization",a).param("expectedVersion","1")).andExpect(status().isForbidden());
            jdbc.update("UPDATE memberships SET active=false WHERE household_id=? AND user_id=?",home,member);
            http.perform(get(path).header("Authorization",b)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOUSEHOLD_ACCESS_REVOKED"));
            http.perform(post("/api/v1/households/join").header("Authorization",b).header("Idempotency-Key",joinKey).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code",invite))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOUSEHOLD_ACCESS_REVOKED"));
            // The owner cannot leave while another member remains.
            http.perform(delete("/api/v1/households/"+home+"/members/"+creator).header("Authorization",a).param("expectedVersion","1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_MUST_TRANSFER"));
            // A non-owner member may still leave; the owner keeps the household and the invitation.
            http.perform(delete("/api/v1/households/"+home+"/members/"+other).header("Authorization",c).param("expectedVersion","1")).andExpect(status().isNoContent());
            assertNull(jdbc.queryForObject("SELECT closed_at FROM households WHERE id=?",java.sql.Timestamp.class,home));
            assertEquals(invite,code(a,path));
            // A sole owner must dissolve instead of leaving.
            http.perform(delete("/api/v1/households/"+home+"/members/"+creator).header("Authorization",a).param("expectedVersion","1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OWNER_MUST_DISSOLVE"));
            http.perform(post("/api/v1/households/"+home+"/dissolve").header("Authorization",a).header("Idempotency-Key",UUID.randomUUID()))
                .andExpect(status().isNoContent());
            assertNotNull(jdbc.queryForObject("SELECT closed_at FROM households WHERE id=?",java.sql.Timestamp.class,home));
            assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM memberships WHERE household_id=? AND NOT active",Integer.class,home));
            http.perform(get(path).header("Authorization",a)).andExpect(status().isNotFound());
            http.perform(post("/api/v1/households/join").header("Authorization",a).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code",invite))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
            http.perform(get("/api/v1/households/"+home).header("Authorization",a)).andExpect(status().isForbidden());
        }finally {
            for(UUID user:users){jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?",user);jdbc.update("DELETE FROM sessions WHERE user_id=?",user);}
            if(home!=null)jdbc.update("DELETE FROM invitations WHERE household_id=?",home);
            for(UUID user:users)jdbc.update("DELETE FROM memberships WHERE user_id=?",user);
            if(home!=null)jdbc.update("DELETE FROM households WHERE id=?",home);
            for(UUID user:users)jdbc.update("DELETE FROM users WHERE id=?",user);
        }
    }
    private void join(String token,String code,UUID key,UUID home)throws Exception {
        http.perform(post("/api/v1/households/join").header("Authorization",token).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code",code))))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.householdId").value(home.toString()));
    }
    private String code(String token,String path)throws Exception {
        String body=http.perform(get(path).header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").isEmpty()).andReturn().getResponse().getContentAsString();
        assertTrue(json.readTree(body).has("expiresAt"));return json.readTree(body).path("code").asText();
    }
    private String login(UUID user)throws Exception {
        String body=http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",name(user),"password","fixture-password"))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();return "Bearer "+json.readTree(body).path("token").asText();
    }
    private static String name(UUID id){return "shared_"+id.toString().replace("-", "").substring(0,18);}
}
