package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.model.ArchiveRecipeRequest;
import edu.xjtlu.civa.server.api.model.CreateRecipeRequest;
import edu.xjtlu.civa.server.api.model.Recipe;
import edu.xjtlu.civa.server.api.model.RecipeIngredient;
import edu.xjtlu.civa.server.api.model.RecipeOnlyIngredient;
import edu.xjtlu.civa.server.api.model.RecipePage;
import edu.xjtlu.civa.server.api.model.Unit;
import edu.xjtlu.civa.server.api.model.UpdateRecipeRequest;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Household recipes (canonical ingredient ids) with the personal snapshot dual-write. */
final class HouseholdRecipeService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC);
    private static final String SELECT = "SELECT r.id, r.created_at, r.updated_at, r.household_id, r.author_id,"
            + " r.name, r.steps, r.yield_servings, r.duration_minutes, r.tags, r.image_id, r.total_kcal, r.archived,"
            + " r.recipe_only_ingredients, r.version FROM recipes r";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdempotencyExecutor executor;
    private final TransactionTemplate transactions;

    HouseholdRecipeService(JdbcTemplate jdbc, ObjectMapper json, IdempotencyExecutor executor,
                           PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
        this.transactions = new TransactionTemplate(manager);
    }

    RecipePage list(UUID actor, UUID home, String q, String tag, UUID ingredientId, Boolean archived,
                    Integer page, Integer size) {
        requireMember(actor, home);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid pagination");
        StringBuilder where = new StringBuilder(" WHERE r.household_id = ? AND r.archived = ?");
        List<Object> params = new ArrayList<>();
        params.add(home);
        params.add(Boolean.TRUE.equals(archived));
        if (q != null && !q.isBlank()) {
            where.append(" AND lower(r.name || ' ' || CAST(r.steps AS text)) LIKE ?");
            params.add("%" + q.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (tag != null && !tag.isBlank()) {
            where.append(" AND ? = ANY(r.tags)");
            params.add(tag.trim());
        }
        if (ingredientId != null) {
            where.append(" AND EXISTS (SELECT 1 FROM recipe_ingredients ri WHERE ri.recipe_id = r.id AND ri.ingredient_id = ?)");
            params.add(ingredientId);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM recipes r" + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pageSize);
        pageParams.add(pageNumber * pageSize);
        List<Recipe> items = jdbc.query(SELECT + where + " ORDER BY r.updated_at DESC, r.id LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), pageParams.toArray());
        for (Recipe recipe : items) attachIngredients(recipe);
        return new RecipePage(items, pageNumber, pageSize, total == null ? 0 : total);
    }

    Recipe get(UUID actor, UUID home, UUID id) {
        requireMember(actor, home);
        List<Recipe> rows = jdbc.query(SELECT + " WHERE r.id = ? AND r.household_id = ?", (rs, row) -> map(rs), id, home);
        if (rows.isEmpty()) throw new PersonalRecipeFault("NOT_FOUND");
        attachIngredients(rows.get(0));
        return rows.get(0);
    }

    IdempotencyExecutor.Result create(UUID actor, UUID home, UUID key, byte[] digest, CreateRecipeRequest body) {
        requireMember(actor, home);
        List<RecipeOnlyIngredient> recipeOnly = body.getRecipeOnlyIngredients() == null
                ? List.of() : body.getRecipeOnlyIngredients();
        validate(body.getName(), body.getSteps(), body.getIngredients(), recipeOnly,
                body.getYieldServings(), body.getDurationMinutes(), false);
        return executor.execute(actor, key, digest, () -> transactions.execute(status -> {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO recipes(id, household_id, author_id, name, steps, yield_servings, duration_minutes,"
                            + " tags, image_id, total_kcal, recipe_only_ingredients)"
                            + " VALUES (?,?,?,?,CAST(? AS jsonb),?,?,CAST(? AS TEXT[]),?,?,CAST(? AS jsonb))",
                    id, home, actor, body.getName().trim(), write(body.getSteps()), decimal(body.getYieldServings()),
                    body.getDurationMinutes(), textArray(body.getTags()), body.getImageId(),
                    decimal(body.getTotalKcal()), write(recipeOnly));
            insertIngredients(id, body.getIngredients());
            snapshot(id, actor, body, recipeOnly);
            return new IdempotencyExecutor.Result(201, writeModel(get(actor, home, id)));
        }), ignored -> { });
    }

    Recipe update(UUID actor, UUID home, UUID id, UpdateRecipeRequest body) {
        requireMember(actor, home);
        List<RecipeOnlyIngredient> recipeOnly = body.getRecipeOnlyIngredients();
        if (recipeOnly != null && recipeOnly.size() > 100) throw new IllegalArgumentException("Invalid recipe-only ingredients");
        boolean replacingRecipeOnly = recipeOnly != null;
        if (body.getIngredients() == null || body.getIngredients().isEmpty()) {
            boolean hasRecipeOnly = replacingRecipeOnly
                    ? !recipeOnly.isEmpty()
                    : existingRecipeOnly(home, id);
            if (!hasRecipeOnly) throw new IllegalArgumentException("Invalid ingredients");
        }
        validate(body.getName(), body.getSteps(), body.getIngredients(), recipeOnly,
                body.getYieldServings(), body.getDurationMinutes(), !replacingRecipeOnly);
        // The replacement is one logical edit: the header, the old ingredient rows and the new
        // ingredient rows must commit or roll back together, otherwise a rejected write (duplicate
        // or unknown ingredient) leaves the recipe without any ingredients.
        return transactions.execute(status -> {
            int updated = jdbc.update("UPDATE recipes SET name=?, steps=CAST(? AS jsonb), yield_servings=?, duration_minutes=?,"
                            + " tags=CAST(? AS TEXT[]), image_id=?, total_kcal=?, version=version+1, updated_at=CURRENT_TIMESTAMP"
                            + " WHERE id=? AND household_id=? AND version=?",
                    body.getName().trim(), write(body.getSteps()), decimal(body.getYieldServings()), body.getDurationMinutes(),
                    textArray(body.getTags()), body.getImageId(), decimal(body.getTotalKcal()), id, home, body.getExpectedVersion());
            if (updated == 0) throw new PersonalRecipeFault(exists(home, id) ? "VERSION_CONFLICT" : "NOT_FOUND");
            jdbc.update("DELETE FROM recipe_ingredients WHERE recipe_id=?", id);
            insertIngredients(id, body.getIngredients());
            if (replacingRecipeOnly) {
                jdbc.update("UPDATE recipes SET recipe_only_ingredients=CAST(? AS jsonb) WHERE id=? AND household_id=?",
                        write(recipeOnly), id, home);
            }
            return get(actor, home, id);
        });
    }

    Recipe archive(UUID actor, UUID home, UUID id, ArchiveRecipeRequest body) {
        requireMember(actor, home);
        int updated = jdbc.update("UPDATE recipes SET archived=?, version=version+1, updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=? AND household_id=? AND version=?",
                Boolean.TRUE.equals(body.getArchived()), id, home, body.getExpectedVersion());
        if (updated == 0) throw new PersonalRecipeFault(exists(home, id) ? "VERSION_CONFLICT" : "NOT_FOUND");
        return get(actor, home, id);
    }

    void addWishlist(UUID actor, UUID recipeId) {
        if (!visible(actor, recipeId)) throw new PersonalRecipeFault("NOT_FOUND");
        jdbc.update("INSERT INTO wishlists(user_id, recipe_id) VALUES (?,?) ON CONFLICT (user_id, recipe_id) DO NOTHING",
                actor, recipeId);
    }

    void removeWishlist(UUID actor, UUID recipeId) {
        jdbc.update("DELETE FROM wishlists WHERE user_id=? AND recipe_id=?", actor, recipeId);
    }

    RecipePage listWishlist(UUID actor, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid pagination");
        Long total = jdbc.queryForObject("SELECT count(*) FROM wishlists w JOIN recipes r ON r.id = w.recipe_id"
                + " JOIN memberships m ON m.household_id = r.household_id AND m.user_id = ? AND m.active"
                + " WHERE w.user_id = ?", Long.class, actor, actor);
        List<Recipe> items = jdbc.query(SELECT + " JOIN wishlists w ON w.recipe_id = r.id"
                        + " JOIN memberships m ON m.household_id = r.household_id AND m.user_id = ? AND m.active"
                        + " WHERE w.user_id = ? ORDER BY w.created_at DESC, r.id LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), actor, actor, pageSize, pageNumber * pageSize);
        for (Recipe recipe : items) attachIngredients(recipe);
        return new RecipePage(items, pageNumber, pageSize, total == null ? 0 : total);
    }

    private void snapshot(UUID recipeId, UUID actor, CreateRecipeRequest body,
                          List<RecipeOnlyIngredient> recipeOnly) {
        List<edu.xjtlu.civa.server.api.model.PersonalRecipeIngredient> ingredients = new ArrayList<>();
        if (body.getIngredients() != null) {
            for (RecipeIngredient item : body.getIngredients()) {
                String name = jdbc.queryForObject("SELECT name FROM ingredients WHERE id=?", String.class, item.getIngredientId());
                ingredients.add(new edu.xjtlu.civa.server.api.model.PersonalRecipeIngredient()
                        .name(name).quantity(item.getQuantity()).unit(item.getUnit().getValue()));
            }
        }
        // The personal snapshot has free-text ingredients; preserve recipe-only descriptions there
        // instead of inventing a quantity or dropping them.
        for (RecipeOnlyIngredient item : recipeOnly) {
            String amount = item.getAmountText();
            ingredients.add(new edu.xjtlu.civa.server.api.model.PersonalRecipeIngredient()
                    .name(item.getName())
                    .quantity(amount == null || amount.isBlank() ? null : amount)
                    .unit(null)
                    .note(item.getNote())
                    .sourceText(amount == null || amount.isBlank() ? null : amount));
        }
        jdbc.update("INSERT INTO user_recipes(id, user_id, name, description, steps, ingredients)"
                        + " VALUES (?,?,?,'',CAST(? AS jsonb),CAST(? AS jsonb))",
                UUID.randomUUID(), actor, body.getName().trim(), write(body.getSteps()), write(ingredients));
    }

    private void insertIngredients(UUID recipeId, List<RecipeIngredient> items) {
        int position = 0;
        if (items == null) return;
        for (RecipeIngredient item : items) {
            jdbc.update("INSERT INTO recipe_ingredients(recipe_id, ingredient_id, position, quantity, unit, track_stock)"
                            + " VALUES (?,?,?,?,?,?)",
                    recipeId, item.getIngredientId(), position++, new BigDecimal(item.getQuantity()),
                    item.getUnit().getValue(), Boolean.TRUE.equals(item.getTrackStock()));
        }
    }

    private Recipe map(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Recipe recipe = new Recipe(id, ISO.format(rs.getTimestamp("created_at").toInstant()),
                ISO.format(rs.getTimestamp("updated_at").toInstant()), rs.getObject("household_id", UUID.class),
                rs.getObject("author_id", UUID.class), rs.getString("name"),
                read(rs.getString("steps"), new TypeReference<List<String>>() { }), new ArrayList<>(),
                text(rs.getBigDecimal("yield_servings")), rs.getInt("duration_minutes"),
                new java.util.LinkedHashSet<>(array(rs, "tags")), rs.getObject("image_id", UUID.class),
                text(rs.getBigDecimal("total_kcal")), rs.getBoolean("archived"), rs.getLong("version"));
        recipe.setRecipeOnlyIngredients(readRecipeOnly(rs.getString("recipe_only_ingredients")));
        return recipe;
    }

    private List<RecipeOnlyIngredient> readRecipeOnly(String value) {
        if (value == null || value.isBlank()) return new ArrayList<>();
        List<RecipeOnlyIngredient> list = read(value, new TypeReference<List<RecipeOnlyIngredient>>() { });
        return list == null ? new ArrayList<>() : list;
    }

    private void attachIngredients(Recipe recipe) {
        List<RecipeIngredient> ingredients = new ArrayList<>();
        jdbc.query("SELECT ingredient_id, quantity, unit, track_stock FROM recipe_ingredients WHERE recipe_id=?"
                + " ORDER BY position", rs2 -> {
            ingredients.add(new RecipeIngredient(rs2.getObject(1, UUID.class), text(rs2.getBigDecimal(2)),
                    Unit.fromValue(rs2.getString(3)), rs2.getBoolean(4)));
        }, recipe.getId());
        recipe.setIngredients(ingredients);
    }

    private boolean exists(UUID home, UUID id) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM recipes WHERE id=? AND household_id=?",
                Integer.class, id, home);
        return count != null && count > 0;
    }

    private boolean visible(UUID actor, UUID recipeId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM recipes r JOIN memberships m"
                + " ON m.household_id = r.household_id AND m.user_id = ? AND m.active WHERE r.id = ?",
                Integer.class, actor, recipeId);
        return count != null && count > 0;
    }

    private void requireMember(UUID actor, UUID home) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM memberships WHERE household_id=? AND user_id=?"
                + " AND active", Integer.class, home, actor);
        if (count == null || count == 0) throw new PersonalRecipeFault("NOT_FOUND");
    }

    private void validate(String name, List<String> steps, List<RecipeIngredient> ingredients,
                          List<RecipeOnlyIngredient> recipeOnly, String servings, Integer minutes,
                          boolean allowExistingOnly) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 120) throw new IllegalArgumentException("Invalid name");
        if (steps == null || steps.isEmpty() || steps.size() > 60) throw new IllegalArgumentException("Invalid steps");
        List<RecipeIngredient> tracked = ingredients == null ? List.of() : ingredients;
        if (tracked.size() > 100) throw new IllegalArgumentException("Invalid ingredients");
        for (RecipeIngredient item : tracked) {
            if (item.getIngredientId() == null || item.getUnit() == null || item.getQuantity() == null
                    || item.getTrackStock() == null) throw new IllegalArgumentException("Invalid ingredient");
        }
        if (recipeOnly != null) {
            if (recipeOnly.size() > 100) throw new IllegalArgumentException("Invalid recipe-only ingredients");
            for (RecipeOnlyIngredient item : recipeOnly) {
                if (item == null || item.getName() == null || item.getName().trim().isEmpty()
                        || item.getName().trim().codePointCount(0, item.getName().trim().length()) > 120
                        || (item.getAmountText() != null
                            && item.getAmountText().codePointCount(0, item.getAmountText().length()) > 200)
                        || (item.getNote() != null
                            && item.getNote().codePointCount(0, item.getNote().length()) > 500)) {
                    throw new IllegalArgumentException("Invalid recipe-only ingredient");
                }
            }
        }
        if (tracked.isEmpty() && (recipeOnly == null || recipeOnly.isEmpty()) && !allowExistingOnly) {
            throw new IllegalArgumentException("Invalid ingredients");
        }
        if (servings == null) throw new IllegalArgumentException("Missing yield servings");
        if (minutes == null || minutes < 1 || minutes > 1440) throw new IllegalArgumentException("Invalid duration");
    }

    private boolean existingRecipeOnly(UUID home, UUID id) {
        List<Integer> counts = jdbc.query(
                "SELECT jsonb_array_length(recipe_only_ingredients) FROM recipes WHERE id=? AND household_id=?",
                (rs, n) -> rs.getInt(1), id, home);
        return !counts.isEmpty() && counts.get(0) > 0;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid payload", invalid);
        }
    }

    private String writeModel(Object model) {
        try {
            return json.writeValueAsString(model);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot serialise recipe", invalid);
        }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Corrupt recipe payload", invalid);
        }
    }

    private static List<String> array(ResultSet rs, String column) throws SQLException {
        java.sql.Array value = rs.getArray(column);
        return value == null ? List.of() : List.of((String[]) value.getArray());
    }

    private static String textArray(java.util.Collection<String> values) {
        return "{" + String.join(",", values == null ? java.util.List.<String>of() : values) + "}";
    }

    private static String[] array(java.util.Collection<String> values) {
        return values == null ? new String[0] : values.toArray(new String[0]);
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
