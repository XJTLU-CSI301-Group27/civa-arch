package edu.xjtlu.civa.server.record;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
class RecordsConfiguration {
    @Bean MealEntryService mealEntryService(JdbcTemplate jdbc, ObjectMapper json, IdempotencyExecutor executor) {
        return new MealEntryService(jdbc, json, executor);
    }
}
