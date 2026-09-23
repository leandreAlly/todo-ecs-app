package com.leandre.todoecs;

import java.util.List;

/** Used when caching is switched off, so the service needs no null checks. */
public class NoopTaskCache implements TaskCache {

    @Override
    public Lookup read() {
        return new Lookup(java.util.Optional.empty(), -1L);
    }

    @Override
    public void write(List<TaskView> tasks, long generation) {
    }

    @Override
    public void evict() {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
