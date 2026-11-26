package edu.xjtlu.civa.server.profile;

import edu.xjtlu.civa.server.auth.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class PreferencesErrors {

    @ExceptionHandler(PreferencesFault.class)
    ResponseEntity<Map<String, Object>> fault(PreferencesFault error, HttpServletRequest request) {
        int status = "VERSION_CONFLICT".equals(error.code) ? 409 : 400;
        return ApiExceptionHandler.response(status, error.code, request, new HttpHeaders());
    }
}
