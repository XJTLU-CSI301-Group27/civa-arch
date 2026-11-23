package edu.xjtlu.civa.server.recipe;

import edu.xjtlu.civa.server.api.model.RecipeFavorite;
import edu.xjtlu.civa.server.api.model.RecipeFavoriteKind;
import edu.xjtlu.civa.server.api.model.RecipeFavoritePage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Unified favourites over the public catalogue, personal recipes and household recipes. */
final class RecipeFavoriteService {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(java.time.ZoneOffset.UTC);
    private static final String SELECT = "SELECT f.kind, f.recipe_id, f.created_at,"
            + " COALESCE(c.name, u.name, r.name, '') AS name,"
            + " COALESCE(c.emoji, u.emoji) AS emoji,"
            + " COALESCE(c.category, u.category, '') AS category"
            + " FROM recipe_favorites f"
            + " LEFT JOIN recipe_catalog c ON f.kind = 'CATALOG' AND c.id = f.recipe_id"
            + " LEFT JOIN user_recipes u ON f.kind = 'PERSONAL' AND u.id = f.recipe_id AND u.user_id = f.user_id"
            + " LEFT JOIN recipes r ON f.kind = 'HOUSEHOLD' AND r.id = f.recipe_id"
            + " WHERE f.user_id = ?";

    private final JdbcTemplate jdbc;

    RecipeFavoriteService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    RecipeFavoritePage list(UUID actor, RecipeFavoriteKind kind, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid pagination");
        List<Object> params = new ArrayList<>();
        params.add(actor);
        String where = "";
        if (kind != null) {
            where = " AND f.kind = ?";
            params.add(kind.getValue());
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM recipe_favorites f WHERE f.user_id = ?" + where,
                Long.class, params.toArray());
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(pageSize);
        pageParams.add(pageNumber * pageSize);
        List<RecipeFavorite> items = jdbc.query(SELECT + where + " ORDER BY f.created_at DESC, f.recipe_id LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), pageParams.toArray());
        return new RecipeFavoritePage(items, pageNumber, pageSize, total == null ? 0 : total);
    }

    RecipeFavorite get(UUID actor, RecipeFavoriteKind kind, UUID recipeId) {
        List<RecipeFavorite> rows = jdbc.query(SELECT + " AND f.kind = ? AND f.recipe_id = ?",
                (rs, row) -> map(rs), actor, kind.getValue(), recipeId);
        if (rows.isEmpty()) throw new PersonalRecipeFault("NOT_FOUND");
        return rows.get(0);
    }

    void add(UUID actor, RecipeFavoriteKind kind, UUID recipeId) {
        if (!visible(actor, kind, recipeId)) throw new PersonalRecipeFault("NOT_FOUND");
        jdbc.update("INSERT INTO recipe_favorites(user_id, kind, recipe_id) VALUES (?,?,?)"
                        + " ON CONFLICT (user_id, kind, recipe_id) DO NOTHING",
                actor, kind.getValue(), recipeId);
    }

    void remove(UUID actor, RecipeFavoriteKind kind, UUID recipeId) {
        jdbc.update("DELETE FROM recipe_favorites WHERE user_id=? AND kind=? AND recipe_id=?",
                actor, kind.getValue(), recipeId);
    }

    private boolean visible(UUID actor, RecipeFavoriteKind kind, UUID recipeId) {
        Integer count = switch (kind.getValue()) {
            case "CATALOG" -> jdbc.queryForObject("SELECT count(*) FROM recipe_catalog WHERE id=?",
                    Integer.class, recipeId);
            case "PERSONAL" -> jdbc.queryForObject("SELECT count(*) FROM user_recipes WHERE id=? AND user_id=?",
                    Integer.class, recipeId, actor);
            case "HOUSEHOLD" -> jdbc.queryForObject("SELECT count(*) FROM recipes r"
                            + " JOIN memberships m ON m.household_id = r.household_id AND m.user_id = ? AND m.active"
                            + " WHERE r.id = ?", Integer.class, actor, recipeId);
            default -> 0;
        };
        return count != null && count > 0;
    }

    private static RecipeFavorite map(ResultSet rs) throws SQLException {
        RecipeFavorite favorite = new RecipeFavorite(
                RecipeFavoriteKind.fromValue(rs.getString("kind")),
                rs.getObject("recipe_id", UUID.class),
                rs.getString("name"),
                ISO.format(rs.getTimestamp("created_at").toInstant()));
        favorite.setEmoji(rs.getString("emoji"));
        favorite.setCategory(rs.getString("category"));
        return favorite;
    }
}
