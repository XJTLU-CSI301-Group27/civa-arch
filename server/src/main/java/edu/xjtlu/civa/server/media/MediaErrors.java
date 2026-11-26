package edu.xjtlu.civa.server.media;

import edu.xjtlu.civa.server.auth.ApiExceptionHandler;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MediaController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class MediaErrors {
    @ExceptionHandler(MediaFault.class)
    ResponseEntity<Map<String, Object>> fault(MediaFault error, HttpServletRequest request) {
        int status = switch (error.code) {
            case "NOT_FOUND" -> 404;
            case "PAYLOAD_TOO_LARGE" -> 413;
            case "IDEMPOTENCY_KEY_REUSED" -> 409;
            default -> 400;
        };
        return ApiExceptionHandler.response(status, error.code, request, new HttpHeaders());
    }

    @ExceptionHandler(IdempotencyExecutor.KeyReused.class)
    ResponseEntity<Map<String, Object>> keyReused(HttpServletRequest request) {
        return ApiExceptionHandler.response(409, "IDEMPOTENCY_KEY_REUSED", request, new HttpHeaders());
    }
}
