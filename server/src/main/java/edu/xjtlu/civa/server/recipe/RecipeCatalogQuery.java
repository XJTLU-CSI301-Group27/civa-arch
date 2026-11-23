package edu.xjtlu.civa.server.recipe;

import java.util.ArrayList;
import java.util.List;

/** Validated filter/paging state for the read-only catalogue query. */
record RecipeCatalogQuery(String where, List<Object> params, int page, int size, int offset) {
    static RecipeCatalogQuery of(String q, String category, String tag, boolean highProtein,
                                 Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0) throw new IllegalArgumentException("Invalid page");
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("Invalid page size");
        StringBuilder where = new StringBuilder("WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase(java.util.Locale.ROOT);
            if (needle.length() > 100) throw new IllegalArgumentException("Search text too long");
            where.append(" AND search_text LIKE ?");
            params.add("%" + needle + "%");
        }
        if (category != null && !category.isBlank()) {
            String value = category.trim();
            if (value.length() > 40) throw new IllegalArgumentException("Category too long");
            where.append(" AND category = ?");
            params.add(value);
        }
        if (tag != null && !tag.isBlank()) {
            String value = tag.trim();
            if (value.length() > 40) throw new IllegalArgumentException("Tag too long");
            where.append(" AND (EXISTS (SELECT 1 FROM unnest(methods) AS v WHERE v LIKE ?)"
                    + " OR EXISTS (SELECT 1 FROM unnest(flavors) AS v WHERE v LIKE ?)"
                    + " OR EXISTS (SELECT 1 FROM unnest(meal_types) AS v WHERE v LIKE ?))");
            String like = "%" + value + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (highProtein) where.append(" AND protein_per100g IS NOT NULL AND protein_per100g >= 10");
        return new RecipeCatalogQuery(where.toString(), List.copyOf(params), pageNumber, pageSize,
                pageNumber * pageSize);
    }
}
