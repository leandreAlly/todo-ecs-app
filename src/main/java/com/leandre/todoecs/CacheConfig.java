package com.leandre.todoecs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true", matchIfMissing = true)
    public TaskCache redisTaskCache(StringRedisTemplate redis,
                                    ObjectMapper mapper,
                                    @Value("${app.cache.ttl-seconds:30}") long ttlSeconds) {
        return new RedisTaskCache(redis, mapper, Duration.ofSeconds(ttlSeconds));
    }

    @Bean
    @ConditionalOnMissingBean(TaskCache.class)
    public TaskCache noopTaskCache() {
        return new NoopTaskCache();
    }

    /**
     * Lettuce queues commands while it is disconnected and only gives up at the
     * command timeout, which defaults to a minute. Rejecting immediately turns
     * an unreachable cache into a fast miss instead of a stalled page load.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceFailFast(
            @Value("${app.cache.command-timeout-seconds:1}") long timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .timeoutOptions(TimeoutOptions.enabled(timeout))
                .build());
    }
}
