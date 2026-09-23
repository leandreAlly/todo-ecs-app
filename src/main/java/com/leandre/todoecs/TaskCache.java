package com.leandre.todoecs;

import java.util.List;
import java.util.Optional;

/**
 * The seam between the service and Redis. Narrow on purpose: the service can
 * be tested against an in-memory implementation, and a cache that is down is
 * indistinguishable from a cache that is empty except through
 * {@link #isAvailable()}.
 */
public interface TaskCache {

    record Lookup(Optional<List<TaskView>> tasks, long generation) {
    }

    Lookup read();

    void write(List<TaskView> tasks, long generation);

    void evict();

    boolean isAvailable();
}
