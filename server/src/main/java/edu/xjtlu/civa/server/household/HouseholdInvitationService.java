package edu.xjtlu.civa.server.household;

import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import edu.xjtlu.civa.server.config.JsonbCodec;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One persistent code per household. Every active member has the same access. */
final class HouseholdInvitationService {
    private final JdbcTemplate jdbc;
    private final IdempotencyExecutor idempotency;
    private final JsonbCodec json;
    private final InvitationReceiptCipher cipher;
    private final TransactionTemplate transactions;
    private final SecureRandom random=new SecureRandom();
    HouseholdInvitationService(JdbcTemplate jdbc, IdempotencyExecutor idempotency, JsonbCodec json,
            InvitationReceiptCipher cipher, PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.idempotency=idempotency;this.json=json;this.cipher=cipher;
        transactions=new TransactionTemplate(manager);
    }
    /** Called inside the household-create transaction, which already owns its new household row. */
    void provisionLocked(UUID home, UUID actor) {
        Boolean exists=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM invitations WHERE household_id=? AND permanent)",Boolean.class,home);
        if(Boolean.TRUE.equals(exists))return;
        String code=InvitationCodes.generate(random);
        jdbc.update("INSERT INTO invitations(id,household_id,created_by,code_hash,expires_at,permanent,sealed_code) VALUES (?,?,?,?,NULL,true,?)",
                UUID.randomUUID(),home,actor,InvitationCodes.hash(code),cipher.seal(code));
    }
    /** Backfills only existing populated homes; never resets a fixed code or revives a closed home. */
    void provisionExisting() {
        var homes=jdbc.query("SELECT id FROM households WHERE closed_at IS NULL AND EXISTS(SELECT 1 FROM memberships m WHERE m.household_id=households.id AND m.active)",(rs,n)->rs.getObject(1,UUID.class));
        for(UUID home:homes) transactions.executeWithoutResult(status->{
            var locked=jdbc.query("SELECT id FROM households WHERE id=? AND closed_at IS NULL FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),home);
            if(locked.isEmpty())return;
            var users=jdbc.query("SELECT user_id FROM memberships WHERE household_id=? AND active ORDER BY id LIMIT 1",(rs,n)->rs.getObject(1,UUID.class),home);
            if(!users.isEmpty())provisionLocked(home,users.get(0));
        });
    }
    String get(UUID actor,UUID home) {
        return transactions.execute(status->{requireMember(actor,home);return reveal(stored(home));});
    }
    /** Compatibility POST returns the same code; it cannot rotate, expire or consume it. */
    IdempotencyExecutor.Result create(UUID actor,UUID home,UUID key,byte[] digest) {
        var result=idempotency.execute(actor,key,digest,()->{
            requireMember(actor,home);
            return new IdempotencyExecutor.Result(201,stored(home));
        },stored->requireMember(actor,home));
        // Older successful idempotency receipts must not resurrect a one-use legacy invitation.
        return new IdempotencyExecutor.Result(result.status,get(actor,home));
    }
    private String stored(UUID home) {
        var rows=jdbc.query("SELECT id,sealed_code FROM invitations WHERE household_id=? AND permanent",(rs,n)->{
            var body=json.object();body.put("id",rs.getObject(1,UUID.class).toString());body.put("householdId",home.toString());
            body.put("code",rs.getString(2));body.putNull("expiresAt");return json.write(body);
        },home);
        if(rows.size()!=1)throw new IllegalStateException("Fixed household invitation missing");
        return rows.get(0);
    }
    private String reveal(String stored) {
        var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.read(stored);
        body.put("code",cipher.open(body.path("code").asText()));return json.write(body);
    }
    private void requireMember(UUID actor,UUID home) {
        var homes=jdbc.query("SELECT id FROM households WHERE id=? AND closed_at IS NULL FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),home);
        if(homes.isEmpty())throw new HouseholdWriteService.Failure("NOT_FOUND");
        Boolean active=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships WHERE household_id=? AND user_id=? AND active)",Boolean.class,home,actor);
        if(!Boolean.TRUE.equals(active)) {
            Boolean revoked=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships WHERE household_id=? AND user_id=? AND NOT active)",Boolean.class,home,actor);
            throw new HouseholdWriteService.Failure(Boolean.TRUE.equals(revoked)?"HOUSEHOLD_ACCESS_REVOKED":"NOT_FOUND");
        }
    }
}
