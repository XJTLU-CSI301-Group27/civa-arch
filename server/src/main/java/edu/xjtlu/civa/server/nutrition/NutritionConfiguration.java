package edu.xjtlu.civa.server.nutrition;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class NutritionConfiguration {
    /**
     * {@code auto} uses DeepSeek when a key is configured and the deterministic stub otherwise,
     * so CI and local runs without a key stay green. Explicit {@code deepseek} without a valid
     * key fails at startup instead of silently returning fake numbers.
     */
    @Bean NutritionProvider nutritionProvider(ObjectMapper json,
            @Value("${civa.nutrition.provider:auto}") String provider,
            @Value("${civa.nutrition.api-key:}") String apiKey,
            @Value("${civa.nutrition.model:deepseek-flash}") String model,
            @Value("${civa.nutrition.endpoint:https://api.deepseek.com/chat/completions}") String endpoint) {
        String selected = provider == null ? "auto" : provider.trim().toLowerCase(Locale.ROOT);
        switch (selected) {
            case "stub": return new StubNutritionProvider();
            case "deepseek": return new DeepSeekNutritionProvider(json, endpoint, model, apiKey);
            case "auto":
                return apiKey == null || apiKey.isBlank()
                        ? new StubNutritionProvider()
                        : new DeepSeekNutritionProvider(json, endpoint, model, apiKey);
            default: throw new IllegalArgumentException("Unknown civa.nutrition.provider: " + provider);
        }
    }

    @Bean NutritionService nutritionService(JdbcTemplate jdbc, NutritionProvider provider, Clock clock,
            @Value("${civa.nutrition.cache-days:30}") int cacheDays,
            @Value("${civa.nutrition.hourly-limit:60}") int hourlyLimit,
            @Value("${civa.nutrition.daily-limit:20}") int dailyLimit) {
        return new NutritionService(jdbc, provider, new NutritionRateLimiter(clock, hourlyLimit, dailyLimit), clock, cacheDays);
    }

    @Bean Clock clock() { return Clock.systemUTC(); }
}
