package edu.xjtlu.civa.server.nutrition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling-window limit for provider calls (cached hits are free). Single-instance only:
 * a multi-instance deployment needs a shared counter (the database or Redis).
 */
final class NutritionRateLimiter {
    private final Clock clock;
    private final int hourlyLimit;
    private final int dailyLimit;
    private final Map<UUID, Deque<Instant>> calls = new ConcurrentHashMap<>();

    NutritionRateLimiter(Clock clock, int hourlyLimit, int dailyLimit) {
        if (hourlyLimit < 1 || dailyLimit < 1) throw new IllegalArgumentException("Invalid nutrition rate limit");
        this.clock = clock;
        this.hourlyLimit = hourlyLimit;
        this.dailyLimit = dailyLimit;
    }

    synchronized void acquire(UUID actor) {
        Instant now = clock.instant();
        Deque<Instant> window = calls.computeIfAbsent(actor, ignored -> new ArrayDeque<>());
        window.removeIf(at -> !at.plus(Duration.ofHours(24)).isAfter(now));
        long lastHour = window.stream().filter(at -> at.plus(Duration.ofHours(1)).isAfter(now)).count();
        if (lastHour >= hourlyLimit) throw NutritionErrors.rateLimited(untilExpiry(window, Duration.ofHours(1), now));
        if (window.size() >= dailyLimit) throw NutritionErrors.rateLimited(untilExpiry(window, Duration.ofHours(24), now));
        window.addLast(now);
    }

    /** Seconds until the oldest call inside the window no longer blocks a new request. */
    private static long untilExpiry(Deque<Instant> window, Duration span, Instant now) {
        for (Instant at : window) {
            if (at.plus(span).isAfter(now)) {
                return Math.max(1, Duration.between(now, at.plus(span)).toSeconds());
            }
        }
        return 1;
    }
}
