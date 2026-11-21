package edu.xjtlu.civa.server.nutrition;

/** Failure taxonomy for nutrition estimates, mapped to ApiError responses by ApiExceptionHandler. */
public final class NutritionErrors {
    private NutritionErrors() { }

    public static final class Failure extends RuntimeException {
        public final String code;
        public final int status;
        public final long retryAfterSeconds;

        public Failure(String code, int status, long retryAfterSeconds) {
            super(code);
            this.code = code;
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    static Failure unavailable(long retryAfterSeconds) {
        return new Failure("NUTRITION_UNAVAILABLE", 503, retryAfterSeconds);
    }

    static Failure rateLimited(long retryAfterSeconds) {
        return new Failure("RATE_LIMITED", 429, retryAfterSeconds);
    }
}
