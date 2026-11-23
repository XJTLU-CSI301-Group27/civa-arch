package edu.xjtlu.civa.server.recipe;

import edu.xjtlu.civa.server.auth.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RecipeCatalogController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class RecipeCatalogErrors {
    @ExceptionHandler(RecipeCatalogNotFound.class)
    ResponseEntity<Map<String, Object>> notFound(RecipeCatalogNotFound error, HttpServletRequest request) {
        return ApiExceptionHandler.response(404, "NOT_FOUND", request, new HttpHeaders());
    }
}
