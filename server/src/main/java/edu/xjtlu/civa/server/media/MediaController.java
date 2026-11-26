package edu.xjtlu.civa.server.media;

import edu.xjtlu.civa.server.api.MediaApi;
import edu.xjtlu.civa.server.api.model.Media;
import edu.xjtlu.civa.server.api.model.MediaScope;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
class MediaController implements MediaApi {
    private final MediaService media;

    MediaController(MediaService media) {
        this.media = media;
    }

    @Override public ResponseEntity<Media> uploadMedia(UUID idempotencyKey, MultipartFile file, MediaScope scope,
            UUID householdId) {
        return ResponseEntity.status(201).body(media.upload(actor(), idempotencyKey, file, scope, householdId));
    }

    @Override public ResponseEntity<Resource> getMediaContent(UUID mediaId) {
        Resource resource = media.content(actor(), mediaId);
        MediaType type = MediaType.parseMediaType(media.mimeType(mediaId));
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                .body(resource);
    }

    private static UUID actor() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
