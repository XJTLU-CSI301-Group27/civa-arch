package edu.xjtlu.civa.server.plan;

import edu.xjtlu.civa.server.api.model.PlanItem;
import edu.xjtlu.civa.server.api.model.PlanMealType;
import edu.xjtlu.civa.server.api.model.PlanSlot;
import edu.xjtlu.civa.server.api.model.PlanSlotPage;
import edu.xjtlu.civa.server.api.model.PlanSlotWrite;
import edu.xjtlu.civa.server.api.model.PlanCommitRequest;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Household meal plans: one slot per date+meal, versioned, with lockable slots. */
final class PlanningService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final IdempotencyExecutor executor;
    private final TransactionTemplate transactions;

    PlanningService(JdbcTemplate jdbc, IdempotencyExecutor executor, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.executor = executor;
        this.transactions = new TransactionTemplate(manager);
    }

    PlanSlotPage list(UUID actor, UUID home, String from, String to, Integer page, Integer size) {
        requireMember(actor, home);
        LocalDate start = from == null || from.isBlank() ? LocalDate.now() : date(from);
        LocalDate end = to == null || to.isBlank() ? start.plusDays(13) : date(to);
        if (end.isBefore(start)) throw new PlanFault("INVALID_PLAN");
        if (start.plusDays(90).isBefore(end)) throw new PlanFault("INVALID_PLAN");
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 60 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 200) throw new PlanFault("INVALID_PLAN");
        List<PlanSlot> slots = jdbc.query("SELECT s.id, s.household_id, s.date, s.meal_type, s.locked, s.version,"
                        + " s.created_at, s.updated_at, s.author_id, u.display_name AS last_editor_name"
                        + " FROM plan_slots s LEFT JOIN users u ON u.id=s.author_id"
                        + " WHERE s.household_id=? AND s.date BETWEEN ? AND ?"
                        + " ORDER BY s.date, CASE s.meal_type WHEN 'BREAKFAST' THEN 0 WHEN 'LUNCH' THEN 1 ELSE 2 END"
                        + " LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), home, start, end, pageSize, (long) pageNumber * pageSize);
        return new PlanSlotPage(slots, pageNumber, pageSize, (long) slots.size());
    }

    PlanSlot replace(UUID actor, UUID home, String dateText, PlanMealType meal, List<PlanItem> items,
                     Long expectedVersion) {
        requireMember(actor, home);
        LocalDate date = date(dateText);
        if (meal == null || expectedVersion == null) throw new PlanFault("INVALID_PLAN");
        List<PlanItem> rows = validate(home, items);
        return transactions.execute(status -> {
            List<PlanSlot> existing = slotRow(home, date, meal, true);
            if (existing.isEmpty()) {
                if (expectedVersion != 0) throw new PlanFault("VERSION_CONFLICT");
                UUID id = UUID.randomUUID();
                jdbc.update("INSERT INTO plan_slots(id, household_id, date, meal_type, locked, author_id)"
                                + " VALUES (?,?,?,?,false,?)",
                        id, home, date, meal.getValue(), actor);
                writeItems(id, home, rows);
                return get(home, date, meal);
            }
            PlanSlot slot = existing.get(0);
            if (slot.getLocked()) throw new PlanFault("PLAN_SLOT_LOCKED");
            if (!expectedVersion.equals(slot.getVersion())) throw new PlanFault("VERSION_CONFLICT");
            jdbc.update("UPDATE plan_slots SET version=version+1, updated_at=CURRENT_TIMESTAMP, author_id=?"
                    + " WHERE id=?", actor, slot.getId());
            jdbc.update("DELETE FROM plan_items WHERE slot_id=?", slot.getId());
            writeItems(slot.getId(), home, rows);
            return get(home, date, meal);
        });
    }

    PlanSlot lock(UUID actor, UUID home, String dateText, PlanMealType meal, boolean locked, Long expectedVersion) {
        requireMember(actor, home);
        LocalDate date = date(dateText);
        if (meal == null || expectedVersion == null) throw new PlanFault("INVALID_PLAN");
        return transactions.execute(status -> {
            List<PlanSlot> existing = slotRow(home, date, meal, true);
            if (existing.isEmpty()) {
                if (expectedVersion != 0) throw new PlanFault("VERSION_CONFLICT");
                UUID id = UUID.randomUUID();
                jdbc.update("INSERT INTO plan_slots(id, household_id, date, meal_type, locked, author_id)"
                                + " VALUES (?,?,?,?,?,?)",
                        id, home, date, meal.getValue(), locked, actor);
                return get(home, date, meal);
            }
            PlanSlot slot = existing.get(0);
            if (!expectedVersion.equals(slot.getVersion())) throw new PlanFault("VERSION_CONFLICT");
            jdbc.update("UPDATE plan_slots SET locked=?, version=version+1, updated_at=CURRENT_TIMESTAMP,"
                    + " author_id=? WHERE id=?", locked, actor, slot.getId());
            return get(home, date, meal);
        });
    }

    List<PlanSlot> commit(UUID actor, UUID home, UUID key, byte[] digest, PlanCommitRequest body) {
        requireMember(actor, home);
        if (body == null || body.getSlots() == null || body.getSlots().isEmpty() || body.getSlots().size() > 42) {
            throw new PlanFault("INVALID_PLAN");
        }
        for (PlanSlotWrite write : body.getSlots()) {
            if (write == null || write.getDate() == null || write.getMealType() == null
                    || write.getExpectedVersion() == null) throw new PlanFault("INVALID_PLAN");
            validate(home, write.getItems());
        }
        IdempotencyExecutor.Result result = executor.execute(actor, key, digest, () -> {
            List<PlanSlot> saved = transactions.execute(status -> {
                List<PlanSlot> resultSlots = new ArrayList<>();
                for (PlanSlotWrite write : body.getSlots()) {
                    resultSlots.add(applyWrite(actor, home, write));
                }
                return resultSlots;
            });
            return new IdempotencyExecutor.Result(200, writeJson(saved));
        }, ignored -> { });
        return readJson(result.body);
    }

    private PlanSlot applyWrite(UUID actor, UUID home, PlanSlotWrite write) {
        LocalDate date = date(write.getDate());
        List<PlanItem> rows = validate(home, write.getItems());
        List<PlanSlot> existing = slotRow(home, date, write.getMealType(), true);
        if (!existing.isEmpty() && existing.get(0).getLocked()) {
            // Generated plans never overwrite a slot the household locked.
            return get(home, date, write.getMealType());
        }
        if (existing.isEmpty()) {
            if (write.getExpectedVersion() != 0) throw new PlanFault("VERSION_CONFLICT");
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO plan_slots(id, household_id, date, meal_type, locked, author_id)"
                            + " VALUES (?,?,?,?,false,?)",
                    id, home, date, write.getMealType().getValue(), actor);
            writeItems(id, home, rows);
            return get(home, date, write.getMealType());
        }
        PlanSlot slot = existing.get(0);
        if (!write.getExpectedVersion().equals(slot.getVersion())) throw new PlanFault("VERSION_CONFLICT");
        jdbc.update("UPDATE plan_slots SET version=version+1, updated_at=CURRENT_TIMESTAMP, author_id=?"
                + " WHERE id=?", actor, slot.getId());
        jdbc.update("DELETE FROM plan_items WHERE slot_id=?", slot.getId());
        writeItems(slot.getId(), home, rows);
        return get(home, date, write.getMealType());
    }

    private PlanSlot get(UUID home, LocalDate date, PlanMealType meal) {
        List<PlanSlot> rows = jdbc.query("SELECT s.id, s.household_id, s.date, s.meal_type, s.locked, s.version,"
                + " s.created_at, s.updated_at, s.author_id, u.display_name AS last_editor_name"
                + " FROM plan_slots s LEFT JOIN users u ON u.id=s.author_id"
                + " WHERE s.household_id=? AND s.date=? AND s.meal_type=?",
                (rs, row) -> map(rs), home, date, meal.getValue());
        if (rows.isEmpty()) throw new PlanFault("NOT_FOUND");
        return rows.get(0);
    }

    private List<PlanSlot> slotRow(UUID home, LocalDate date, PlanMealType meal, boolean lock) {
        return jdbc.query("SELECT s.id, s.household_id, s.date, s.meal_type, s.locked, s.version,"
                        + " s.created_at, s.updated_at, s.author_id, u.display_name AS last_editor_name"
                        + " FROM plan_slots s LEFT JOIN users u ON u.id=s.author_id"
                        + " WHERE s.household_id=? AND s.date=? AND s.meal_type=?"
                        + (lock ? " FOR UPDATE OF s" : ""),
                (rs, row) -> map(rs), home, date, meal.getValue());
    }

    private void writeItems(UUID slotId, UUID home, List<PlanItem> items) {
        int position = 0;
        for (PlanItem item : items) {
            jdbc.update("INSERT INTO plan_items(slot_id, household_id, recipe_id, position, servings)"
                            + " VALUES (?,?,?,?,?)",
                    slotId, home, item.getRecipeId(), position++, new BigDecimal(item.getServings()));
        }
    }

    private List<PlanItem> validate(UUID home, List<PlanItem> items) {
        if (items == null || items.size() > 10) throw new PlanFault("INVALID_PLAN");
        List<PlanItem> result = new ArrayList<>();
        java.util.Set<UUID> seen = new java.util.LinkedHashSet<>();
        for (PlanItem item : items) {
            if (item == null || item.getRecipeId() == null || item.getServings() == null) {
                throw new PlanFault("INVALID_PLAN");
            }
            BigDecimal servings;
            try {
                servings = new BigDecimal(item.getServings());
            } catch (NumberFormatException invalid) {
                throw new PlanFault("INVALID_PLAN");
            }
            if (servings.signum() <= 0 || !seen.add(item.getRecipeId())) throw new PlanFault("INVALID_PLAN");
            Integer count = jdbc.queryForObject("SELECT count(*) FROM recipes WHERE household_id=? AND id=?"
                    + " AND NOT archived", Integer.class, home, item.getRecipeId());
            if (count == null || count == 0) throw new PlanFault("INVALID_PLAN");
            result.add(new PlanItem().recipeId(item.getRecipeId())
                    .servings(servings.stripTrailingZeros().toPlainString()));
        }
        return result;
    }

    private PlanSlot map(ResultSet rs) throws SQLException {
        PlanSlot slot = new PlanSlot();
        slot.setId(rs.getObject("id", UUID.class));
        slot.setCreatedAt(ISO.format(rs.getTimestamp("created_at").toInstant()));
        slot.setUpdatedAt(ISO.format(rs.getTimestamp("updated_at").toInstant()));
        slot.setHouseholdId(rs.getObject("household_id", UUID.class));
        slot.setDate(rs.getObject("date", LocalDate.class).toString());
        slot.setMealType(PlanMealType.fromValue(rs.getString("meal_type")));
        slot.setLocked(rs.getBoolean("locked"));
        slot.setVersion(rs.getLong("version"));
        slot.setLastEditorId(rs.getObject("author_id", UUID.class));
        slot.setLastEditorName(rs.getString("last_editor_name"));
        UUID slotId = slot.getId();
        List<PlanItem> items = jdbc.query("SELECT recipe_id, servings FROM plan_items WHERE slot_id=?"
                        + " ORDER BY position", (row, n) -> new PlanItem()
                        .recipeId(row.getObject("recipe_id", UUID.class))
                        .servings(number(row.getBigDecimal("servings"))), slotId);
        slot.setItems(items);
        return slot;
    }

    private void requireMember(UUID actor, UUID home) {
        Boolean member = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships"
                + " WHERE household_id=? AND user_id=? AND active)", Boolean.class, home, actor);
        if (!Boolean.TRUE.equals(member)) throw new PlanFault("NOT_FOUND");
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) {
            throw new PlanFault("INVALID_PLAN");
        }
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private String writeJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot serialise plan", invalid);
        }
    }

    private List<PlanSlot> readJson(String value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readerForListOf(PlanSlot.class).readValue(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot read stored plan", invalid);
        }
    }
}
