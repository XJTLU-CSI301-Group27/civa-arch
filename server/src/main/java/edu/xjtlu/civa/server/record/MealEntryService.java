package edu.xjtlu.civa.server.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.model.CreateMealEntryRequest;
import edu.xjtlu.civa.server.api.model.DaySummary;
import edu.xjtlu.civa.server.api.model.EntryMealType;
import edu.xjtlu.civa.server.api.model.KcalBasis;
import edu.xjtlu.civa.server.api.model.MealEntry;
import edu.xjtlu.civa.server.api.model.MealEntryPage;
import edu.xjtlu.civa.server.api.model.PeriodSummary;
import edu.xjtlu.civa.server.api.model.UpdateMealEntryRequest;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Personal food-diary entries: the only source of actual intake and kcal totals. */
final class MealEntryService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String SELECT = "SELECT e.id, e.created_at, e.updated_at, e.user_id, e.date, e.zone_id,"
            + " e.meal_type, e.name, e.recipe_id, e.cooking_event_id, e.amount, e.basis, e.basis_kcal, e.total_kcal,"
            + " e.note, e.image_id, e.version FROM meal_entries e";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdempotencyExecutor executor;

    MealEntryService(JdbcTemplate jdbc, ObjectMapper json, IdempotencyExecutor executor) {
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
    }

    MealEntryPage list(UUID actor, LocalDate from, LocalDate to, Integer page, Integer size) {
        Range range = range(from, to);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) throw new RecordFault("INVALID_RANGE");
        Long total = jdbc.queryForObject("SELECT count(*) FROM meal_entries WHERE user_id=? AND date BETWEEN ? AND ?",
                Long.class, actor, range.from, range.to);
        List<MealEntry> items = jdbc.query(SELECT + " WHERE e.user_id=? AND e.date BETWEEN ? AND ?"
                        + " ORDER BY e.date DESC, e.created_at DESC, e.id LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), actor, range.from, range.to, pageSize, pageNumber * pageSize);
        return new MealEntryPage(items, pageNumber, pageSize, total == null ? 0 : total);
    }

    MealEntry get(UUID actor, UUID id) {
        List<MealEntry> rows = jdbc.query(SELECT + " WHERE e.id=? AND e.user_id=?", (rs, row) -> map(rs), id, actor);
        if (rows.isEmpty()) throw new RecordFault("NOT_FOUND");
        return rows.get(0);
    }

    IdempotencyExecutor.Result create(UUID actor, UUID key, byte[] digest, CreateMealEntryRequest body) {
        Validated value = validate(body.getDate(), body.getMealType(), body.getName(), body.getAmount(),
                body.getBasis(), body.getBasisKcal(), body.getNote(), body.getRecipeId(),
                body.getCookingEventId(), body.getImageId(), actor);
        return executor.execute(actor, key, digest, () -> {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO meal_entries(id, user_id, date, zone_id, meal_type, name, recipe_id,"
                            + " cooking_event_id, amount, basis, basis_kcal, total_kcal, note, image_id)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, actor, value.date, zone(actor), value.mealType.getValue(), value.name, value.recipeId,
                    value.cookingEventId, value.amount, value.basis.getValue(), value.basisKcal,
                    value.totalKcal, value.note, value.imageId);
            return new IdempotencyExecutor.Result(201, writeModel(get(actor, id)));
        }, stored -> requireStoredOwner(actor, stored));
    }

    /**
     * A meal entry is personal data, so leaving a household must not revoke it. What a replay must
     * never do is hand one account's stored response to another: verify the stored body still
     * belongs to the authenticated actor.
     */
    private void requireStoredOwner(UUID actor, IdempotencyExecutor.Result stored) {
        if (stored == null || stored.body == null) throw new RecordFault("NOT_FOUND");
        String owner;
        try {
            owner = json.readTree(stored.body).path("userId").asText(null);
        } catch (JsonProcessingException corrupt) {
            throw new RecordFault("NOT_FOUND");
        }
        if (owner == null || !owner.equals(actor.toString())) throw new RecordFault("NOT_FOUND");
    }

    MealEntry update(UUID actor, UUID id, UpdateMealEntryRequest body) {
        if (body.getExpectedVersion() == null) throw new RecordFault("INVALID_RANGE");
        Validated value = validate(body.getDate(), body.getMealType(), body.getName(), body.getAmount(),
                body.getBasis(), body.getBasisKcal(), body.getNote(), body.getRecipeId(),
                body.getCookingEventId(), body.getImageId(), actor);
        int updated = jdbc.update("UPDATE meal_entries SET date=?, zone_id=?, meal_type=?, name=?, recipe_id=?,"
                        + " cooking_event_id=?, amount=?, basis=?, basis_kcal=?, total_kcal=?, note=?, image_id=?,"
                        + " version=version+1, updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=? AND user_id=? AND version=?",
                value.date, zone(actor), value.mealType.getValue(), value.name, value.recipeId,
                value.cookingEventId, value.amount, value.basis.getValue(), value.basisKcal,
                value.totalKcal, value.note, value.imageId, id, actor, body.getExpectedVersion());
        if (updated == 0) throw new RecordFault(exists(actor, id) ? "VERSION_CONFLICT" : "NOT_FOUND");
        return get(actor, id);
    }

    void delete(UUID actor, UUID id, Long expectedVersion) {
        if (expectedVersion == null) throw new RecordFault("INVALID_RANGE");
        int deleted = jdbc.update("DELETE FROM meal_entries WHERE id=? AND user_id=? AND version=?",
                id, actor, expectedVersion);
        if (deleted == 0) throw new RecordFault(exists(actor, id) ? "VERSION_CONFLICT" : "NOT_FOUND");
    }

    PeriodSummary summary(UUID actor, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        List<DaySummary> days = new ArrayList<>();
        BigDecimal known = BigDecimal.ZERO;
        int unknown = 0;
        int daysWithKnown = 0;
        List<Object[]> rows = jdbc.query("SELECT date, coalesce(sum(total_kcal),0),"
                        + " count(*) FILTER (WHERE total_kcal IS NULL), count(*)"
                        + " FROM meal_entries WHERE user_id=? AND date BETWEEN ? AND ?"
                        + " GROUP BY date ORDER BY date", (rs, row) -> new Object[]{rs.getObject(1, LocalDate.class),
                        rs.getBigDecimal(2), rs.getInt(3), rs.getInt(4)}, actor, range.from, range.to);
        for (Object[] row : rows) {
            LocalDate date = (LocalDate) row[0];
            BigDecimal dayKnown = (BigDecimal) row[1];
            int dayUnknown = (Integer) row[2];
            int dayEntries = (Integer) row[3];
            DaySummary day = new DaySummary();
            day.setDate(date.toString());
            day.setKnownKcal(knownText(dayKnown));
            day.setUnknownCount(dayUnknown);
            day.setEntryCount(dayEntries);
            days.add(day);
            known = known.add(dayKnown);
            unknown += dayUnknown;
            if (dayKnown.signum() > 0) daysWithKnown++;
        }
        PeriodSummary result = new PeriodSummary();
        result.setFrom(range.from.toString());
        result.setTo(range.to.toString());
        result.setKnownKcal(knownText(known));
        result.setUnknownCount(unknown);
        result.setAverageKnownKcal(daysWithKnown == 0 ? "0"
                : knownText(known.divide(BigDecimal.valueOf(daysWithKnown), 3, RoundingMode.HALF_UP)));
        result.setDays(days);
        return result;
    }

    private Validated validate(String dateText, EntryMealType mealType, String name, String amountText,
                               KcalBasis basis, String basisKcalText, String note, UUID recipeId,
                               UUID cookingEventId, UUID imageId, UUID actor) {
        LocalDate date = parseDate(dateText);
        if (mealType == null) throw new RecordFault("INVALID_RANGE");
        String title = name == null ? "" : name.trim();
        if (title.isEmpty() || title.length() > 120) throw new RecordFault("INVALID_RANGE");
        BigDecimal amount = decimal(amountText);
        if (amount == null || amount.signum() <= 0) throw new RecordFault("INVALID_RANGE");
        if (basis == null) throw new RecordFault("INVALID_RANGE");
        // Mirrors the meal_entries CHECK constraints: UNKNOWN means no estimate, every other basis
        // must carry a non-negative estimate, and MANUAL entries describe one whole portion.
        BigDecimal basisKcal = decimal(basisKcalText);
        if (basis == KcalBasis.UNKNOWN) {
            if (basisKcal != null) throw new RecordFault("INVALID_RANGE");
        } else if (basisKcal == null || basisKcal.signum() < 0) {
            throw new RecordFault("INVALID_RANGE");
        }
        if (basis == KcalBasis.MANUAL && amount.compareTo(BigDecimal.ONE) != 0) {
            throw new RecordFault("INVALID_RANGE");
        }
        String text = note == null ? "" : note;
        if (text.length() > 2000) throw new RecordFault("INVALID_RANGE");
        if (recipeId != null && !recipeVisible(actor, recipeId)) throw new RecordFault("INVALID_RECIPE");
        if (cookingEventId != null && !cookingVisible(actor, cookingEventId)) throw new RecordFault("INVALID_RECIPE");
        if (imageId != null) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM media WHERE id=? AND owner_user_id=?",
                    Integer.class, imageId, actor);
            if (count == null || count == 0) throw new RecordFault("INVALID_IMAGE");
        }
        BigDecimal totalKcal = totalKcal(basis, basisKcal, amount);
        Validated value = new Validated();
        value.date = date;
        value.mealType = mealType;
        value.name = title;
        value.amount = amount;
        value.basis = basis;
        value.basisKcal = basisKcal;
        value.totalKcal = totalKcal;
        value.note = text;
        value.recipeId = recipeId;
        value.cookingEventId = cookingEventId;
        value.imageId = imageId;
        return value;
    }

    /** One basis per entry: per-serving or per-unit scales by amount; per-100g scales by amount/100. */
    private static BigDecimal totalKcal(KcalBasis basis, BigDecimal basisKcal, BigDecimal amount) {
        if (basis == KcalBasis.UNKNOWN || basisKcal == null) return null;
        if (basis == KcalBasis.GRAM) {
            return basisKcal.multiply(amount).divide(BigDecimal.valueOf(100), 3, RoundingMode.HALF_UP);
        }
        return basisKcal.multiply(amount).setScale(3, RoundingMode.HALF_UP);
    }

    private boolean recipeVisible(UUID actor, UUID recipeId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM recipes r JOIN memberships m"
                + " ON m.household_id = r.household_id AND m.user_id = ? AND m.active WHERE r.id = ?",
                Integer.class, actor, recipeId);
        return count != null && count > 0;
    }

    private boolean cookingVisible(UUID actor, UUID eventId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM cooking_events c JOIN memberships m"
                + " ON m.household_id = c.household_id AND m.user_id = ? AND m.active WHERE c.id = ?",
                Integer.class, actor, eventId);
        return count != null && count > 0;
    }

    private boolean exists(UUID actor, UUID id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM meal_entries WHERE id=? AND user_id=?",
                Integer.class, id, actor);
        return count != null && count > 0;
    }

    private MealEntry map(ResultSet rs) throws SQLException {
        MealEntry entry = new MealEntry();
        entry.setId(rs.getObject("id", UUID.class));
        entry.setCreatedAt(ISO.format(rs.getTimestamp("created_at").toInstant()));
        entry.setUpdatedAt(ISO.format(rs.getTimestamp("updated_at").toInstant()));
        entry.setUserId(rs.getObject("user_id", UUID.class));
        entry.setDate(rs.getObject("date", LocalDate.class).toString());
        entry.setZoneId(rs.getString("zone_id"));
        entry.setMealType(EntryMealType.fromValue(rs.getString("meal_type")));
        entry.setName(rs.getString("name"));
        entry.setRecipeId(rs.getObject("recipe_id", UUID.class));
        entry.setCookingEventId(rs.getObject("cooking_event_id", UUID.class));
        entry.setAmount(plain(rs.getBigDecimal("amount")));
        entry.setBasis(KcalBasis.fromValue(rs.getString("basis")));
        entry.setBasisKcal(plain(rs.getBigDecimal("basis_kcal")));
        entry.setTotalKcal(plain(rs.getBigDecimal("total_kcal")));
        entry.setNote(rs.getString("note"));
        entry.setImageId(rs.getObject("image_id", UUID.class));
        entry.setVersion(rs.getLong("version"));
        return entry;
    }

    private String zone(UUID actor) {
        return jdbc.queryForObject("SELECT zone_id FROM users WHERE id=?", String.class, actor);
    }

    private Range range(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) throw new RecordFault("INVALID_RANGE");
        if (from.plusDays(731).isBefore(to)) throw new RecordFault("INVALID_RANGE");
        return new Range(from, to);
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) {
            throw new RecordFault("INVALID_RANGE");
        }
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException invalid) {
            throw new RecordFault("INVALID_RANGE");
        }
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String knownText(BigDecimal value) {
        return plain(value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    private String writeModel(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot serialise meal entry", invalid);
        }
    }

    private record Range(LocalDate from, LocalDate to) { }

    private static final class Validated {
        LocalDate date;
        EntryMealType mealType;
        String name;
        BigDecimal amount;
        KcalBasis basis;
        BigDecimal basisKcal;
        BigDecimal totalKcal;
        String note;
        UUID recipeId;
        UUID cookingEventId;
        UUID imageId;
    }
}
