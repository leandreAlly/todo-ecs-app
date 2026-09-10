package com.leandre.todoecs;

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

    void goOffline() {
        available = false;
        entry.set(null);
    }

    @Override
    public Optional<List<TaskView>> read() {
        return available ? Optional.ofNullable(entry.get()) : Optional.empty();
    }

    @Override
    public void write(List<TaskView> tasks) {
        if (available) {
            entry.set(List.copyOf(tasks));
        }
    }

    @Override
    public void evict() {
        entry.set(null);
    }

    @Override
    public boolean isAvailable() {
        return available;
    }
}
