package edu.xjtlu.civa.server.profile;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class ProfileConfiguration {
    @Bean PreferencesService preferencesService(JdbcTemplate jdbc) {
        return new PreferencesService(jdbc);
    }
}
