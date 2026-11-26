package edu.xjtlu.civa.server.household;

import edu.xjtlu.civa.server.CivaServer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes=CivaServer.class)
@AutoConfigureMockMvc
class HouseholdLeaveIntegrationTest {
    @Autowired MockMvc http; @Autowired JdbcTemplate jdbc; @Autowired PasswordEncoder passwords; @Autowired ObjectMapper json;

    @Test void creatorCanLeaveAndOnlyFinalLeaveClosesIdentity() throws Exception {
        UUID owner=UUID.randomUUID(),member=UUID.randomUUID(),stranger=UUID.randomUUID(),home=UUID.randomUUID();
        UUID[] actors={owner,member,stranger};
        try {
            for(UUID user:actors) jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",user,"leave_"+user.toString().replace("-", "").substring(0,16),"Leave fixture",passwords.encode("fixture-password"),"UTC");
            jdbc.update("INSERT INTO households(id,name) VALUES (?,?)",home,"Leave fixture");
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'OWNER',true)",UUID.randomUUID(),home,owner);
            jdbc.update("INSERT INTO memberships(id,household_id,user_id,role,active) VALUES (?,?,?,'MEMBER',true)",UUID.randomUUID(),home,member);
            String ownerToken=login(owner),memberToken=login(member),strangerToken=login(stranger);
            String path="/api/v1/households/"+home+"/members/";
            http.perform(delete(path+owner).param("expectedVersion","1").header("Authorization",ownerToken)).andExpect(status().isNoContent());
            assertNull(jdbc.queryForObject("SELECT closed_at FROM households WHERE id=?",java.sql.Timestamp.class,home));
            http.perform(delete(path+member).param("expectedVersion","1").header("Authorization",ownerToken)).andExpect(status().isForbidden());
            http.perform(delete(path+member).param("expectedVersion","1").header("Authorization",strangerToken)).andExpect(status().isNotFound());
            http.perform(delete(path+member).param("expectedVersion","0").header("Authorization",memberToken)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
            http.perform(delete(path+member).param("expectedVersion","1").header("Authorization",memberToken)).andExpect(status().isNoContent());
            assertEquals(2L,jdbc.queryForObject("SELECT version FROM memberships WHERE household_id=? AND user_id=?",Long.class,home,member));
            http.perform(get("/api/v1/me").header("Authorization",memberToken)).andExpect(status().isOk()).andExpect(jsonPath("$.householdId").isEmpty());
            http.perform(get("/api/v1/households/"+home).header("Authorization",memberToken)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HOUSEHOLD_ACCESS_REVOKED"));
            http.perform(delete(path+member).param("expectedVersion","1").header("Authorization",memberToken)).andExpect(status().isNotFound());
            assertFalse(jdbc.queryForObject("SELECT active FROM memberships WHERE household_id=? AND user_id=?",Boolean.class,home,owner));
            assertNotNull(jdbc.queryForObject("SELECT closed_at FROM households WHERE id=?",java.sql.Timestamp.class,home));
            http.perform(get("/api/v1/households/"+home).header("Authorization",ownerToken)).andExpect(status().isForbidden());
            http.perform(post("/api/v1/households").header("Authorization",memberToken).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Next fixture home\"}")).andExpect(status().isCreated());
        } finally {
            // Random test-owned fixtures only; the guarded test database is separate from development.
            for(UUID user:actors) {
                jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?",user);
                jdbc.update("DELETE FROM sessions WHERE user_id=?",user);
            }
            var owned=jdbc.query("SELECT household_id FROM memberships WHERE user_id IN (?,?,?) ",(rs,n)->rs.getObject(1,UUID.class),owner,member,stranger);
            for(UUID id:owned)jdbc.update("DELETE FROM invitations WHERE household_id=?",id);
            for(UUID user:actors)jdbc.update("DELETE FROM memberships WHERE user_id=?",user);
            for(UUID id:owned)jdbc.update("DELETE FROM households WHERE id=?",id);
            for(UUID user:actors)jdbc.update("DELETE FROM users WHERE id=?",user);
        }
    }
    private String login(UUID user)throws Exception {
        var response=http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"leave_"+user.toString().replace("-", "").substring(0,16)+"\",\"password\":\"fixture-password\"}")).andExpect(status().isOk()).andReturn();
        return "Bearer "+json.readTree(response.getResponse().getContentAsString()).path("token").asText();
    }
}
