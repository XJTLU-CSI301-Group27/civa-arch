package edu.xjtlu.civa.server.household;

import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import edu.xjtlu.civa.server.auth.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class HouseholdErrors {
    @ExceptionHandler(HouseholdReadService.AccessFailure.class)
    ResponseEntity<Map<String, Object>> access(HouseholdReadService.AccessFailure error, HttpServletRequest request) {
        return error("HOUSEHOLD_ACCESS_REVOKED".equals(error.code) ? 403 : 404, error.code, request);
    }
    @ExceptionHandler(HouseholdWriteService.Failure.class)
    ResponseEntity<Map<String, Object>> write(HouseholdWriteService.Failure failure, HttpServletRequest request) {
        int status = switch (failure.code) {
            case "INVALID_INVITATION", "OWNER_CANNOT_LEAVE", "INVALID_TRANSFER_TARGET" -> 400;
            case "FORBIDDEN", "HOUSEHOLD_ACCESS_REVOKED", "NOT_HOUSEHOLD_OWNER", "NOT_TRANSFER_TARGET" -> 403;
            case "NOT_FOUND", "TRANSFER_REQUEST_NOT_FOUND" -> 404;
            case "VERSION_CONFLICT", "ALREADY_IN_HOUSEHOLD", "TRANSFER_ALREADY_PENDING", "TRANSFER_NOT_PENDING",
                    "OWNER_MUST_TRANSFER", "OWNER_MUST_DISSOLVE", "HOUSEHOLD_HAS_MEMBERS" -> 409;
            default -> 500;
        };
        return error(status, status == 500 ? "INTERNAL_ERROR" : failure.code, request);
    }
    @ExceptionHandler(IdempotencyExecutor.KeyReused.class)
    ResponseEntity<Map<String, Object>> reused(HttpServletRequest request) { return error(409, "IDEMPOTENCY_KEY_REUSED", request); }
    @ExceptionHandler(HouseholdController.NotImplemented.class)
    ResponseEntity<Map<String, Object>> notImplemented(HttpServletRequest request) { return error(501, "CONTRACT_NOT_IMPLEMENTED", request); }
    private ResponseEntity<Map<String, Object>> error(int status, String code, HttpServletRequest request) {
        return ApiExceptionHandler.response(status, code, request, new HttpHeaders());
    }
}
