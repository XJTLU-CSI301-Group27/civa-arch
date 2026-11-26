package edu.xjtlu.civa.server.plan;

import edu.xjtlu.civa.server.application.IdempotencyExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class PlanConfiguration {
    @Bean PlanningService planningService(JdbcTemplate jdbc, IdempotencyExecutor executor,
            PlatformTransactionManager manager) {
        return new PlanningService(jdbc, executor, manager);
    }
}
