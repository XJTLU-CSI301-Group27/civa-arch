package edu.xjtlu.civa.server.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class RecipeCatalogConfiguration {
    @Bean RecipeCatalogService recipeCatalogService(JdbcTemplate jdbc, ObjectMapper json) {
        return new RecipeCatalogService(jdbc, json);
    }
}
