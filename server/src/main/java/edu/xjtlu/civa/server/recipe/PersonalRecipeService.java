package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.model.ArchivePersonalRecipeRequest;
import edu.xjtlu.civa.server.api.model.CreatePersonalRecipeRequest;
import edu.xjtlu.civa.server.api.model.PersonalRecipe;
import edu.xjtlu.civa.server.api.model.PersonalRecipeIngredient;
import edu.xjtlu.civa.server.api.model.PersonalRecipePage;
import edu.xjtlu.civa.server.api.model.UpdatePersonalRecipeRequest;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Personal recipes owned by the user: free-text ingredients, optimistic version, idempotent create. */
final class PersonalRecipeService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String COLUMNS = "id, name, category, description, emoji, steps, ingredients, methods,"
            + " flavors, meal_types, estimated_minutes, servings, serving_size, kcal, protein, carbs, fat,"
            + " protein_per100g, carbs_per100g, fat_per100g, origin_household_recipe_id, image_id, archived,"
            + " version, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final IdempotencyExecutor executor;
    private final TransactionTemplate transactions;

    PersonalRecipeService(JdbcTemplate jdbc, ObjectMapper json, IdempotencyExecutor executor,
                          PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.executor = executor;
        this.transactions = new TransactionTemplate(manager);
    }

    PersonalRecipePage list(UUID actor, String q, String category, Boolean archived, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid pagination");
        StringBuilder where = new StringBuilder("WHERE user_id = ? AND archived = ?");
        List<Object> params = new ArrayList<>();
        params.add(actor);
        params.add(Boolean.TRUE.equals(archived));
        if (q != null && !q.isBlank()) {
            where.append(" AND lower(name || ' ' || description || ' ' || CAST(ingredients AS text)) LIKE ?");
            params.add("%" + q.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category = ?");
            params.add(category.trim());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM user_recipes " + where, Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pageSize);
        pageParams.add(pageNumber * pageSize);
        List<PersonalRecipe> items = jdbc.query("SELECT " + COLUMNS + " FROM user_recipes " + where
                        + " ORDER BY updated_at DESC, id LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), pageParams.toArray());
        return new PersonalRecipePage(items, pageNumber, pageSize, total == null ? 0 : total);
    }

    PersonalRecipe get(UUID actor, UUID id) {
        List<PersonalRecipe> rows = jdbc.query("SELECT " + COLUMNS + " FROM user_recipes WHERE id = ? AND user_id = ?",
                (rs, row) -> map(rs), id, actor);
        if (rows.isEmpty()) throw new PersonalRecipeFault("NOT_FOUND");
        return rows.get(0);
    }

    IdempotencyExecutor.Result create(UUID actor, UUID key, byte[] digest, CreatePersonalRecipeRequest body) {
        validate(body.getName(), body.getSteps(), body.getIngredients());
        validateImage(actor, body.getImageId());
        return executor.execute(actor, key, digest, () -> {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO user_recipes(id, user_id, name, category, description, emoji, steps, ingredients,"
                            + " methods, flavors, meal_types, estimated_minutes, servings, kcal, protein, carbs, fat,"
                            + " protein_per100g, carbs_per100g, fat_per100g, origin_household_recipe_id, image_id)"
                            + " VALUES (?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, actor, body.getName().trim(), safe(body.getCategory()), safe(body.getDescription()),
                    body.getEmoji(), write(body.getSteps()), write(body.getIngredients()),
                    array(body.getMethods()), array(body.getFlavors()), array(body.getMealTypes()),
                    body.getEstimatedMinutes(), body.getServings(), body.getKcal(), body.getProtein(),
                    body.getCarbs(), body.getFat(), body.getProteinPer100g(), body.getCarbsPer100g(),
                    body.getFatPer100g(), body.getOriginHouseholdRecipeId(), body.getImageId());
            return new IdempotencyExecutor.Result(201, writeModel(row(id, actor)));
        }, ignored -> { });
    }

    PersonalRecipe update(UUID actor, UUID id, UpdatePersonalRecipeRequest body) {
        validate(body.getName(), body.getSteps(), body.getIngredients());
        validateImage(actor, body.getImageId());
        int updated = jdbc.update("UPDATE user_recipes SET name=?, category=?, description=?, emoji=?,"
                        + " steps=CAST(? AS jsonb), ingredients=CAST(? AS jsonb), methods=?, flavors=?, meal_types=?,"
                        + " estimated_minutes=?, servings=?, kcal=?, protein=?, carbs=?, fat=?, protein_per100g=?,"
                        + " carbs_per100g=?, fat_per100g=?, image_id=?, version=version+1,"
                        + " updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND version=?",
                body.getName().trim(), safe(body.getCategory()), safe(body.getDescription()), body.getEmoji(),
                write(body.getSteps()), write(body.getIngredients()), array(body.getMethods()), array(body.getFlavors()),
                array(body.getMealTypes()), body.getEstimatedMinutes(), body.getServings(), body.getKcal(),
                body.getProtein(), body.getCarbs(), body.getFat(), body.getProteinPer100g(),
                body.getCarbsPer100g(), body.getFatPer100g(), body.getImageId(), id, actor,
                body.getExpectedVersion());
        return writeResult(actor, id, updated);
    }

    PersonalRecipe archive(UUID actor, UUID id, ArchivePersonalRecipeRequest body) {
        int updated = jdbc.update("UPDATE user_recipes SET archived=?, version=version+1, updated_at=CURRENT_TIMESTAMP"
                        + " WHERE id=? AND user_id=? AND version=?",
                Boolean.TRUE.equals(body.getArchived()), id, actor, body.getExpectedVersion());
        return writeResult(actor, id, updated);
    }

    void delete(UUID actor, UUID id) {
        transactions.executeWithoutResult(status -> {
            int deleted = jdbc.update("DELETE FROM user_recipes WHERE id=? AND user_id=?", id, actor);
            if (deleted == 0) throw new PersonalRecipeFault("NOT_FOUND");
            jdbc.update("DELETE FROM recipe_favorites WHERE user_id=? AND kind='PERSONAL' AND recipe_id=?", actor, id);
        });
    }

    private PersonalRecipe writeResult(UUID actor, UUID id, int updated) {
        if (updated == 0) {
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM user_recipes WHERE id=? AND user_id=?",
                    Integer.class, id, actor);
            throw new PersonalRecipeFault(exists != null && exists > 0 ? "VERSION_CONFLICT" : "NOT_FOUND");
        }
        return row(id, actor);
    }

    private PersonalRecipe row(UUID id, UUID actor) {
        return get(actor, id);
    }

    private void validate(String name, List<String> steps, List<PersonalRecipeIngredient> ingredients) {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 120) throw new IllegalArgumentException("Invalid name");
        if (steps == null || steps.isEmpty() || steps.size() > 60) throw new IllegalArgumentException("Invalid steps");
        for (String step : steps) {
            if (step == null || step.trim().isEmpty() || step.length() > 2000) throw new IllegalArgumentException("Invalid step");
        }
        if (ingredients == null || ingredients.isEmpty() || ingredients.size() > 100) {
            throw new IllegalArgumentException("Invalid ingredients");
        }
        for (PersonalRecipeIngredient item : ingredients) {
            if (item == null || item.getName() == null || item.getName().trim().isEmpty()
                    || item.getName().length() > 120) throw new IllegalArgumentException("Invalid ingredient");
        }
    }

    private void validateImage(UUID actor, UUID imageId) {
        if (imageId == null) return;
        List<UUID> owners = jdbc.query("SELECT owner_user_id FROM media WHERE id=?", (rs, row) ->
                rs.getObject(1, UUID.class), imageId);
        if (owners.isEmpty()) throw new PersonalRecipeFault("INVALID_IMAGE");
        if (actor.equals(owners.get(0))) return;
        Boolean shared = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM media m JOIN memberships mb"
                + " ON mb.household_id=m.household_id AND mb.user_id=? AND mb.active"
                + " WHERE m.id=? AND m.scope='HOUSEHOLD')", Boolean.class, actor, imageId);
        if (!Boolean.TRUE.equals(shared)) throw new PersonalRecipeFault("INVALID_IMAGE");
    }

    private PersonalRecipe map(ResultSet rs) throws SQLException {
        PersonalRecipe recipe = new PersonalRecipe(
                rs.getObject("id", UUID.class),
                ISO.format(rs.getTimestamp("created_at").toInstant()),
                ISO.format(rs.getTimestamp("updated_at").toInstant()),
                rs.getString("name"),
                read(rs.getString("steps"), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { }),
                read(rs.getString("ingredients"),
                        new com.fasterxml.jackson.core.type.TypeReference<List<PersonalRecipeIngredient>>() { }),
                rs.getBoolean("archived"),
                rs.getLong("version"));
        recipe.setCategory(rs.getString("category"));
        recipe.setDescription(rs.getString("description"));
        recipe.setEmoji(rs.getString("emoji"));
        recipe.setMethods(array(rs, "methods"));
        recipe.setFlavors(array(rs, "flavors"));
        recipe.setMealTypes(array(rs, "meal_types"));
        recipe.setEstimatedMinutes(rs.getObject("estimated_minutes", Integer.class));
        recipe.setServings(text(rs.getBigDecimal("servings")));
        recipe.setServingSize(rs.getString("serving_size"));
        recipe.setKcal(text(rs.getBigDecimal("kcal")));
        recipe.setProtein(text(rs.getBigDecimal("protein")));
        recipe.setCarbs(text(rs.getBigDecimal("carbs")));
        recipe.setFat(text(rs.getBigDecimal("fat")));
        recipe.setProteinPer100g(text(rs.getBigDecimal("protein_per100g")));
        recipe.setCarbsPer100g(text(rs.getBigDecimal("carbs_per100g")));
        recipe.setFatPer100g(text(rs.getBigDecimal("fat_per100g")));
        recipe.setOriginHouseholdRecipeId(rs.getObject("origin_household_recipe_id", UUID.class));
        recipe.setImageId(rs.getObject("image_id", UUID.class));
        return recipe;
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
            throw new IllegalStateException("Cannot serialise personal recipe", invalid);
        }
    }

    private <T> T read(String value, com.fasterxml.jackson.core.type.TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Corrupt personal recipe payload", invalid);
        }
    }

    private static List<String> array(ResultSet rs, String column) throws SQLException {
        java.sql.Array value = rs.getArray(column);
        if (value == null) return List.of();
        return List.of((String[]) value.getArray());
    }

    private static String[] array(List<String> values) {
        return values == null ? new String[0] : values.toArray(new String[0]);
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
