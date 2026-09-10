package com.leandre.todoecs;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WarmupConfig {

    private static final Logger log = LoggerFactory.getLogger(WarmupConfig.class);

    /**
     * Opens the first pooled connection and touches Redis before the load
     * balancer sends real traffic, so the latency the UI reports is the cost of
     * the query rather than of pool initialisation and JIT warm-up.
     *
     * <p>It must never propagate: a runner that throws aborts the context and
     * kills the task, which would reintroduce exactly the "a brief database
     * blip fails the deployment" behaviour that the lazy pool and the shallow
     * health check are there to avoid.
     */
    @Bean
    public ApplicationRunner warmup(DataSource dataSource, TaskCache cache) {
        return args -> {
            try (var connection = dataSource.getConnection();
                 var statement = connection.createStatement()) {
                statement.execute("SELECT 1");
                log.info("Database warm-up succeeded");
            } catch (Exception e) {
                log.warn("Database warm-up failed, continuing: {}", e.toString());
            }
            log.info("Cache reachable at start-up: {}", cache.isAvailable());
        };
    }
}
