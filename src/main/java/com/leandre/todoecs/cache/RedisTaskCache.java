package com.leandre.todoecs.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leandre.todoecs.dto.TaskView;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Read cache for the task list.
 *
 * <p>Every Redis failure is swallowed and reported as a miss. A cache is an
 * optimisation, so losing it has to degrade latency and nothing else - if it
 * could fail a request it would also fail the ALB health check and take the
 * fleet down with it.
 *
 * <p>The breaker exists because Lettuce still costs a full command timeout per
 * call while Redis is unreachable. Without it, a failover would add that
 * timeout to every page load for as long as it lasted.
 */
public class RedisTaskCache implements TaskCache {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskCache.class);
    private static final String KEY = "todo:tasks:all";
    private static final String GENERATION_KEY = "todo:tasks:generation";
    private static final TypeReference<List<TaskView>> LIST_OF_TASKS = new TypeReference<>() {
    };
    private static final DefaultRedisScript<Long> WRITE_IF_CURRENT = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1]); "
                    + "if not current then current = '0' end; "
                    + "if current ~= ARGV[1] then return 0 end; "
                    + "redis.call('SET', KEYS[2], ARGV[2], 'EX', ARGV[3]); return 1",
            Long.class);
    private static final DefaultRedisScript<Long> ADVANCE_AND_EVICT = new DefaultRedisScript<>(
            "redis.call('INCR', KEYS[1]); redis.call('DEL', KEYS[2]); return 1",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final Breaker breaker = new Breaker();

    /**
     * @param mapper must be the context's ObjectMapper. A bare {@code new
     *               ObjectMapper()} has no JavaTimeModule, so writing a
     *               TaskView would throw on its Instant fields, every write
     *               would be swallowed, and the cache would silently never
     *               serve a hit.
     */
    public RedisTaskCache(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    @Override
    public Lookup read() {
        if (breaker.isOpen()) {
            return miss(-1L);
        }
        try {
            String payload = redis.opsForValue().get(KEY);
            breaker.recordSuccess();
            if (payload == null) {
                String generation = redis.opsForValue().get(GENERATION_KEY);
                return miss(generation == null ? 0L : Long.parseLong(generation));
            }
            return new Lookup(Optional.of(mapper.readValue(payload, LIST_OF_TASKS)), -1L);
        } catch (RedisConnectionFailureException | QueryTimeoutException | RedisSystemException e) {
            log.warn("Cache read failed, falling back to the database: {}", e.toString());
            breaker.recordFailure();
            return miss(-1L);
        } catch (Exception e) {
            log.warn("Discarding an unreadable cache entry: {}", e.toString());
            evict();
            return miss(-1L);
        }
    }

    @Override
    public void write(List<TaskView> tasks, long generation) {
        if (breaker.isOpen() || generation < 0) {
            return;
        }
        try {
            redis.execute(WRITE_IF_CURRENT,
                    List.of(GENERATION_KEY, KEY),
                    Long.toString(generation),
                    mapper.writeValueAsString(tasks),
                    Long.toString(ttl.toSeconds()));
            breaker.recordSuccess();
        } catch (RedisConnectionFailureException | QueryTimeoutException | RedisSystemException e) {
            log.warn("Cache write failed: {}", e.toString());
            breaker.recordFailure();
        } catch (Exception e) {
            log.warn("Could not serialise the task list for the cache: {}", e.toString());
        }
    }

    @Override
    public void evict() {
        try {
            redis.execute(ADVANCE_AND_EVICT, List.of(GENERATION_KEY, KEY));
        } catch (Exception e) {
            log.warn("Cache eviction failed, entry will expire in {}: {}", ttl, e.toString());
        }
    }

    @Override
    public boolean isAvailable() {
        if (breaker.isOpen()) {
            return false;
        }
        try {
            redis.hasKey(KEY);
            breaker.recordSuccess();
            return true;
        } catch (Exception e) {
            breaker.recordFailure();
            return false;
        }
    }

    private static Lookup miss(long generation) {
        return new Lookup(Optional.empty(), generation);
    }

    /** Opens for 30 seconds after three consecutive failures. */
    private static final class Breaker {

        private static final int THRESHOLD = 3;
        private static final long OPEN_FOR_MILLIS = 30_000L;

        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntil;

        boolean isOpen() {
            return System.currentTimeMillis() < openUntil;
        }

        void recordSuccess() {
            failures.set(0);
        }

        void recordFailure() {
            if (failures.incrementAndGet() >= THRESHOLD) {
                openUntil = System.currentTimeMillis() + OPEN_FOR_MILLIS;
                failures.set(0);
            }
        }
    }
}
