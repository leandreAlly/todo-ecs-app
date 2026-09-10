package com.leandre.todoecs;

import java.util.List;
import java.util.Optional;

/** Used when caching is switched off, so the service needs no null checks. */
public class NoopTaskCache implements TaskCache {

    @Override
    public Optional<List<TaskView>> read() {
        return Optional.empty();
    }

    @Override
    public void write(List<TaskView> tasks) {
    }

    @Override
    public void evict() {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
