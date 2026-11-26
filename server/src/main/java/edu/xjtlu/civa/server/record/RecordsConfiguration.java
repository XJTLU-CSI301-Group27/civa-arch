package edu.xjtlu.civa.server.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import edu.xjtlu.civa.server.config.JsonbCodec;
import edu.xjtlu.civa.server.inventory.InventoryMatchingService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class RecordsConfiguration {
    @Bean MealEntryService mealEntryService(JdbcTemplate jdbc, ObjectMapper json, IdempotencyExecutor executor) {
        return new MealEntryService(jdbc, json, executor);
    }

    @Bean CookingEventService cookingEventService(JdbcTemplate jdbc, ObjectMapper json, JsonbCodec jsonb,
            IdempotencyExecutor executor, InventoryMatchingService matching) {
        return new CookingEventService(jdbc, json, jsonb, executor, matching, Clock.systemUTC());
    }
}
