package com.leandre.todoecs;

import java.util.List;

/**
 * A list of tasks plus where it came from. The source and the timing are the
 * point of the lab: they are what makes the cache visible in the UI rather
 * than something you have to take on trust.
 */
public record TaskPage(List<TaskView> items, String source, long latencyMs) {

    public static final String FROM_CACHE = "cache";
    public static final String FROM_DATABASE = "database";
    public static final String FROM_DATABASE_DEGRADED = "database (cache unavailable)";
}
