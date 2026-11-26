package edu.xjtlu.civa.server.record;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.model.ConfirmCookingRequest;
import edu.xjtlu.civa.server.api.model.CookingAllocation;
import edu.xjtlu.civa.server.api.model.CookingEvent;
import edu.xjtlu.civa.server.api.model.CookingEventPage;
import edu.xjtlu.civa.server.api.model.CookingPreview;
import edu.xjtlu.civa.server.api.model.CookingPreviewRequest;
import edu.xjtlu.civa.server.api.model.RecipeIngredient;
import edu.xjtlu.civa.server.api.model.RecipeSnapshot;
import edu.xjtlu.civa.server.api.model.Unit;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import edu.xjtlu.civa.server.config.JsonbCodec;
import edu.xjtlu.civa.server.inventory.InventoryMatchingService;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Cooking confirmation: one atomic household event that deducts inventory batches exactly once. */
final class CookingEventService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String SELECT = "SELECT e.id, e.created_at, e.updated_at, e.household_id, e.actor_id,"
            + " e.cooked_at, e.servings, e.recipe_id, e.recipe_snapshot, e.plan_slot_id, e.request_id"
            + " FROM cooking_events e";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonbCodec jsonb;
    private final IdempotencyExecutor executor;
    private final InventoryMatchingService matching;
    private final Clock clock;

    CookingEventService(JdbcTemplate jdbc, ObjectMapper json, JsonbCodec jsonb, IdempotencyExecutor executor,
                        InventoryMatchingService matching, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.jsonb = jsonb;
        this.executor = executor;
        this.matching = matching;
        this.clock = clock;
    }

    CookingPreview preview(UUID actor, UUID home, CookingPreviewRequest body) {
        if (body == null || body.getRecipeId() == null) throw new RecordFault("INVALID_RECIPE");
        requireMember(actor, home);
        return matching.preview(actor, home, body.getRecipeId(), body.getServings());
    }

    IdempotencyExecutor.Result confirm(UUID actor, UUID home, UUID key, byte[] digest,
                                       ConfirmCookingRequest body) {
        if (body == null || body.getRecipeId() == null || body.getRecipeVersion() == null
                || body.getServings() == null || body.getCookedAt() == null || body.getAllocations() == null) {
            throw new RecordFault("INVALID_RANGE");
        }
        BigDecimal servings = positive(body.getServings());
        OffsetDateTime cookedAt = parseInstant(body.getCookedAt());
        requireMember(actor, home);
        return executor.execute(actor, key, digest, () -> {
            String zone = lockHousehold(home);
            try {
                LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(zone)));
                RecipeRow recipe = recipe(home, body.getRecipeId());
                if (recipe.version != body.getRecipeVersion()) throw new RecordFault("VERSION_CONFLICT");
                List<RecipeIngredient> snapshotIngredients = ingredients(recipe.id);
                List<CookingAllocation> allocations = normalise(body.getAllocations(), snapshotIngredients);
                requireCompleteAllocations(recipe, snapshotIngredients, allocations, servings);
                UUID eventId = UUID.randomUUID();
                jdbc.update("INSERT INTO cooking_events(id, household_id, actor_id, cooked_at, servings,"
                                + " recipe_id, recipe_snapshot, plan_slot_id, request_id)"
                                + " VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?)",
                        eventId, home, actor, cookedAt, servings, recipe.id,
                        jsonb.write(json.valueToTree(snapshot(recipe, snapshotIngredients))), body.getPlanSlotId(), key);
                Map<UUID, Allocation> perBatch = new LinkedHashMap<>();
                for (CookingAllocation allocation : allocations) {
                    Allocation current = perBatch.get(allocation.getBatchId());
                    if (current == null) {
                        perBatch.put(allocation.getBatchId(), new Allocation(allocation.getExpectedVersion(),
                                new BigDecimal(allocation.getQuantity()), allocation.getIngredientId(),
                                allocation.getUnit()));
                        continue;
                    }
                    if (!current.expectedVersion.equals(allocation.getExpectedVersion())) {
                        throw new RecordFault("VERSION_CONFLICT");
                    }
                    if (!current.ingredientId.equals(allocation.getIngredientId())
                            || current.unit != allocation.getUnit()) {
                        throw new RecordFault("INVALID_INGREDIENT");
                    }
                    current.quantity = current.quantity.add(new BigDecimal(allocation.getQuantity()));
                }
                for (Map.Entry<UUID, Allocation> entry : perBatch.entrySet()) {
                    deduct(home, actor, eventId, key, entry.getKey(), entry.getValue(), today);
                }
                return new IdempotencyExecutor.Result(201, jsonb.write(json.valueToTree(get(home, eventId))));
            } finally {
                jdbc.execute("SET LOCAL lock_timeout = DEFAULT");
            }
        }, ignored -> requireMember(actor, home));
    }

    CookingEventPage list(UUID actor, UUID home, UUID recipeId, String from, String to, Integer page, Integer size) {
        requireMember(actor, home);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) throw new RecordFault("INVALID_RANGE");
        LocalDate start = date(from);
        LocalDate end = date(to);
        if (start != null && end != null && start.isAfter(end)) throw new RecordFault("INVALID_RANGE");
        StringBuilder where = new StringBuilder(" WHERE e.household_id=?");
        List<Object> params = new ArrayList<>();
        params.add(home);
        if (recipeId != null) {
            where.append(" AND e.recipe_id=?");
            params.add(recipeId);
        }
        if (start != null) {
            where.append(" AND e.cooked_at >= ?");
            params.add(start.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
        }
        if (end != null) {
            where.append(" AND e.cooked_at < ?");
            params.add(end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM cooking_events e" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pageSize);
        pageParams.add((long) pageNumber * pageSize);
        List<CookingEvent> items = jdbc.query(SELECT + where + " ORDER BY e.cooked_at DESC, e.id DESC LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), pageParams.toArray());
        return new CookingEventPage(items, pageNumber, pageSize, total == null ? 0 : total);
    }

    private void deduct(UUID home, UUID actor, UUID eventId, UUID key, UUID batchId, Allocation allocation,
                        LocalDate today) {
        BigDecimal quantity = allocation.quantity;
        List<BatchRow> batches = jdbc.query("SELECT id, ingredient_id, unit, quantity, version, expires_on"
                        + " FROM inventory_batches WHERE household_id=? AND id=? FOR UPDATE",
                (rs, row) -> new BatchRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getBigDecimal(4), rs.getLong(5),
                        rs.getObject(6, LocalDate.class)),
                home, batchId);
        if (batches.isEmpty()) throw new RecordFault("INSUFFICIENT_STOCK");
        BatchRow batch = batches.get(0);
        // The batch must really be the ingredient the caller says it is: a wrong batch id must not
        // consume another ingredient's stock.
        if (!batch.ingredientId.equals(allocation.ingredientId)
                || !batch.unit.equals(allocation.unit.getValue())) {
            throw new RecordFault("INVALID_INGREDIENT");
        }
        if (allocation.expectedVersion != batch.version) throw new RecordFault("VERSION_CONFLICT");
        // Same rule as the preview: an expiry date equal to today is still usable.
        if (batch.expiresOn != null && batch.expiresOn.isBefore(today)) throw new RecordFault("EXPIRED_BATCH");
        BigDecimal before = batch.quantity;
        if (before.compareTo(quantity) < 0) throw new RecordFault("INSUFFICIENT_STOCK");
        BigDecimal after = before.subtract(quantity);
        jdbc.update("UPDATE inventory_batches SET quantity=?, version=version+1, updated_at=CURRENT_TIMESTAMP"
                + " WHERE household_id=? AND id=?", after, home, batchId);
        jdbc.update("INSERT INTO inventory_movements(id, household_id, batch_id, before_quantity, after_quantity,"
                        + " reason, actor_id, cooking_event_id, request_id) VALUES (?,?,?,?,?,'COOK',?,?,?)",
                UUID.randomUUID(), home, batchId, before, after, actor, eventId, key);
    }

    /**
     * The confirmation may only deduct what the recipe actually consumes: every stock-tracked line
     * must be covered exactly once, and untracked lines must not be deducted. A recipe with no
     * stock-tracked lines is legitimately confirmed with an empty allocation list.
     */
    private void requireCompleteAllocations(RecipeRow recipe, List<RecipeIngredient> ingredients,
                                            List<CookingAllocation> allocations, BigDecimal servings) {
        BigDecimal yield = new BigDecimal(recipe.yieldServings());
        Map<UUID, BigDecimal> allocated = new LinkedHashMap<>();
        for (CookingAllocation allocation : allocations) {
            allocated.merge(allocation.getIngredientId(), new BigDecimal(allocation.getQuantity()), BigDecimal::add);
        }
        Set<UUID> tracked = new LinkedHashSet<>();
        for (RecipeIngredient ingredient : ingredients) {
            UUID ingredientId = ingredient.getIngredientId();
            if (!Boolean.TRUE.equals(ingredient.getTrackStock())) {
                if (allocated.containsKey(ingredientId)) throw new RecordFault("INVALID_INGREDIENT");
                continue;
            }
            tracked.add(ingredientId);
            BigDecimal got = allocated.get(ingredientId);
            if (ingredient.getQuantity() == null) throw new RecordFault("INVALID_INGREDIENT");
            BigDecimal required = InventoryMatchingService.requiredFor(ingredient, servings, yield);
            if (required.signum() == 0) {
                // A tracked line of zero grams still needs no batch and must not consume stock.
                if (got != null) throw new RecordFault("INVALID_INGREDIENT");
            } else if (got == null || got.compareTo(required) != 0) {
                throw new RecordFault("INVALID_RANGE");
            }
        }
        for (UUID ingredientId : allocated.keySet()) {
            if (!tracked.contains(ingredientId)) throw new RecordFault("INVALID_INGREDIENT");
        }
    }

    private List<CookingAllocation> normalise(List<CookingAllocation> allocations,
                                              List<RecipeIngredient> recipeIngredients) {
        Map<UUID, RecipeIngredient> byIngredient = new LinkedHashMap<>();
        for (RecipeIngredient ingredient : recipeIngredients) {
            byIngredient.put(ingredient.getIngredientId(), ingredient);
        }
        List<CookingAllocation> result = new ArrayList<>();
        for (CookingAllocation allocation : allocations) {
            if (allocation == null || allocation.getBatchId() == null || allocation.getIngredientId() == null
                    || allocation.getQuantity() == null || allocation.getUnit() == null
                    || allocation.getExpectedVersion() == null) {
                throw new RecordFault("INVALID_RANGE");
            }
            BigDecimal quantity = positive(allocation.getQuantity());
            RecipeIngredient ingredient = byIngredient.get(allocation.getIngredientId());
            if (ingredient == null || ingredient.getUnit() != allocation.getUnit()) {
                throw new RecordFault("INVALID_INGREDIENT");
            }
            result.add(new CookingAllocation().batchId(allocation.getBatchId())
                    .expectedVersion(allocation.getExpectedVersion())
                    .ingredientId(allocation.getIngredientId())
                    .quantity(quantity.stripTrailingZeros().toPlainString())
                    .unit(allocation.getUnit()));
        }
        return result;
    }

    private RecipeSnapshot snapshot(RecipeRow recipe, List<RecipeIngredient> ingredients) {
        return new RecipeSnapshot().recipeId(recipe.id).recipeName(recipe.name)
                .recipeVersion(recipe.version).yieldServings(recipe.yieldServings)
                .totalKcal(recipe.totalKcal).ingredients(ingredients);
    }

    private RecipeRow recipe(UUID home, UUID id) {
        List<RecipeRow> rows = jdbc.query("SELECT id, version, name, yield_servings, total_kcal FROM recipes"
                        + " WHERE household_id=? AND id=? AND NOT archived",
                (rs, row) -> new RecipeRow(rs.getObject("id", UUID.class), rs.getLong("version"),
                        rs.getString("name"), number(rs.getBigDecimal("yield_servings")),
                        number(rs.getBigDecimal("total_kcal"))), home, id);
        if (rows.isEmpty()) throw new RecordFault("NOT_FOUND");
        return rows.get(0);
    }

    private List<RecipeIngredient> ingredients(UUID recipeId) {
        return jdbc.query("SELECT ingredient_id, quantity, unit, track_stock FROM recipe_ingredients"
                        + " WHERE recipe_id=? ORDER BY position, ingredient_id",
                (rs, row) -> new RecipeIngredient().ingredientId(rs.getObject(1, UUID.class))
                        .quantity(number(rs.getBigDecimal(2)))
                        .unit(Unit.fromValue(rs.getString(3)))
                        .trackStock(rs.getBoolean(4)), recipeId);
    }

    private CookingEvent get(UUID home, UUID id) {
        List<CookingEvent> rows = jdbc.query(SELECT + " WHERE e.household_id=? AND e.id=?",
                (rs, row) -> map(rs), home, id);
        if (rows.isEmpty()) throw new RecordFault("NOT_FOUND");
        return rows.get(0);
    }

    private CookingEvent map(ResultSet rs) throws SQLException {
        RecipeSnapshot recipe;
        try {
            recipe = json.readValue(rs.getString("recipe_snapshot"), RecipeSnapshot.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Corrupt cooking snapshot", invalid);
        }
        CookingEvent event = new CookingEvent();
        event.setId(rs.getObject("id", UUID.class));
        event.setCreatedAt(ISO.format(rs.getTimestamp("created_at").toInstant()));
        event.setUpdatedAt(ISO.format(rs.getTimestamp("updated_at").toInstant()));
        event.setHouseholdId(rs.getObject("household_id", UUID.class));
        event.setActorId(rs.getObject("actor_id", UUID.class));
        event.setCookedAt(ISO.format(rs.getTimestamp("cooked_at").toInstant()));
        event.setServings(number(rs.getBigDecimal("servings")));
        event.setRecipe(recipe);
        event.setPlanSlotId(rs.getObject("plan_slot_id", UUID.class));
        event.setRequestId(rs.getObject("request_id", UUID.class));
        return event;
    }

    /** Locks the household row and returns its zone, so "today" matches the household calendar. */
    private String lockHousehold(UUID home) {
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        List<String> zones = jdbc.query("SELECT zone_id FROM households WHERE id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), home);
        if (zones.isEmpty()) throw new RecordFault("NOT_FOUND");
        return zones.get(0);
    }

    private void requireMember(UUID actor, UUID home) {
        Boolean member = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships"
                + " WHERE household_id=? AND user_id=? AND active)", Boolean.class, home, actor);
        if (!Boolean.TRUE.equals(member)) throw new RecordFault("NOT_FOUND");
    }

    private static BigDecimal positive(String value) {
        try {
            BigDecimal parsed = new BigDecimal(value);
            if (parsed.signum() <= 0) throw new RecordFault("INVALID_RANGE");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new RecordFault("INVALID_RANGE");
        }
    }

    private static OffsetDateTime parseInstant(String value) {
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (RuntimeException invalid) {
            throw new RecordFault("INVALID_RANGE");
        }
    }

    private static LocalDate date(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) {
            throw new RecordFault("INVALID_RANGE");
        }
    }

    private static String number(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private record RecipeRow(UUID id, long version, String name, String yieldServings, String totalKcal) { }

    private record BatchRow(UUID id, UUID ingredientId, String unit, BigDecimal quantity, long version,
                            LocalDate expiresOn) { }

    private static final class Allocation {
        final Long expectedVersion;
        final UUID ingredientId;
        final Unit unit;
        BigDecimal quantity;

        Allocation(Long expectedVersion, BigDecimal quantity, UUID ingredientId, Unit unit) {
            this.expectedVersion = expectedVersion;
            this.quantity = quantity;
            this.ingredientId = ingredientId;
            this.unit = unit;
        }
    }
}
