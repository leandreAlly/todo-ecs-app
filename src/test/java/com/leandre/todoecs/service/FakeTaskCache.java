package com.leandre.todoecs.service;

import com.leandre.todoecs.cache.TaskCache;
import com.leandre.todoecs.dto.TaskView;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory stand-in for Redis. Preferred over mocking RedisTemplate, which
 * would mean stubbing opsForValue() to return another mock and would end up
 * asserting on Spring's API rather than on the caching behaviour.
 */
class FakeTaskCache implements TaskCache {

    private final AtomicReference<List<TaskView>> entry = new AtomicReference<>();
    private boolean available = true;
    private long generation;

    void goOffline() {
        available = false;
        entry.set(null);
    }

    @Override
    public Lookup read() {
        return available
                ? new Lookup(Optional.ofNullable(entry.get()), generation)
                : new Lookup(Optional.empty(), -1L);
    }

    @Override
    public void write(List<TaskView> tasks, long expectedGeneration) {
        if (available && generation == expectedGeneration) {
            entry.set(List.copyOf(tasks));
        }
    }

    @Override
    public void evict() {
        generation++;
        entry.set(null);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }
}
