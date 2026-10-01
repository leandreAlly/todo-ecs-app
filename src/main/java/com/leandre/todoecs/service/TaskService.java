package com.leandre.todoecs.service;

import com.leandre.todoecs.cache.TaskCache;
import com.leandre.todoecs.dto.TaskPage;
import com.leandre.todoecs.dto.TaskView;
import com.leandre.todoecs.model.Task;
import com.leandre.todoecs.repository.TaskRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

        TaskCache.Lookup lookup = cache.read();
        if (lookup.tasks().isPresent()) {
            return new TaskPage(lookup.tasks().get(), TaskPage.FROM_CACHE, millisSince(start));
        }

        List<TaskView> tasks = repository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(TaskView::of)
                .toList();
        cache.write(tasks, lookup.generation());

        String source = cache.isAvailable() ? TaskPage.FROM_DATABASE : TaskPage.FROM_DATABASE_DEGRADED;
        return new TaskPage(tasks, source, millisSince(start));
    }

    @Transactional
    public TaskView create(String title) {
        Task saved = repository.save(new Task(title.trim()));
        evictAfterCommit();
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
            evictAfterCommit();
            return view;
        });
    }

    @Transactional
    public boolean delete(Long id) {
        if (!repository.existsById(id)) {
            return false;
        }
        repository.deleteById(id);
        evictAfterCommit();
        return true;
    }

    public boolean cacheAvailable() {
        return cache.isAvailable();
    }

    /**
     * A write must not invalidate the cache until its database transaction is
     * visible. Otherwise a concurrent read can miss Redis, read the old
     * database state, and repopulate the cache while the write is still
     * uncommitted. Plain unit tests do not start a Spring transaction, so they
     * retain the immediate eviction behaviour.
     */
    private void evictAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            cache.evict();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evict();
            }
        });
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
