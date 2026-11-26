package edu.xjtlu.civa.feature.ef;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

final class ProfileIdentity {
    private ProfileIdentity() { }
    static String scope(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("Account required");
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(userId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder();
            for (byte value : bytes) hash.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
            return hash.toString();
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static String normalizedName(String name) {
        if (name == null) throw new IllegalArgumentException("Name required");
        String result = name.trim();
        if (result.isEmpty() || result.codePointCount(0,result.length()) > 60) throw new IllegalArgumentException("Invalid name length");
        return result;
    }
    static boolean avatarFile(String name) { return name != null && name.matches("avatar-[a-f0-9-]+\\.jpg"); }
}
