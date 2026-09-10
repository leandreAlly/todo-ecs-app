package com.leandre.todoecs;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskController {

    private final TaskService tasks;
    private final String version;
    private final String commit;

    public TaskController(TaskService tasks,
                          @Value("${app.version:local}") String version,
                          @Value("${app.commit:unknown}") String commit) {
        this.tasks = tasks;
        this.version = version;
        this.commit = commit;
    }

    /**
     * Liveness only, and deliberately so. This is the ALB health check for both
     * target groups, so it must stay independent of the database and the cache:
     * a check that failed during an RDS failover would drain every healthy task
     * out of the production target group over an outage the application is
     * built to survive.
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "commit", commit);
    }

    @GetMapping("/api/version")
    public Map<String, String> version() {
        return Map.of("version", version, "commit", commit);
    }

    @GetMapping("/api/tasks")
    public TaskPage list() {
        return tasks.list();
    }

    @PostMapping("/api/tasks")
    public ResponseEntity<TaskView> create(@Valid @RequestBody TaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tasks.create(request.title()));
    }

    @PatchMapping("/api/tasks/{id}")
    public ResponseEntity<TaskView> update(@PathVariable Long id,
                                           @Valid @RequestBody TaskUpdateRequest request) {
        return tasks.update(id, request.title(), request.completed())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/api/tasks/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return tasks.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}
