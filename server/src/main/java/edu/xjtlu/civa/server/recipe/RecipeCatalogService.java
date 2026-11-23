package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.model.CatalogRecipe;
import edu.xjtlu.civa.server.api.model.CatalogRecipeIngredient;
import edu.xjtlu.civa.server.api.model.CatalogRecipePage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only access to the merged public recipe catalogue. Public writes do not exist. */
final class RecipeCatalogService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    RecipeCatalogService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    CatalogRecipePage list(String q, String category, String tag, boolean highProtein, Integer page, Integer size) {
        RecipeCatalogQuery query = RecipeCatalogQuery.of(q, category, tag, highProtein, page, size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM recipe_catalog " + query.where(),
                Long.class, query.params().toArray());
        List<Object> params = new ArrayList<>(query.params());
        params.add(query.size());
        params.add(query.offset());
        List<CatalogRecipe> items = jdbc.query(
                "SELECT id, name, category, cuisine, emoji, description, steps, ingredients, methods, flavors,"
                        + " meal_types, estimated_minutes, servings, serving_size, kcal, protein, carbs, fat,"
                        + " protein_per100g, carbs_per100g, fat_per100g, image_key, source, source_url"
                        + " FROM recipe_catalog " + query.where() + " ORDER BY name, id LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), params.toArray());
        return new CatalogRecipePage(items, query.page(), query.size(), total == null ? 0 : total);
    }

    CatalogRecipe get(UUID id) {
        List<CatalogRecipe> rows = jdbc.query(
                "SELECT id, name, category, cuisine, emoji, description, steps, ingredients, methods, flavors,"
                        + " meal_types, estimated_minutes, servings, serving_size, kcal, protein, carbs, fat,"
                        + " protein_per100g, carbs_per100g, fat_per100g, image_key, source, source_url"
                        + " FROM recipe_catalog WHERE id = ?",
                (rs, row) -> map(rs), id);
        if (rows.isEmpty()) throw new RecipeCatalogNotFound("NOT_FOUND");
        return rows.get(0);
    }

    private CatalogRecipe map(ResultSet rs) throws SQLException {
        CatalogRecipe recipe = new CatalogRecipe(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("category"),
                rs.getString("cuisine"),
                rs.getString("description"),
                read(rs.getString("steps"), new TypeReference<List<String>>() { }),
                read(rs.getString("ingredients"), new TypeReference<List<CatalogRecipeIngredient>>() { }),
                array(rs, "methods"),
                array(rs, "flavors"),
                array(rs, "meal_types"),
                CatalogRecipe.SourceEnum.fromValue(rs.getString("source")));
        recipe.setEmoji(rs.getString("emoji"));
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
        recipe.setImageKey(rs.getString("image_key"));
        recipe.setSourceUrl(rs.getString("source_url"));
        return recipe;
    }

    private <T> T read(String value, TypeReference<T> type) {
        try {
            return json.readValue(value, type);
        } catch (IOException invalid) {
            throw new UncheckedIOException("Corrupt catalogue payload", invalid);
        }
    }

    private static List<String> array(ResultSet rs, String column) throws SQLException {
        java.sql.Array array = rs.getArray(column);
        if (array == null) return List.of();
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }

    private static String text(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
