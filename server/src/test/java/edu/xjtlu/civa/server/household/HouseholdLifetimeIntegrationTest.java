package edu.xjtlu.civa.server.household;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class HouseholdLifetimeIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;

    /** Dissolving the sole-owner household races a join; exactly one side may win. */
    @Test void dissolveRacingJoinNeverLeavesAnInconsistentFamily() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();
        UUID home = null;
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (UUID user : List.of(owner, joiner)) {
                jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                        user, name(user), "Race fixture", passwords.encode("fixture-password"), "UTC");
            }
            String ownerToken = login(owner);
            String joinerToken = login(joiner);
            home = create(ownerToken);
            String code = invite(ownerToken, home);
            UUID id = home;

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> dissolving = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return dissolve(ownerToken, id);
            });
            Future<Integer> joining = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return join(joinerToken, code);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int dissolveStatus = dissolving.get(15, TimeUnit.SECONDS);
            int joinStatus = joining.get(15, TimeUnit.SECONDS);

            assertTrue((dissolveStatus == 204 && joinStatus == 400)
                            || (joinStatus == 201 && dissolveStatus == 409),
                    "exactly one side must win: dissolve=" + dissolveStatus + " join=" + joinStatus);
            long active = jdbc.queryForObject(
                    "SELECT count(*) FROM memberships WHERE household_id=? AND active", Long.class, home);
            var closed = jdbc.queryForObject(
                    "SELECT closed_at FROM households WHERE id=?", java.sql.Timestamp.class, home);
            UUID currentOwner = jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home);
            if (dissolveStatus == 204) {
                assertEquals(0, active);
                assertNotNull(closed);
                assertNull(currentOwner);
                assertEquals(400, join(joinerToken, code), "a closed family cannot be revived by the old code");
            } else {
                assertEquals(2, active);
                assertNull(closed);
                assertEquals(owner, currentOwner);
                assertEquals(409, dissolve(ownerToken, home), "a family with members cannot be dissolved");
            }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM memberships m JOIN households h"
                    + " ON h.id=m.household_id WHERE h.id=? AND h.closed_at IS NOT NULL AND m.active",
                    Integer.class, home));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            cleanup(home, List.of(owner, joiner));
        }
    }

    /** A non-owner member leaving races a new join; the owner stays and the family stays open. */
    @Test void memberLeaveRacingJoinKeepsTheOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();
        UUID home = null;
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (UUID user : List.of(owner, member, joiner)) {
                jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                        user, name(user), "Race fixture", passwords.encode("fixture-password"), "UTC");
            }
            String ownerToken = login(owner);
            String memberToken = login(member);
            String joinerToken = login(joiner);
            home = create(ownerToken);
            String code = invite(ownerToken, home);
            assertEquals(201, join(memberToken, code));
            long memberVersion = jdbc.queryForObject("SELECT version FROM memberships"
                    + " WHERE household_id=? AND user_id=? AND active", Long.class, home, member);
            UUID id = home;

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> leaving = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return leave(memberToken, id, member, memberVersion);
            });
            Future<Integer> joining = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return join(joinerToken, code);
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(204, leaving.get(15, TimeUnit.SECONDS));
            assertEquals(201, joining.get(15, TimeUnit.SECONDS));

            assertEquals(owner, jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home));
            assertNull(jdbc.queryForObject("SELECT closed_at FROM households WHERE id=?",
                    java.sql.Timestamp.class, home));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM memberships WHERE household_id=? AND active", Integer.class, home));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships"
                    + " WHERE household_id=? AND user_id=? AND NOT active", Integer.class, home, member));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            cleanup(home, List.of(owner, member, joiner));
        }
    }

    /** The owner can only leave after a transfer; a sole owner must dissolve. */
    @Test void ownerCannotLeaveMultiMemberHouseholdAndSoleOwnerDissolves() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID home = null;
        try {
            for (UUID user : List.of(owner, member)) {
                jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                        user, name(user), "Leave fixture", passwords.encode("fixture-password"), "UTC");
            }
            String ownerToken = login(owner);
            String memberToken = login(member);
            home = create(ownerToken);
            String code = invite(ownerToken, home);
            assertEquals(201, join(memberToken, code));
            long ownerVersion = jdbc.queryForObject("SELECT version FROM memberships"
                    + " WHERE household_id=? AND user_id=? AND active", Long.class, home, owner);
            long memberVersion = jdbc.queryForObject("SELECT version FROM memberships"
                    + " WHERE household_id=? AND user_id=? AND active", Long.class, home, member);

            assertEquals(409, leave(ownerToken, home, owner, ownerVersion));
            assertEquals("OWNER_MUST_TRANSFER", failureCode(ownerToken, home, owner, ownerVersion));
            assertEquals(204, leave(memberToken, home, member, memberVersion));
            assertEquals(409, leave(ownerToken, home, owner, ownerVersion));
            assertEquals("OWNER_MUST_DISSOLVE", failureCode(ownerToken, home, owner, ownerVersion));

            assertEquals(204, dissolve(ownerToken, home));
            assertNotNull(jdbc.queryForObject("SELECT closed_at FROM households WHERE id=?",
                    java.sql.Timestamp.class, home));
            assertNull(jdbc.queryForObject("SELECT owner_user_id FROM households WHERE id=?", UUID.class, home));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM memberships WHERE household_id=? AND active", Integer.class, home));
            assertEquals(400, join(memberToken, code));
        } finally {
            cleanup(home, List.of(owner, member));
        }
    }

    private UUID create(String token) throws Exception {
        var response = http.perform(post("/api/v1/households")
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lifetime fixture\"}"))
                .andReturn().getResponse();
        assertEquals(201, response.getStatus());
        return UUID.fromString(json.readTree(response.getContentAsString()).path("id").asText());
    }

    private String invite(String token, UUID home) throws Exception {
        var response = http.perform(get("/api/v1/households/" + home + "/invitations")
                        .header("Authorization", token))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        return json.readTree(response.getContentAsString()).path("code").asText();
    }

    private int join(String token, String code) throws Exception {
        return http.perform(post("/api/v1/households/join")
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", code))))
                .andReturn().getResponse().getStatus();
    }

    private int leave(String token, UUID home, UUID user, long version) throws Exception {
        return http.perform(delete("/api/v1/households/" + home + "/members/" + user)
                        .header("Authorization", token)
                        .param("expectedVersion", Long.toString(version)))
                .andReturn().getResponse().getStatus();
    }

    private String failureCode(String token, UUID home, UUID user, long version) throws Exception {
        var body = http.perform(delete("/api/v1/households/" + home + "/members/" + user)
                        .header("Authorization", token)
                        .param("expectedVersion", Long.toString(version)))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("code").asText();
    }

    private int dissolve(String token, UUID home) throws Exception {
        return http.perform(post("/api/v1/households/" + home + "/dissolve")
                        .header("Authorization", token)
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andReturn().getResponse().getStatus();
    }

    private void cleanup(UUID home, List<UUID> users) {
        if (home != null) jdbc.update("DELETE FROM ownership_transfer_requests WHERE household_id=?", home);
        for (UUID user : users) {
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
        }
        if (home != null) jdbc.update("DELETE FROM invitations WHERE household_id=?", home);
        for (UUID user : users) jdbc.update("DELETE FROM memberships WHERE user_id=?", user);
        if (home != null) jdbc.update("DELETE FROM households WHERE id=?", home);
        for (UUID user : users) jdbc.update("DELETE FROM users WHERE id=?", user);
    }

    private String login(UUID user) throws Exception {
        var response = http.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name(user), "password", "fixture-password"))))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        return "Bearer " + json.readTree(response.getContentAsString()).path("token").asText();
    }

    private static String name(UUID id) {
        return "race_" + id.toString().replace("-", "").substring(0, 18);
    }
}
