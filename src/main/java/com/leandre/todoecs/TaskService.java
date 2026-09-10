package com.leandre.todoecs;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads go to Redis first and fall back to PostgreSQL; writes go to PostgreSQL
 * through RDS Proxy and drop the cached list.
 *
 * <p>Caching is done by hand rather than with {@code @Cacheable} so the API can
 * say which path served a request. A transparent annotation would cache just
 * as well and demonstrate nothing.
 */
@Service
public class TaskService {

    private final TaskRepository repository;
    private final TaskCache cache;

    public TaskService(TaskRepository repository, TaskCache cache) {
        this.repository = repository;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public TaskPage list() {
        long start = System.nanoTime();

        Optional<List<TaskView>> cached = cache.read();
        if (cached.isPresent()) {
            return new TaskPage(cached.get(), TaskPage.FROM_CACHE, millisSince(start));
        }

        List<TaskView> tasks = repository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(TaskView::of)
                .toList();
        cache.write(tasks);

        String source = cache.isAvailable() ? TaskPage.FROM_DATABASE : TaskPage.FROM_DATABASE_DEGRADED;
        return new TaskPage(tasks, source, millisSince(start));
    }

    @Transactional
    public TaskView create(String title) {
        Task saved = repository.save(new Task(title.trim()));
        cache.evict();
        return TaskView.of(saved);
    }

    @Transactional
    public Optional<TaskView> update(Long id, String title, Boolean completed) {
        return repository.findById(id).map(task -> {
            if (title != null && !title.isBlank()) {
                task.setTitle(title.trim());
            }
            if (completed != null) {
                task.setCompleted(completed);
            }
            TaskView view = TaskView.of(repository.save(task));
            cache.evict();
            return view;
        });
    }

    @Transactional
    public boolean delete(Long id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        cache.evict();
        return true;
    }

    public boolean cacheAvailable() {
        return cache.isAvailable();
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
