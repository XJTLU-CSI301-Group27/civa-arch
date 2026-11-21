package edu.xjtlu.civa.server.nutrition;

/**
 * Canonical text, digest and normalization for nutrition request caching. The digest is the
 * cache key: two requests with the same normalized dish content share one estimate.
 */
final class NutritionDigest {
    private NutritionDigest() { }

    static String canonical(NutritionProvider.Input input) {
        return input.name().trim()
                + "\n--ingredients--\n" + String.join("\n", normalize(input.ingredients()))
                + "\n--steps--\n" + String.join("\n", normalize(input.steps()));
    }

    static String hash(NutritionProvider.Input input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical(input).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Trims, collapses internal whitespace and drops blank entries. */
    static java.util.List<String> normalize(java.util.List<String> values) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (values == null) return result;
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim().replaceAll("\\s+", " ");
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }
}
