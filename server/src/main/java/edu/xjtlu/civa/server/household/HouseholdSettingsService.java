package edu.xjtlu.civa.server.household;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Any active member can rename; concurrent settings edits must never silently overwrite. */
final class HouseholdSettingsService {
    private final JdbcTemplate jdbc;
    private final HouseholdReadService read;
    private final TransactionTemplate transactions;
    HouseholdSettingsService(JdbcTemplate jdbc,HouseholdReadService read,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.read=read;transactions=new TransactionTemplate(manager);
    }
    HouseholdReadService.Household rename(UUID actor,UUID home,long expectedVersion,String requested) {
        if(expectedVersion<0||requested==null)throw new IllegalArgumentException("Invalid household settings");
        String name=requested.trim();
        if(name.isEmpty()||name.codePointCount(0,name.length())>120)throw new IllegalArgumentException("Invalid household name");
        return transactions.execute(status->{
            var rows=jdbc.query("SELECT id FROM households WHERE id=? AND closed_at IS NULL FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),home);
            if(rows.isEmpty())throw new HouseholdWriteService.Failure("NOT_FOUND");
            var current=read.getHousehold(actor,home); // checks active membership, including legacy-role compatibility
            if(current.version!=expectedVersion)throw new HouseholdWriteService.Failure("VERSION_CONFLICT");
            if(!name.equals(current.name)) {
                int changed=jdbc.update("UPDATE households SET name=?,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version=? AND closed_at IS NULL",name,home,expectedVersion);
                if(changed!=1)throw new HouseholdWriteService.Failure("VERSION_CONFLICT");
            }
            return read.getHousehold(actor,home);
        });
    }
}
