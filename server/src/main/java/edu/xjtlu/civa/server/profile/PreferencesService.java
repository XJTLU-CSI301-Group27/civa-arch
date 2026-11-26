package edu.xjtlu.civa.server.profile;

import edu.xjtlu.civa.server.api.model.Preference;
import edu.xjtlu.civa.server.api.model.UpdatePreferenceRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Personal taste preferences: liked tags and excluded ingredients, versioned. */
public final class PreferencesService {
    private final JdbcTemplate jdbc;

    public PreferencesService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Preference get(UUID userId) {
        List<Preference> rows = jdbc.query("SELECT liked_tags, excluded_ingredient_ids, version"
                        + " FROM preferences WHERE user_id=?", (rs, row) -> new Preference(userId,
                        toSet(rs.getArray("liked_tags")), toUuidSet(rs.getArray("excluded_ingredient_ids")),
                        rs.getLong("version")), userId);
        if (!rows.isEmpty()) return rows.get(0);
        return new Preference(userId, java.util.Set.of(), java.util.Set.of(), 0L);
    }

    public Preference update(UUID userId, UpdatePreferenceRequest body) {
        if (body.getExpectedVersion() == null) throw new PreferencesFault("INVALID_PREFERENCES");
        java.util.Set<String> tags = new java.util.LinkedHashSet<>();
        if (body.getLikedTags() != null) {
            for (String tag : body.getLikedTags()) {
                String value = tag == null ? "" : tag.trim();
                if (value.isEmpty() || value.length() > 40) throw new PreferencesFault("INVALID_PREFERENCES");
                tags.add(value);
            }
            if (tags.size() > 30) throw new PreferencesFault("INVALID_PREFERENCES");
        }
        java.util.Set<UUID> excluded = new java.util.LinkedHashSet<>();
        if (body.getExcludedIngredientIds() != null) {
            for (UUID ingredient : body.getExcludedIngredientIds()) {
                if (ingredient == null) throw new PreferencesFault("INVALID_PREFERENCES");
                Integer count = jdbc.queryForObject("SELECT count(*) FROM ingredients WHERE id=?",
                        Integer.class, ingredient);
                if (count == null || count == 0) throw new PreferencesFault("INVALID_PREFERENCES");
                excluded.add(ingredient);
            }
        }
        List<Long> versions = jdbc.query("SELECT version FROM preferences WHERE user_id=?",
                (rs, row) -> rs.getLong(1), userId);
        if (versions.isEmpty()) {
            if (body.getExpectedVersion() != 0) throw new PreferencesFault("VERSION_CONFLICT");
            jdbc.update("INSERT INTO preferences(user_id, liked_tags, excluded_ingredient_ids) VALUES"
                    + " (?,CAST(? AS TEXT[]),CAST(? AS UUID[]))", userId, textArray(tags), uuidArray(excluded));
        } else {
            if (!versions.get(0).equals(body.getExpectedVersion())) throw new PreferencesFault("VERSION_CONFLICT");
            jdbc.update("UPDATE preferences SET liked_tags=CAST(? AS TEXT[]),"
                            + " excluded_ingredient_ids=CAST(? AS UUID[]), version=version+1 WHERE user_id=?",
                    textArray(tags), uuidArray(excluded), userId);
        }
        return get(userId);
    }

    private static java.util.Set<String> toSet(java.sql.Array array) {
        try {
            if (array == null) return java.util.Set.of();
            String[] values = (String[]) array.getArray();
            return new LinkedHashSet<>(List.of(values));
        } catch (java.sql.SQLException invalid) {
            return java.util.Set.of();
        }
    }

    private static java.util.Set<UUID> toUuidSet(java.sql.Array array) {
        try {
            if (array == null) return java.util.Set.of();
            Object[] values = (Object[]) array.getArray();
            java.util.Set<UUID> ids = new LinkedHashSet<>();
            for (Object value : values) ids.add((UUID) value);
            return ids;
        } catch (java.sql.SQLException invalid) {
            return java.util.Set.of();
        }
    }

    private static String textArray(java.util.Collection<String> values) {
        List<String> quoted = new ArrayList<>();
        for (String value : values) quoted.add("\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        return "{" + String.join(",", quoted) + "}";
    }

    private static String uuidArray(java.util.Collection<UUID> values) {
        List<String> quoted = new ArrayList<>();
        for (UUID value : values) quoted.add(value.toString());
        return "{" + String.join(",", quoted) + "}";
    }
}
