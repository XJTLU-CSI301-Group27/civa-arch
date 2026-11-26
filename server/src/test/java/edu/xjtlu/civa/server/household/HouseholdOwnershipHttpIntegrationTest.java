package edu.xjtlu.civa.server.household;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.CivaServer;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest(classes = CivaServer.class)
@AutoConfigureMockMvc
class HouseholdOwnershipHttpIntegrationTest {
    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;

    @Test void creationAssignsTheCreatorAsOwner() throws Exception {
        UUID owner = user("own");
        String token = login(owner);
        UUID home = create(token);
        try {
            JsonNode body = jsonOf(call(get("/api/v1/households/" + home).header("Authorization", token)));
            assertEquals(owner.toString(), body.path("ownerUserId").asText());
            assertEquals("CREATED", body.path("ownerSource").asText());
            assertEquals(owner, jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home));
            assertNotNull(jdbc.queryForObject(
                    "SELECT owner_confirmed_at FROM households WHERE id=?", java.sql.Timestamp.class, home));
        } finally {
            cleanup(home, owner);
        }
    }

    @Test void onlyTheOwnerCanRequestAndOnlyTheTargetCanAcceptOrReject() throws Exception {
        UUID owner = user("own");
        UUID target = user("tgt");
        UUID third = user("thd");
        UUID outsider = user("out");
        String ownerToken = login(owner);
        String targetToken = login(target);
        String thirdToken = login(third);
        UUID home = create(ownerToken);
        try {
            assertEquals(201, join(targetToken, invite(ownerToken, home)));
            assertEquals(201, join(thirdToken, invite(ownerToken, home)));

            assertEquals("NOT_HOUSEHOLD_OWNER",
                    failureCode(requestRaw(targetToken, home, third), 403));
            assertEquals("INVALID_TRANSFER_TARGET",
                    failureCode(requestRaw(ownerToken, home, owner), 400));
            assertEquals("INVALID_TRANSFER_TARGET",
                    failureCode(requestRaw(ownerToken, home, outsider), 400));

            MvcResult created = requestRaw(ownerToken, home, target);
            assertEquals(201, status(created));
            UUID requestId = UUID.fromString(jsonOf(created).path("id").asText());
            JsonNode request = jsonOf(created);
            assertEquals("PENDING", request.path("status").asText());
            assertEquals(owner.toString(), request.path("requestedBy").asText());
            assertEquals(target.toString(), request.path("targetUserId").asText());
            assertEquals(owner, jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home),
                    "requesting must not change the owner");

            assertEquals("TRANSFER_ALREADY_PENDING", failureCode(requestRaw(ownerToken, home, third), 409));
            assertEquals("NOT_TRANSFER_TARGET", failureCode(acceptRaw(ownerToken, home, requestId, UUID.randomUUID()), 403));
            assertEquals("NOT_TRANSFER_TARGET", failureCode(acceptRaw(thirdToken, home, requestId, UUID.randomUUID()), 403));
            assertEquals("NOT_HOUSEHOLD_OWNER", failureCode(cancelRaw(thirdToken, home, requestId, UUID.randomUUID()), 403));

            JsonNode rejected = jsonOf(rejectRaw(targetToken, home, requestId));
            assertEquals("REJECTED", rejected.path("status").asText());
            assertNotNull(rejected.path("resolvedAt").asText(null));
            assertEquals(owner, jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home));
            assertEquals("TRANSFER_NOT_PENDING", failureCode(acceptRaw(targetToken, home, requestId, UUID.randomUUID()), 409));
            assertEquals("TRANSFER_NOT_PENDING", failureCode(cancelRaw(ownerToken, home, requestId, UUID.randomUUID()), 409));
        } finally {
            cleanup(home, owner, target, third, outsider);
        }
    }

    @Test void acceptingTransfersOwnershipOnceAndImmediately() throws Exception {
        UUID owner = user("own");
        UUID target = user("tgt");
        String ownerToken = login(owner);
        String targetToken = login(target);
        UUID home = create(ownerToken);
        try {
            assertEquals(201, join(targetToken, invite(ownerToken, home)));
            long version = householdVersion(home);
            UUID requestId = requestIdOf(requestRaw(ownerToken, home, target));

            UUID acceptKey = UUID.randomUUID();
            JsonNode accepted = jsonOf(acceptRaw(targetToken, home, requestId, acceptKey));
            assertEquals(target.toString(), accepted.path("ownerUserId").asText());
            assertEquals("TRANSFER", accepted.path("ownerSource").asText());
            assertEquals(version + 1, accepted.path("version").asLong());
            assertEquals("ACCEPTED", jdbc.queryForObject(
                    "SELECT status FROM ownership_transfer_requests WHERE id=?", String.class, requestId));

            // The old owner keeps membership but loses every owner-only capability immediately.
            assertEquals("NOT_HOUSEHOLD_OWNER", failureCode(requestRaw(ownerToken, home, owner), 403));
            assertEquals(target, jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home));
            // As an ordinary member they may now leave without a transfer.
            assertEquals(204, leave(ownerToken, home, owner, membershipVersion(home, owner)));

            // Replaying the same successful intent returns the stored result and never transfers twice.
            assertEquals(200, status(acceptRaw(targetToken, home, requestId, acceptKey)));
            assertEquals(200, status(acceptRaw(targetToken, home, requestId, acceptKey)));
            assertEquals(target, jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home));
            assertEquals(version + 1, householdVersion(home), "replay must not transfer again");
            assertEquals("TRANSFER_NOT_PENDING", failureCode(
                    acceptRaw(targetToken, home, requestId, UUID.randomUUID()), 409));
        } finally {
            cleanup(home, owner, target);
        }
    }

    @Test void cancelKeepsTheOwnerAndBlocksLaterAcceptance() throws Exception {
        UUID owner = user("own");
        UUID target = user("tgt");
        String ownerToken = login(owner);
        String targetToken = login(target);
        UUID home = create(ownerToken);
        try {
            assertEquals(201, join(targetToken, invite(ownerToken, home)));
            UUID requestId = requestIdOf(requestRaw(ownerToken, home, target));

            JsonNode cancelled = jsonOf(cancelRaw(ownerToken, home, requestId, UUID.randomUUID()));
            assertEquals("CANCELLED", cancelled.path("status").asText());
            assertEquals(owner, jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home));
            assertEquals("TRANSFER_NOT_PENDING", failureCode(
                    acceptRaw(targetToken, home, requestId, UUID.randomUUID()), 409));
        } finally {
            cleanup(home, owner, target);
        }
    }

    @Test void requestDetailsAreVisibleOnlyToTheOwnerAndTarget() throws Exception {
        UUID owner = user("own");
        UUID target = user("tgt");
        UUID third = user("thd");
        UUID outsider = user("out");
        String ownerToken = login(owner);
        String targetToken = login(target);
        String thirdToken = login(third);
        String outsiderToken = login(outsider);
        UUID home = create(ownerToken);
        try {
            assertEquals(201, join(targetToken, invite(ownerToken, home)));
            assertEquals(201, join(thirdToken, invite(ownerToken, home)));
            requestIdOf(requestRaw(ownerToken, home, target));

            assertEquals(1, listTotal(ownerToken, home));
            assertEquals(1, listTotal(targetToken, home));
            assertEquals(0, listTotal(thirdToken, home), "other members must not see request details");
            assertEquals(404, status(call(get("/api/v1/households/" + home + "/owner-transfer-requests")
                    .header("Authorization", outsiderToken))));
        } finally {
            cleanup(home, owner, target, third, outsider);
        }
    }

    @Test void targetLeavingCancelsThePendingRequestAndKeepsTheOwner() throws Exception {
        UUID owner = user("own");
        UUID target = user("tgt");
        String ownerToken = login(owner);
        String targetToken = login(target);
        UUID home = create(ownerToken);
        try {
            assertEquals(201, join(targetToken, invite(ownerToken, home)));
            UUID requestId = requestIdOf(requestRaw(ownerToken, home, target));
            long version = membershipVersion(home, target);

            assertEquals(204, leave(targetToken, home, target, version));
            assertEquals("CANCELLED", jdbc.queryForObject(
                    "SELECT status FROM ownership_transfer_requests WHERE id=?", String.class, requestId));
            assertEquals(target, jdbc.queryForObject(
                    "SELECT resolved_by FROM ownership_transfer_requests WHERE id=?", UUID.class, requestId));
            assertEquals(owner, jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home));
        } finally {
            cleanup(home, owner, target);
        }
    }

    @Test void soloOwnerMustDissolveAndDissolveKeepsHistory() throws Exception {
        UUID owner = user("own");
        UUID other = user("oth");
        String ownerToken = login(owner);
        String otherToken = login(other);
        UUID home = create(ownerToken);
        try {
            String code = invite(ownerToken, home);
            assertEquals(201, join(otherToken, code));
            assertEquals("NOT_HOUSEHOLD_OWNER", failureCode(dissolveRaw(otherToken, home), 403));
            assertEquals("HOUSEHOLD_HAS_MEMBERS", failureCode(dissolveRaw(ownerToken, home), 409));
            assertEquals(204, leave(otherToken, home, other, membershipVersion(home, other)));
            assertEquals("OWNER_MUST_DISSOLVE", failureCode(leaveRaw(ownerToken, home, owner,
                    membershipVersion(home, owner)), 409));

            assertEquals(204, status(dissolveRaw(ownerToken, home)));
            assertNotNull(jdbc.queryForObject("SELECT closed_at FROM households WHERE id=?",
                    java.sql.Timestamp.class, home));
            assertNull(jdbc.queryForObject("SELECT owner_user_id FROM households WHERE id=?", UUID.class, home));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM memberships WHERE household_id=? AND active", Integer.class, home));
            assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM memberships WHERE household_id=? AND NOT active", Integer.class, home));
            assertEquals(400, join(otherToken, code), "a closed family cannot be revived by the old code");
        } finally {
            cleanup(home, owner, other);
        }
    }

    @Test void acceptingRacingCancelLeavesExactlyOneWinner() throws Exception {
        UUID owner = user("own");
        UUID target = user("tgt");
        String ownerToken = login(owner);
        String targetToken = login(target);
        UUID home = create(ownerToken);
        var pool = Executors.newFixedThreadPool(2);
        try {
            assertEquals(201, join(targetToken, invite(ownerToken, home)));
            UUID requestId = requestIdOf(requestRaw(ownerToken, home, target));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> accepting = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return status(acceptRaw(targetToken, home, requestId, UUID.randomUUID()));
            });
            Future<Integer> cancelling = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return status(cancelRaw(ownerToken, home, requestId, UUID.randomUUID()));
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int acceptStatus = accepting.get(15, TimeUnit.SECONDS);
            int cancelStatus = cancelling.get(15, TimeUnit.SECONDS);

            boolean accepted = acceptStatus == 200 && cancelStatus == 409;
            boolean cancelled = cancelStatus == 200 && acceptStatus == 409;
            assertTrue(accepted || cancelled,
                    "exactly one side must win: accept=" + acceptStatus + " cancel=" + cancelStatus);
            String status = jdbc.queryForObject(
                    "SELECT status FROM ownership_transfer_requests WHERE id=?", String.class, requestId);
            UUID currentOwner = jdbc.queryForObject(
                    "SELECT owner_user_id FROM households WHERE id=?", UUID.class, home);
            if (accepted) {
                assertEquals("ACCEPTED", status);
                assertEquals(target, currentOwner);
            } else {
                assertEquals("CANCELLED", status);
                assertEquals(owner, currentOwner);
            }
            assertNotNull(currentOwner);
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM memberships"
                    + " WHERE household_id=? AND active", Integer.class, home));
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
            cleanup(home, owner, target);
        }
    }

    @Test void renameAndInvitationStayAvailableWhileATransferIsPending() throws Exception {
        UUID owner = user("own");
        UUID target = user("tgt");
        String ownerToken = login(owner);
        String targetToken = login(target);
        UUID home = create(ownerToken);
        try {
            assertEquals(201, join(targetToken, invite(ownerToken, home)));
            UUID requestId = requestIdOf(requestRaw(ownerToken, home, target));

            long version = householdVersion(home);
            JsonNode renamed = jsonOf(call(put("/api/v1/households/" + home)
                    .header("Authorization", targetToken)
                    .param("expectedVersion", Long.toString(version))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Renamed while pending\"}")));
            assertEquals("Renamed while pending", renamed.path("name").asText());
            assertEquals(owner.toString(), renamed.path("ownerUserId").asText());

            JsonNode invitation = jsonOf(call(post("/api/v1/households/" + home + "/invitations")
                    .header("Authorization", targetToken)
                    .header("Idempotency-Key", UUID.randomUUID())));
            assertNotNull(invitation.path("code").asText(null));
            assertEquals("PENDING", jdbc.queryForObject(
                    "SELECT status FROM ownership_transfer_requests WHERE id=?", String.class, requestId));
        } finally {
            cleanup(home, owner, target);
        }
    }

    // ---- fixtures and helpers ----

    private UUID user(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,username,display_name,password_hash,zone_id) VALUES (?,?,?,?,?)",
                id, prefix + "_" + id.toString().replace("-", "").substring(0, 18), "Owner fixture",
                passwords.encode("fixture-password"), "UTC");
        return id;
    }

    private String login(UUID user) throws Exception {
        String name = jdbc.queryForObject("SELECT username FROM users WHERE id=?", String.class, user);
        MvcResult result = call(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", name, "password", "fixture-password"))));
        assertEquals(200, status(result));
        return "Bearer " + jsonOf(result).path("token").asText();
    }

    private UUID create(String token) throws Exception {
        MvcResult result = call(post("/api/v1/households")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Owner fixture home\"}"));
        assertEquals(201, status(result));
        return UUID.fromString(jsonOf(result).path("id").asText());
    }

    private String invite(String token, UUID home) throws Exception {
        MvcResult result = call(get("/api/v1/households/" + home + "/invitations")
                .header("Authorization", token));
        assertEquals(200, status(result));
        return jsonOf(result).path("code").asText();
    }

    private int join(String token, String code) throws Exception {
        return status(call(post("/api/v1/households/join")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code)))));
    }

    private MvcResult requestRaw(String token, UUID home, UUID target) throws Exception {
        return call(post("/api/v1/households/" + home + "/owner-transfer-requests")
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("targetUserId", target.toString()))));
    }

    private UUID requestIdOf(MvcResult result) throws Exception {
        assertEquals(201, status(result));
        return UUID.fromString(jsonOf(result).path("id").asText());
    }

    private MvcResult acceptRaw(String token, UUID home, UUID requestId, UUID key) throws Exception {
        return call(post("/api/v1/households/" + home + "/owner-transfer-requests/" + requestId + "/accept")
                .header("Authorization", token).header("Idempotency-Key", key));
    }

    private MvcResult rejectRaw(String token, UUID home, UUID requestId) throws Exception {
        return call(post("/api/v1/households/" + home + "/owner-transfer-requests/" + requestId + "/reject")
                .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID()));
    }

    private MvcResult cancelRaw(String token, UUID home, UUID requestId, UUID key) throws Exception {
        return call(post("/api/v1/households/" + home + "/owner-transfer-requests/" + requestId + "/cancel")
                .header("Authorization", token).header("Idempotency-Key", key));
    }

    private MvcResult dissolveRaw(String token, UUID home) throws Exception {
        return call(post("/api/v1/households/" + home + "/dissolve")
                .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID()));
    }

    private MvcResult leaveRaw(String token, UUID home, UUID user, long version) throws Exception {
        return call(delete("/api/v1/households/" + home + "/members/" + user)
                .header("Authorization", token)
                .param("expectedVersion", Long.toString(version)));
    }

    private int leave(String token, UUID home, UUID user, long version) throws Exception {
        return status(leaveRaw(token, home, user, version));
    }

    private int listTotal(String token, UUID home) throws Exception {
        return jsonOf(call(get("/api/v1/households/" + home + "/owner-transfer-requests")
                .header("Authorization", token))).path("total").asInt();
    }

    private long householdVersion(UUID home) {
        return jdbc.queryForObject("SELECT version FROM households WHERE id=?", Long.class, home);
    }

    private long membershipVersion(UUID home, UUID user) {
        return jdbc.queryForObject("SELECT version FROM memberships"
                + " WHERE household_id=? AND user_id=? AND active", Long.class, home, user);
    }

    private MvcResult call(MockHttpServletRequestBuilder request) throws Exception {
        return http.perform(request).andReturn();
    }

    private int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private JsonNode jsonOf(MvcResult result) throws Exception {
        int status = status(result);
        assertTrue(status >= 200 && status < 300,
                "expected success but was " + status + ": " + result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String failureCode(MvcResult result, int expectedStatus) throws Exception {
        assertEquals(expectedStatus, status(result), result.getResponse().getContentAsString());
        return json.readTree(result.getResponse().getContentAsString()).path("code").asText();
    }

    private void cleanup(UUID home, UUID... users) {
        if (home != null) jdbc.update("DELETE FROM ownership_transfer_requests WHERE household_id=?", home);
        for (UUID user : users) {
            jdbc.update("DELETE FROM idempotency_requests WHERE actor_id=?", user);
            jdbc.update("DELETE FROM sessions WHERE user_id=?", user);
        }
        for (UUID user : users) jdbc.update("DELETE FROM memberships WHERE user_id=?", user);
        if (home != null) jdbc.update("DELETE FROM invitations WHERE household_id=?", home);
        if (home != null) jdbc.update("DELETE FROM households WHERE id=?", home);
        for (UUID user : users) jdbc.update("DELETE FROM users WHERE id=?", user);
    }
}
