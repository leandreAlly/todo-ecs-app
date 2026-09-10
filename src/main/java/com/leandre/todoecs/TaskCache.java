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

    Optional<List<TaskView>> read();

    void write(List<TaskView> tasks);

    void evict();

    boolean isAvailable();
}
