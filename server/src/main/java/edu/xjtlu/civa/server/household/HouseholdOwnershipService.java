package edu.xjtlu.civa.server.household;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.xjtlu.civa.server.api.model.OwnershipTransferRequest;
import edu.xjtlu.civa.server.api.model.OwnershipTransferRequestPage;
import edu.xjtlu.civa.server.api.model.TransferStatus;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two-phase ownership transfer. A request never changes the owner; only the target's acceptance does,
 * atomically with resolving the request. Every write locks the household row first so leave, join and
 * transfer cannot interleave into a household without an owner.
 */
final class HouseholdOwnershipService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String SELECT = "SELECT id,household_id,requested_by,target_user_id,status,version,"
            + "created_at,updated_at,resolved_at,resolved_by FROM ownership_transfer_requests";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdempotencyExecutor executor;

    HouseholdOwnershipService(JdbcTemplate jdbc, ObjectMapper json, IdempotencyExecutor executor) {
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
    }

    /** Only the current owner and the target can see request details; other members get an empty page. */
    OwnershipTransferRequestPage list(UUID actor, UUID home, TransferStatus status, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Invalid pagination");
        requireMember(actor, home);
        UUID owner = ownerOf(home);
        boolean isOwner = owner != null && owner.equals(actor);
        StringBuilder where = new StringBuilder(" WHERE household_id=?");
        List<Object> params = new ArrayList<>();
        params.add(home);
        if (!isOwner) {
            where.append(" AND target_user_id=?");
            params.add(actor);
        }
        if (status != null) {
            where.append(" AND status=?");
            params.add(status.getValue());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM ownership_transfer_requests" + where,
                Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);
        List<OwnershipTransferRequest> items = jdbc.query(SELECT + where
                        + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                (rs, n) -> model(row(rs)), pageParams.toArray());
        return new OwnershipTransferRequestPage(items, page, size, total == null ? 0 : total);
    }

    IdempotencyExecutor.Result request(UUID actor, UUID home, UUID target, UUID key, byte[] digest) {
        return executor.execute(actor, key, digest, () -> {
            Locked locked = lock(home);
            if (locked.owner == null || !locked.owner.equals(actor)) {
                throw new HouseholdWriteService.Failure("NOT_HOUSEHOLD_OWNER");
            }
            if (target == null || target.equals(actor)) {
                throw new HouseholdWriteService.Failure("INVALID_TRANSFER_TARGET");
            }
            if (!isActiveMember(home, target)) {
                throw new HouseholdWriteService.Failure("INVALID_TRANSFER_TARGET");
            }
            UUID id = UUID.randomUUID();
            try {
                jdbc.update("INSERT INTO ownership_transfer_requests"
                                + "(id,household_id,requested_by,target_user_id,status) VALUES (?,?,?,?,'PENDING')",
                        id, home, actor, target);
            } catch (DuplicateKeyException duplicate) {
                throw new HouseholdWriteService.Failure("TRANSFER_ALREADY_PENDING");
            }
            return new IdempotencyExecutor.Result(201, write(model(load(id))));
        }, result -> requireOwner(actor, home));
    }

    IdempotencyExecutor.Result accept(UUID actor, UUID home, UUID requestId, UUID key, byte[] digest) {
        return executor.execute(actor, key, digest, () -> {
            Locked locked = lock(home);
            TransferRow row = loadForUpdate(home, requestId);
            if (row == null) throw new HouseholdWriteService.Failure("TRANSFER_REQUEST_NOT_FOUND");
            if (!PENDING.equals(row.status)) throw new HouseholdWriteService.Failure("TRANSFER_NOT_PENDING");
            if (!actor.equals(row.targetUserId)) throw new HouseholdWriteService.Failure("NOT_TRANSFER_TARGET");
            if (locked.owner == null || !locked.owner.equals(row.requestedBy)) {
                throw new HouseholdWriteService.Failure("TRANSFER_NOT_PENDING");
            }
            if (!isActiveMember(home, actor)) throw new HouseholdWriteService.Failure("NOT_TRANSFER_TARGET");
            int resolved = jdbc.update("UPDATE ownership_transfer_requests SET status='ACCEPTED',"
                            + " resolved_at=CURRENT_TIMESTAMP, resolved_by=?, version=version+1,"
                            + " updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",
                    actor, requestId);
            if (resolved != 1) throw new HouseholdWriteService.Failure("TRANSFER_NOT_PENDING");
            int changed = jdbc.update("UPDATE households SET owner_user_id=?, owner_source='TRANSFER',"
                            + " owner_assigned_at=CURRENT_TIMESTAMP, owner_confirmed_at=CURRENT_TIMESTAMP,"
                            + " version=version+1, updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=?",
                    actor, home, locked.version);
            if (changed != 1) throw new HouseholdWriteService.Failure("VERSION_CONFLICT");
            return new IdempotencyExecutor.Result(200, householdJson(home));
        }, result -> requireMember(actor, home));
    }

    IdempotencyExecutor.Result reject(UUID actor, UUID home, UUID requestId, UUID key, byte[] digest) {
        return executor.execute(actor, key, digest, () -> {
            lock(home);
            TransferRow row = loadForUpdate(home, requestId);
            if (row == null) throw new HouseholdWriteService.Failure("TRANSFER_REQUEST_NOT_FOUND");
            if (!PENDING.equals(row.status)) throw new HouseholdWriteService.Failure("TRANSFER_NOT_PENDING");
            if (!actor.equals(row.targetUserId)) throw new HouseholdWriteService.Failure("NOT_TRANSFER_TARGET");
            int resolved = jdbc.update("UPDATE ownership_transfer_requests SET status='REJECTED',"
                            + " resolved_at=CURRENT_TIMESTAMP, resolved_by=?, version=version+1,"
                            + " updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",
                    actor, requestId);
            if (resolved != 1) throw new HouseholdWriteService.Failure("TRANSFER_NOT_PENDING");
            return new IdempotencyExecutor.Result(200, write(model(load(requestId))));
        }, result -> requireMember(actor, home));
    }

    IdempotencyExecutor.Result cancel(UUID actor, UUID home, UUID requestId, UUID key, byte[] digest) {
        return executor.execute(actor, key, digest, () -> {
            Locked locked = lock(home);
            TransferRow row = loadForUpdate(home, requestId);
            if (row == null) throw new HouseholdWriteService.Failure("TRANSFER_REQUEST_NOT_FOUND");
            if (!PENDING.equals(row.status)) throw new HouseholdWriteService.Failure("TRANSFER_NOT_PENDING");
            if (locked.owner == null || !locked.owner.equals(actor) || !row.requestedBy.equals(actor)) {
                throw new HouseholdWriteService.Failure("NOT_HOUSEHOLD_OWNER");
            }
            int resolved = jdbc.update("UPDATE ownership_transfer_requests SET status='CANCELLED',"
                            + " resolved_at=CURRENT_TIMESTAMP, resolved_by=?, version=version+1,"
                            + " updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING'",
                    actor, requestId);
            if (resolved != 1) throw new HouseholdWriteService.Failure("TRANSFER_NOT_PENDING");
            return new IdempotencyExecutor.Result(200, write(model(load(requestId))));
        }, result -> requireOwner(actor, home));
    }

    IdempotencyExecutor.Result dissolve(UUID actor, UUID home, UUID key, byte[] digest) {
        return executor.execute(actor, key, digest, () -> {
            Locked locked = lock(home);
            if (locked.owner == null || !locked.owner.equals(actor)) {
                throw new HouseholdWriteService.Failure("NOT_HOUSEHOLD_OWNER");
            }
            Integer active = jdbc.queryForObject(
                    "SELECT count(*) FROM memberships WHERE household_id=? AND active", Integer.class, home);
            if (active == null || active != 1) {
                throw new HouseholdWriteService.Failure("HOUSEHOLD_HAS_MEMBERS");
            }
            jdbc.update("UPDATE ownership_transfer_requests SET status='CANCELLED',"
                            + " resolved_at=CURRENT_TIMESTAMP, resolved_by=?, version=version+1,"
                            + " updated_at=CURRENT_TIMESTAMP WHERE household_id=? AND status='PENDING'",
                    actor, home);
            jdbc.update("UPDATE memberships SET active=false, version=version+1, updated_at=CURRENT_TIMESTAMP"
                    + " WHERE household_id=? AND user_id=? AND active", home, actor);
            int changed = jdbc.update("UPDATE households SET closed_at=CURRENT_TIMESTAMP, owner_user_id=NULL,"
                            + " owner_source='CLOSED', version=version+1, updated_at=CURRENT_TIMESTAMP"
                            + " WHERE id=? AND version=?",
                    home, locked.version);
            if (changed != 1) throw new HouseholdWriteService.Failure("VERSION_CONFLICT");
            return new IdempotencyExecutor.Result(204, null);
        }, result -> authorizeDissolveReplay(actor, home));
    }

    private static final String PENDING = "PENDING";

    private void requireMember(UUID actor, UUID home) {
        Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships"
                + " WHERE household_id=? AND user_id=? AND active)", Boolean.class, home, actor);
        if (!Boolean.TRUE.equals(active)) {
            Boolean revoked = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships"
                    + " WHERE household_id=? AND user_id=? AND NOT active)", Boolean.class, home, actor);
            throw new HouseholdWriteService.Failure(Boolean.TRUE.equals(revoked)
                    ? "HOUSEHOLD_ACCESS_REVOKED" : "NOT_FOUND");
        }
    }

    private void requireOwner(UUID actor, UUID home) {
        requireMember(actor, home);
        UUID owner = ownerOf(home);
        if (owner == null || !owner.equals(actor)) {
            throw new HouseholdWriteService.Failure("NOT_HOUSEHOLD_OWNER");
        }
    }

    private void authorizeDissolveReplay(UUID actor, UUID home) {
        Boolean closed = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM households"
                + " WHERE id=? AND closed_at IS NOT NULL)", Boolean.class, home);
        if (Boolean.TRUE.equals(closed)) {
            Boolean wasMember = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships"
                    + " WHERE household_id=? AND user_id=? AND NOT active)", Boolean.class, home, actor);
            if (!Boolean.TRUE.equals(wasMember)) throw new HouseholdWriteService.Failure("NOT_FOUND");
            return;
        }
        requireMember(actor, home);
    }

    private boolean isActiveMember(UUID home, UUID user) {
        Boolean active = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships"
                + " WHERE household_id=? AND user_id=? AND active)", Boolean.class, home, user);
        return Boolean.TRUE.equals(active);
    }

    private UUID ownerOf(UUID home) {
        List<UUID> rows = jdbc.query("SELECT owner_user_id FROM households WHERE id=? AND closed_at IS NULL",
                (rs, row) -> rs.getObject(1, UUID.class), home);
        if (rows.isEmpty()) throw new HouseholdWriteService.Failure("NOT_FOUND");
        return rows.get(0);
    }

    private Locked lock(UUID home) {
        List<Locked> rows = jdbc.query("SELECT owner_user_id,version FROM households"
                        + " WHERE id=? AND closed_at IS NULL FOR UPDATE",
                (rs, row) -> new Locked(rs.getObject(1, UUID.class), rs.getLong(2)), home);
        if (rows.isEmpty()) throw new HouseholdWriteService.Failure("NOT_FOUND");
        return rows.get(0);
    }

    private TransferRow loadForUpdate(UUID home, UUID id) {
        List<TransferRow> rows = jdbc.query(SELECT + " WHERE id=? AND household_id=? FOR UPDATE",
                (rs, n) -> row(rs), id, home);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private TransferRow load(UUID id) {
        List<TransferRow> rows = jdbc.query(SELECT + " WHERE id=?", (rs, n) -> row(rs), id);
        if (rows.isEmpty()) throw new HouseholdWriteService.Failure("TRANSFER_REQUEST_NOT_FOUND");
        return rows.get(0);
    }

    private TransferRow row(ResultSet rs) throws SQLException {
        return new TransferRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getString(5), rs.getLong(6),
                rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant(),
                rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant(),
                rs.getObject(10, UUID.class));
    }

    private OwnershipTransferRequest model(TransferRow row) {
        return new OwnershipTransferRequest(row.id, time(row.createdAt), time(row.updatedAt), row.householdId,
                row.requestedBy, row.targetUserId, TransferStatus.fromValue(row.status), row.version)
                .resolvedAt(row.resolvedAt == null ? null : time(row.resolvedAt))
                .resolvedBy(row.resolvedBy);
    }

    private String householdJson(UUID home) {
        return jdbc.query("SELECT id,name,zone_id,version,created_at,updated_at,owner_user_id,owner_source"
                        + " FROM households WHERE id=?",
                (ResultSet rs) -> {
                    if (!rs.next()) throw new HouseholdWriteService.Failure("NOT_FOUND");
                    ObjectNode body = json.createObjectNode();
                    body.put("id", rs.getObject(1, UUID.class).toString());
                    body.put("name", rs.getString(2));
                    body.put("zoneId", rs.getString(3));
                    body.put("version", rs.getLong(4));
                    body.put("createdAt", time(rs.getTimestamp(5).toInstant()));
                    body.put("updatedAt", time(rs.getTimestamp(6).toInstant()));
                    UUID owner = rs.getObject(7, UUID.class);
                    body.put("ownerUserId", owner == null ? null : owner.toString());
                    body.put("ownerSource", rs.getString(8));
                    return write(body);
                }, home);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot write ownership response", invalid);
        }
    }

    private static String time(Instant instant) {
        return ISO.format(instant);
    }

    private record Locked(UUID owner, long version) { }

    private record TransferRow(UUID id, UUID householdId, UUID requestedBy, UUID targetUserId, String status,
                               long version, Instant createdAt, Instant updatedAt, Instant resolvedAt,
                               UUID resolvedBy) { }
}
