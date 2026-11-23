package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class RecipeCatalogConfiguration {
    @Bean RecipeCatalogService recipeCatalogService(JdbcTemplate jdbc, ObjectMapper json) {
        return new RecipeCatalogService(jdbc, json);
    }

    @Bean PersonalRecipeService personalRecipeService(JdbcTemplate jdbc, ObjectMapper json,
            IdempotencyExecutor executor, PlatformTransactionManager manager) {
        return new PersonalRecipeService(jdbc, json, executor, manager);
    }

    @Bean RecipeFavoriteService recipeFavoriteService(JdbcTemplate jdbc) {
        return new RecipeFavoriteService(jdbc);
    }

    @Bean HouseholdRecipeService householdRecipeService(JdbcTemplate jdbc, ObjectMapper json,
            IdempotencyExecutor executor, PlatformTransactionManager manager) {
        return new HouseholdRecipeService(jdbc, json, executor, manager);
    }
}
