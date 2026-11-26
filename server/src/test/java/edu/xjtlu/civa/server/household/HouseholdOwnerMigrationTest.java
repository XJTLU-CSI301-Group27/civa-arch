package edu.xjtlu.civa.server.household;

import edu.xjtlu.civa.server.CivaServer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real Flyway migrations against a throwaway schema: migrate to the version before the owner
 * migration, seed legacy households, migrate the rest and inspect the result.
 */
@SpringBootTest(classes = CivaServer.class)
class HouseholdOwnerMigrationTest {
    @Autowired DataSource dataSource;

    @Test void backfillsDeterministicallyAndKeepsPendingFamiliesUsable() throws Exception {
        String schema = "mig_" + UUID.randomUUID().toString().replace("-", "");
        UUID signatureHome = UUID.randomUUID();
        UUID soleHome = UUID.randomUUID();
        UUID ambiguousHome = UUID.randomUUID();
        UUID closedHome = UUID.randomUUID();
        UUID signatureUser = UUID.randomUUID();
        UUID joiner = UUID.randomUUID();
        UUID soleUser = UUID.randomUUID();
        UUID ambiguousA = UUID.randomUUID();
        UUID ambiguousB = UUID.randomUUID();
        UUID closedUser = UUID.randomUUID();
        try {
            execute("CREATE SCHEMA " + schema);
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .target(org.flywaydb.core.api.MigrationVersion.fromVersion("20261126213000"))
                    .load().migrate();

            Timestamp created = Timestamp.valueOf("2026-01-01 10:00:00");
            for (UUID user : new UUID[]{signatureUser, joiner, soleUser, ambiguousA, ambiguousB, closedUser}) {
                insertUser(schema, user);
            }
            insertHousehold(schema, signatureHome, "Signature home", created, null);
            insertMembership(schema, signatureHome, signatureUser, created, true);
            insertMembership(schema, signatureHome, joiner, Timestamp.valueOf("2026-01-02 10:00:00"), true);

            insertHousehold(schema, soleHome, "Sole home", created, null);
            insertMembership(schema, soleHome, soleUser, Timestamp.valueOf("2026-01-01 11:00:00"), true);

            insertHousehold(schema, ambiguousHome, "Ambiguous home", created, null);
            insertMembership(schema, ambiguousHome, ambiguousA, Timestamp.valueOf("2026-01-01 11:00:00"), true);
            insertMembership(schema, ambiguousHome, ambiguousB, Timestamp.valueOf("2026-01-01 12:00:00"), true);

            insertHousehold(schema, closedHome, "Closed home", created, Timestamp.valueOf("2026-01-03 10:00:00"));
            insertMembership(schema, closedHome, closedUser, created, false);

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();

            assertOwner(schema, signatureHome, signatureUser, "MIGRATION_SIGNATURE");
            assertOwner(schema, soleHome, soleUser, "MIGRATION_SOLE");
            assertOwner(schema, ambiguousHome, null, "PENDING_CLAIM");
            assertOwner(schema, closedHome, null, "CLOSED");

            // A pending-claim household must stay editable: a plain owner CHECK would reject this update.
            execute("UPDATE " + schema + ".households SET name='Pending renamed' WHERE id='" + ambiguousHome + "'");
            assertEquals("Pending renamed", queryString(schema,
                    "SELECT name FROM " + schema + ".households WHERE id='" + ambiguousHome + "'"));

            // Claiming is a controlled manual operation: it moves the row to a valid owner state.
            execute("UPDATE " + schema + ".households SET owner_user_id='" + ambiguousA
                    + "', owner_source='CLAIM', owner_assigned_at=CURRENT_TIMESTAMP,"
                    + " owner_confirmed_at=CURRENT_TIMESTAMP WHERE id='" + ambiguousHome + "'");
            assertOwner(schema, ambiguousHome, ambiguousA, "CLAIM");

            // The owner cannot be cleared again without a pending/closed state.
            SQLException cleared = assertThrows(SQLException.class, () -> execute(
                    "UPDATE " + schema + ".households SET owner_user_id=NULL WHERE id='" + ambiguousHome + "'"));
            assertEquals("23514", cleared.getSQLState());

            SQLException invalid = assertThrows(SQLException.class, () -> execute(
                    "INSERT INTO " + schema + ".households(id,name,owner_source) VALUES ('"
                            + UUID.randomUUID() + "','Invalid','MIGRATION_EARLIEST')"));
            assertEquals("23514", invalid.getSQLState());

            execute("INSERT INTO " + schema + ".households(id,name,owner_source,closed_at) VALUES ('"
                    + UUID.randomUUID() + "','Closed without owner','CLOSED',CURRENT_TIMESTAMP)");

            // At most one PENDING transfer per household.
            UUID first = UUID.randomUUID();
            execute("INSERT INTO " + schema + ".ownership_transfer_requests"
                    + "(id,household_id,requested_by,target_user_id,status) VALUES ('" + first + "','"
                    + signatureHome + "','" + signatureUser + "','" + joiner + "','PENDING')");
            SQLException duplicate = assertThrows(SQLException.class, () -> execute(
                    "INSERT INTO " + schema + ".ownership_transfer_requests"
                            + "(id,household_id,requested_by,target_user_id,status) VALUES ('" + UUID.randomUUID()
                            + "','" + signatureHome + "','" + signatureUser + "','" + joiner + "','PENDING')"));
            assertEquals("23505", duplicate.getSQLState());
        } finally {
            execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void assertOwner(String schema, UUID home, UUID expected, String source) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT owner_user_id, owner_source FROM " + schema + ".households WHERE id=?")) {
            statement.setObject(1, home);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                if (expected == null) {
                    assertNull(rows.getObject(1));
                } else {
                    assertEquals(expected, rows.getObject(1, UUID.class));
                }
                assertEquals(source, rows.getString(2));
            }
        }
    }

    private String queryString(String schema, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private void insertUser(String schema, UUID id) throws SQLException {
        execute("INSERT INTO " + schema + ".users(id,username,display_name,password_hash,zone_id) VALUES ('"
                + id + "','u_" + id.toString().replace("-", "").substring(0, 20) + "','Legacy','hash','UTC')");
    }

    private void insertHousehold(String schema, UUID id, String name, Timestamp created, Timestamp closed)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + schema + ".households(id,name,created_at,closed_at) VALUES (?,?,?,?)")) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setTimestamp(3, created);
            statement.setTimestamp(4, closed);
            statement.executeUpdate();
        }
    }

    private void insertMembership(String schema, UUID home, UUID user, Timestamp created, boolean active)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO " + schema + ".memberships(id,household_id,user_id,role,active,created_at)"
                             + " VALUES (?,?,?,'MEMBER',?,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, home);
            statement.setObject(3, user);
            statement.setBoolean(4, active);
            statement.setTimestamp(5, created);
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
