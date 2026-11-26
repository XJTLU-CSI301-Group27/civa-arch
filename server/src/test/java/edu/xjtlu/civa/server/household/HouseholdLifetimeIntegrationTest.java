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
class HouseholdLifetimeIntegrationTest {
    @Autowired MockMvc http;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper json;@Autowired PasswordEncoder passwords;
    @Test void joinRacingFinalLeaveNeverRevivesAClosedFamily()throws Exception { race(false); }
    @Test void simultaneousLastTwoLeavesCloseTheFamily()throws Exception { race(true); }
    private void race(boolean bothLeave)throws Exception {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),home=null;var pool=Executors.newFixedThreadPool(2);
        try {
            for(UUID user:List.of(a,b))jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",user,name(user),"Race fixture",passwords.encode("fixture-password"),"UTC");
            String at=login(a),bt=login(b);
            var created=http.perform(post("/api/v1/households").header("Authorization",at).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Race fixture\"}")).andReturn().getResponse();assertEquals(201,created.getStatus());
            home=UUID.fromString(json.readTree(created.getContentAsString()).path("id").asText());UUID id=home;
            var invite=http.perform(get("/api/v1/households/"+home+"/invitations").header("Authorization",at)).andReturn().getResponse();assertEquals(200,invite.getStatus());
            String code=json.readTree(invite.getContentAsString()).path("code").asText();
            if(bothLeave)assertEquals(201,join(bt,code));
            CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            Future<Integer> left=pool.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return leave(at,id,a);});
            Future<Integer> other=pool.submit(()->{ready.countDown();assertTrue(start.await(10,TimeUnit.SECONDS));return bothLeave?leave(bt,id,b):join(bt,code);});
            assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();
            assertEquals(204,left.get(15,TimeUnit.SECONDS));int outcome=other.get(15,TimeUnit.SECONDS);
            if(bothLeave){assertEquals(204,outcome);}else{assertTrue(outcome==201||outcome==400,"Join either precedes closure or is rejected");}
            long active=jdbc.queryForObject("SELECT count(*) FROM memberships WHERE household_id=? AND active",Long.class,home);
            var closed=jdbc.queryForObject("SELECT closed_at FROM households WHERE id=?",java.sql.Timestamp.class,home);
            if(!bothLeave&&outcome==201){assertEquals(1,active);assertNull(closed);assertEquals(204,leave(bt,home,b));}
            else{assertEquals(0,active);assertNotNull(closed);}
            assertNotNull(jdbc.queryForObject("SELECT closed_at FROM households WHERE id=?",java.sql.Timestamp.class,home));
            assertEquals(400,join(at,code));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM memberships m JOIN households h ON h.id=m.household_id WHERE h.id=? AND h.closed_at IS NOT NULL AND m.active",Integer.class,home));
        }finally {
            pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));
            for(UUID u:List.of(a,b)){jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?",u);jdbc.update("DELETE FROM sessions WHERE user_id=?",u);}
            if(home!=null)jdbc.update("DELETE FROM invitations WHERE household_id=?",home);
            for(UUID u:List.of(a,b))jdbc.update("DELETE FROM memberships WHERE user_id=?",u);
            if(home!=null)jdbc.update("DELETE FROM households WHERE id=?",home);
            for(UUID u:List.of(a,b))jdbc.update("DELETE FROM users WHERE id=?",u);
        }
    }
    private int leave(String token,UUID home,UUID user)throws Exception{return http.perform(delete("/api/v1/households/"+home+"/members/"+user).header("Authorization",token).param("expectedVersion","1")).andReturn().getResponse().getStatus();}
    private int join(String token,String code)throws Exception{return http.perform(post("/api/v1/households/join").header("Authorization",token).header("Idempotency-Key",UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("code",code)))).andReturn().getResponse().getStatus();}
    private String login(UUID user)throws Exception{var r=http.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username",name(user),"password","fixture-password")))).andReturn().getResponse();assertEquals(200,r.getStatus());return "Bearer "+json.readTree(r.getContentAsString()).path("token").asText();}
    private static String name(UUID id){return "race_"+id.toString().replace("-", "").substring(0,18);}
}
