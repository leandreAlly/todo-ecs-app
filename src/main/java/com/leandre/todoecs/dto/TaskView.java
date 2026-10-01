package com.leandre.todoecs.dto;

import com.leandre.todoecs.model.Task;
import java.time.Instant;

/**
 * What the API returns and what gets cached. Kept separate from the entity so
 * a cached payload never carries Hibernate proxies or lazy references.
 */
public record TaskView(Long id, String title, boolean completed, Instant createdAt, Instant updatedAt) {

    public static TaskView of(Task task) {
        return new TaskView(task.getId(), task.getTitle(), task.isCompleted(),
                task.getCreatedAt(), task.getUpdatedAt());
    }
}
