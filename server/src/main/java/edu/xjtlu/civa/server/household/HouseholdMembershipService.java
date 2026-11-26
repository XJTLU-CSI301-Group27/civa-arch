package edu.xjtlu.civa.server.household;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Existing removeMember contract: self-leave only; no member may remove another member. */
final class HouseholdMembershipService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    HouseholdMembershipService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transactions = new TransactionTemplate(manager);
    }

    void remove(UUID actor, UUID household, UUID target, long expectedVersion) {
        if (expectedVersion < 0) throw new IllegalArgumentException("Invalid membership version");
        transactions.executeWithoutResult(status -> {
            List<UUID> homes = jdbc.query("SELECT id FROM households WHERE id=? AND closed_at IS NULL FOR UPDATE", (rs, n) -> rs.getObject(1, UUID.class), household);
            if (homes.isEmpty()) throw new HouseholdWriteService.Failure("NOT_FOUND");
            Member acting = active(household, actor);
            if (acting == null) {
                Boolean revoked = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships WHERE household_id=? AND user_id=? AND NOT active)", Boolean.class, household, actor);
                throw new HouseholdWriteService.Failure(Boolean.TRUE.equals(revoked) ? "HOUSEHOLD_ACCESS_REVOKED" : "NOT_FOUND");
            }
            if (!actor.equals(target)) throw new HouseholdWriteService.Failure("FORBIDDEN");
            Member leaving = active(household, target);
            if (leaving == null) throw new HouseholdWriteService.Failure("NOT_FOUND");
            if (leaving.version != expectedVersion) throw new HouseholdWriteService.Failure("VERSION_CONFLICT");
            // The owner can only leave after a completed transfer; a sole owner must dissolve instead.
            UUID owner = ownerOf(household);
            if (owner != null && owner.equals(actor)) {
                Integer active = jdbc.queryForObject(
                        "SELECT count(*) FROM memberships WHERE household_id=? AND active", Integer.class, household);
                throw new HouseholdWriteService.Failure(active != null && active > 1
                        ? "OWNER_MUST_TRANSFER" : "OWNER_MUST_DISSOLVE");
            }
            int count = jdbc.update("UPDATE memberships SET active=false,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND active AND version=?", leaving.id, expectedVersion);
            if (count != 1) throw new HouseholdWriteService.Failure("VERSION_CONFLICT");
            // A member leaving cancels the transfer request that targets them; the owner is unchanged.
            jdbc.update("UPDATE ownership_transfer_requests SET status='CANCELLED', resolved_at=CURRENT_TIMESTAMP,"
                            + " resolved_by=?, version=version+1, updated_at=CURRENT_TIMESTAMP"
                            + " WHERE household_id=? AND target_user_id=? AND status='PENDING'",
                    actor, household, actor);
            // Join, invitation access and leave all lock this same household first.
            // A concurrent join either commits before closure or sees a closed household.
            jdbc.update("UPDATE households SET closed_at=CURRENT_TIMESTAMP,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND NOT EXISTS(SELECT 1 FROM memberships WHERE household_id=? AND active)",household,household);
        });
    }

    private UUID ownerOf(UUID household) {
        List<UUID> rows = jdbc.query("SELECT owner_user_id FROM households WHERE id=?",
                (rs, n) -> rs.getObject(1, UUID.class), household);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Member active(UUID household, UUID user) {
        List<Member> members = jdbc.query("SELECT id,role,version FROM memberships WHERE household_id=? AND user_id=? AND active FOR UPDATE",
                (rs, n) -> new Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3)), household, user);
        return members.isEmpty() ? null : members.get(0);
    }

    private record Member(UUID id, String role, long version) { }
}
