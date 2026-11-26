package edu.xjtlu.civa.server.household;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
import java.util.*;
import java.util.concurrent.*;
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

@SpringBootTest(classes=CivaServer.class) @AutoConfigureMockMvc
class HouseholdNameIntegrationTest {
    @Autowired MockMvc http;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired PasswordEncoder passwords;
    @Test void everyMemberCanRenameWithoutChangingFixedInvitationAndVersionRejectsStaleEdits()throws Exception {
        try(Fixture f=new Fixture()) {
            assertEquals(404,rename(f.tokens[2],f.home,1,"No access"));
            assertEquals(400,rename(f.tokens[0],f.home,1,"   "));
            assertEquals(400,rename(f.tokens[0],f.home,1,"x".repeat(121)));
            assertEquals(200,rename(f.tokens[1],f.home,1,"  小猫之家  "));
            var renamed=read(f.tokens[0],f.home);assertEquals("小猫之家",renamed.path("name").asText());assertEquals(2,renamed.path("version").asLong());
            assertEquals(409,rename(f.tokens[0],f.home,1,"Stale overwrite"));
            assertEquals("小猫之家",read(f.tokens[1],f.home).path("name").asText());
            assertEquals(200,rename(f.tokens[0],f.home,2,"小猫之家")); // no-op does not increment version
            assertEquals(2,read(f.tokens[0],f.home).path("version").asLong());
            assertEquals(f.code,code(f.tokens[1],f.home));
            assertEquals(204,http.perform(delete("/api/v1/households/"+f.home+"/members/"+f.users[1]).header("Authorization",f.tokens[1]).param("expectedVersion","1")).andReturn().getResponse().getStatus());
            assertEquals(403,rename(f.tokens[1],f.home,2,"Revoked"));
            assertEquals("小猫之家",read(f.tokens[0],f.home).path("name").asText());
        }
    }
    @Test void simultaneousEditsAtSameVersionHaveExactlyOneWinner()throws Exception {
        try(Fixture f=new Fixture()) {
            var pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            try {
                var a=pool.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return rename(f.tokens[0],f.home,1,"First draft");});
                var b=pool.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return rename(f.tokens[1],f.home,1,"Second draft");});
                assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();assertEquals(Set.of(200,409),Set.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS)));
                var actual=read(f.tokens[0],f.home);assertEquals(2,actual.path("version").asLong());assertTrue(Set.of("First draft","Second draft").contains(actual.path("name").asText()));
                assertEquals(f.code,code(f.tokens[0],f.home));
            }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));}
        }
    }
    private int rename(String token,UUID home,long version,String name)throws Exception {
        return http.perform(put("/api/v1/households/"+home).header("Authorization",token).param("expectedVersion",Long.toString(version)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name",name)))).andReturn().getResponse().getStatus();
    }
    private com.fasterxml.jackson.databind.JsonNode read(String token,UUID home)throws Exception {
        var r=http.perform(get("/api/v1/households/"+home).header("Authorization",token)).andReturn().getResponse();assertEquals(200,r.getStatus());return json.readTree(r.getContentAsString());
    }
    private String code(String token,UUID home)throws Exception {
        var r=http.perform(get("/api/v1/households/"+home+"/invitations").header("Authorization",token)).andReturn().getResponse();assertEquals(200,r.getStatus());return json.readTree(r.getContentAsString()).path("code").asText();
    }
    private final class Fixture implements AutoCloseable {
        final UUID[] users={UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID()};final String[] tokens=new String[3];UUID home;String code;
        Fixture()throws Exception {
            try {
                for(int i=0;i<3;i++) {
                    String username="rename_"+users[i].toString().replace("-", "").substring(0,18);
                    jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",users[i],username,"Name fixture",passwords.encode("fixture-password"),"UTC");
                    var login=http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",username,"password","fixture-password")))).andReturn().getResponse();assertEquals(200,login.getStatus());tokens[i]="Bearer "+json.readTree(login.getContentAsString()).path("token").asText();
                }
                var created=http.perform(post("/api/v1/households").header("Authorization",tokens[0]).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Original family\"}")).andReturn().getResponse();assertEquals(201,created.getStatus());home=UUID.fromString(json.readTree(created.getContentAsString()).path("id").asText());
                code=code(tokens[0],home);
                assertEquals(201,http.perform(post("/api/v1/households/join").header("Authorization",tokens[1]).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code",code)))).andReturn().getResponse().getStatus());
            }catch(Exception|AssertionError error){close();throw error;}
        }
        public void close(){
            for(UUID user:users){jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?",user);jdbc.update("DELETE FROM sessions WHERE user_id=?",user);}
            if(home!=null)jdbc.update("DELETE FROM invitations WHERE household_id=?",home);
            for(UUID user:users)jdbc.update("DELETE FROM memberships WHERE user_id=?",user);
            if(home!=null)jdbc.update("DELETE FROM households WHERE id=?",home);
            for(UUID user:users)jdbc.update("DELETE FROM users WHERE id=?",user);
        }
    }
}
