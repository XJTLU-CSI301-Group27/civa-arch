package edu.xjtlu.civa.server.recipe;

import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import edu.xjtlu.civa.server.auth.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {PersonalRecipeController.class, RecipeFavoriteController.class, HouseholdRecipeController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
class RecipeWriteErrors {
    @ExceptionHandler(PersonalRecipeFault.class)
    ResponseEntity<Map<String, Object>> fault(PersonalRecipeFault error, HttpServletRequest request) {
        int status = switch (error.code) {
            case "NOT_FOUND" -> 404;
            case "VERSION_CONFLICT", "IDEMPOTENCY_KEY_REUSED" -> 409;
            default -> 400;
        };
        return ApiExceptionHandler.response(status, error.code, request, new HttpHeaders());
    }

    @ExceptionHandler(IdempotencyExecutor.KeyReused.class)
    ResponseEntity<Map<String, Object>> keyReused(HttpServletRequest request) {
        return ApiExceptionHandler.response(409, "IDEMPOTENCY_KEY_REUSED", request, new HttpHeaders());
    }
}
