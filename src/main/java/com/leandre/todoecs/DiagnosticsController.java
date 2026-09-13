package com.leandre.todoecs;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deep check that {@code /health} deliberately is not.
 *
 * <p>It always answers 200, even when a dependency is down, and puts the state
 * of each one in the body. Returning a failure status here would be an
 * invitation for someone to wire a target group to it, which is exactly the
 * mistake the shallow health check exists to prevent.
 */
@RestController
public class DiagnosticsController {

    private final DataSource dataSource;
    private final TaskService tasks;
    private final String commit;

    public DiagnosticsController(DataSource dataSource, TaskService tasks,
                                 @Value("${app.commit:unknown}") String commit) {
        this.dataSource = dataSource;
        this.tasks = tasks;
        this.commit = commit;
    }

    @GetMapping("/api/diagnostics")
    public Map<String, Object> diagnostics() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("commit", commit);
        report.put("database", checkDatabase());
        report.put("cache", checkCache());
        return report;
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.nanoTime();
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            result.put("ok", true);
        } catch (Exception e) {
            result.put("ok", false);
        }
        result.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
        return result;
    }

    private Map<String, Object> checkCache() {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.nanoTime();
        result.put("ok", tasks.cacheAvailable());
        result.put("latencyMs", (System.nanoTime() - start) / 1_000_000L);
        return result;
    }
}
