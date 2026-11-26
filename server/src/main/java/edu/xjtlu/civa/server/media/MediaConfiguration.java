package edu.xjtlu.civa.server.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
class MediaConfiguration {
    @Bean MediaService mediaService(JdbcTemplate jdbc, IdempotencyExecutor executor, ObjectMapper json,
            @Value("${civa.media-dir}") String mediaDir) {
        return new MediaService(jdbc, executor, json, mediaDir);
    }
}
