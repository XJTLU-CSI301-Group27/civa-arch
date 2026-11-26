package edu.xjtlu.civa.server.media;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.api.model.Media;
import edu.xjtlu.civa.server.api.model.MediaScope;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;

/** Personal/household image storage; only jpeg/png up to 5 MB, owner or household members may read. */
final class MediaService {
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final IdempotencyExecutor executor;
    private final ObjectMapper json;
    private final Path root;

    MediaService(JdbcTemplate jdbc, IdempotencyExecutor executor, ObjectMapper json, String mediaDir) {
        this.jdbc = jdbc;
        this.executor = executor;
        this.json = json;
        this.root = Path.of(mediaDir).toAbsolutePath().normalize().resolve("media");
    }

    Media upload(UUID actor, UUID key, MultipartFile file, MediaScope scope, UUID householdId) {
        if (file == null || file.isEmpty() || scope == null) throw new MediaFault("INVALID_MEDIA");
        String mime = file.getContentType();
        if (mime == null) throw new MediaFault("INVALID_MEDIA");
        mime = mime.toLowerCase(java.util.Locale.ROOT);
        if (!mime.equals("image/jpeg") && !mime.equals("image/png")) throw new MediaFault("INVALID_MEDIA");
        if (scope == MediaScope.PERSONAL && householdId != null) throw new MediaFault("INVALID_MEDIA");
        if (scope == MediaScope.HOUSEHOLD) {
            if (householdId == null) throw new MediaFault("INVALID_MEDIA");
            Boolean member = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships"
                    + " WHERE household_id=? AND user_id=? AND active)", Boolean.class, householdId, actor);
            if (!Boolean.TRUE.equals(member)) throw new MediaFault("NOT_FOUND");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException invalid) {
            throw new MediaFault("INVALID_MEDIA");
        }
        if (bytes.length < 1) throw new MediaFault("INVALID_MEDIA");
        if (bytes.length > MAX_BYTES) throw new MediaFault("PAYLOAD_TOO_LARGE");
        String resolvedMime = mime;
        byte[] digest = digest(bytes, resolvedMime, scope, householdId);
        IdempotencyExecutor.Result result = executor.execute(actor, key, digest, () -> {
            UUID id = UUID.randomUUID();
            String storageKey = id + (resolvedMime.equals("image/png") ? ".png" : ".jpg");
            try {
                Files.createDirectories(root);
                Files.write(root.resolve(storageKey), bytes);
            } catch (IOException invalid) {
                throw new IllegalStateException("Cannot store media", invalid);
            }
            jdbc.update("INSERT INTO media(id, owner_user_id, household_id, scope, mime_type, size_bytes, storage_key)"
                            + " VALUES (?,?,?,?,?,?,?)",
                    id, actor, householdId, scope.getValue(), resolvedMime, bytes.length, storageKey);
            return new IdempotencyExecutor.Result(201, write(get(actor, id)));
        }, ignored -> { });
        try {
            return json.readValue(result.body, Media.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Invalid stored media", invalid);
        }
    }

    Resource content(UUID actor, UUID mediaId) {
        Media media = get(actor, mediaId);
        Path path = root.resolve(storageKey(mediaId));
        if (!Files.isRegularFile(path)) throw new MediaFault("NOT_FOUND");
        return new FileSystemResource(path);
    }

    String mimeType(UUID mediaId) {
        String mime = jdbc.queryForObject("SELECT mime_type FROM media WHERE id=?", String.class, mediaId);
        return mime == null ? "application/octet-stream" : mime;
    }

    private Media get(UUID actor, UUID mediaId) {
        List<Media> rows = jdbc.query("SELECT id, created_at, updated_at, owner_user_id, household_id, scope,"
                        + " mime_type, size_bytes FROM media WHERE id=?",
                (rs, row) -> map(rs), mediaId);
        if (rows.isEmpty()) throw new MediaFault("NOT_FOUND");
        Media media = rows.get(0);
        if (actor.equals(media.getOwnerUserId())) return media;
        if (media.getScope() != MediaScope.HOUSEHOLD || media.getHouseholdId() == null) {
            throw new MediaFault("NOT_FOUND");
        }
        Boolean member = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships"
                + " WHERE household_id=? AND user_id=? AND active)", Boolean.class, media.getHouseholdId(), actor);
        if (!Boolean.TRUE.equals(member)) throw new MediaFault("NOT_FOUND");
        return media;
    }

    private String storageKey(UUID id) {
        String key = jdbc.queryForObject("SELECT storage_key FROM media WHERE id=?", String.class, id);
        if (key == null) throw new MediaFault("NOT_FOUND");
        return key;
    }

    private Media map(ResultSet rs) throws SQLException {
        Media media = new Media();
        media.setId(rs.getObject("id", UUID.class));
        media.setCreatedAt(ISO.format(rs.getTimestamp("created_at").toInstant()));
        media.setUpdatedAt(ISO.format(rs.getTimestamp("updated_at").toInstant()));
        media.setOwnerUserId(rs.getObject("owner_user_id", UUID.class));
        media.setHouseholdId(rs.getObject("household_id", UUID.class));
        media.setScope(MediaScope.fromValue(rs.getString("scope")));
        media.setMimeType(rs.getString("mime_type"));
        media.setSizeBytes(rs.getLong("size_bytes"));
        return media;
    }

    private byte[] digest(byte[] bytes, String mime, MediaScope scope, UUID householdId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(mime.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(scope.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(householdId == null ? new byte[0] : householdId.toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Cannot serialise media", invalid);
        }
    }
}
