package com.leandre.todoecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Plain JUnit, no Spring context: this is the behaviour the lab is actually
 * demonstrating, so it is worth testing without a container in the way.
 */
class TaskServiceTest {

    private TaskRepository repository;
    private FakeTaskCache cache;
    private TaskService service;

    @BeforeEach
    void setUp() {
        repository = mock(TaskRepository.class);
        cache = new FakeTaskCache();
        service = new TaskService(repository, cache);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(new Task("write the lab report")));
    }

    @Test
    void firstReadComesFromTheDatabase() {
        TaskPage page = service.list();

        assertThat(page.source()).isEqualTo(TaskPage.FROM_DATABASE);
        assertThat(page.items()).hasSize(1);
        verify(repository, times(1)).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void secondReadComesFromTheCache() {
        service.list();
        TaskPage page = service.list();

        assertThat(page.source()).isEqualTo(TaskPage.FROM_CACHE);
        verify(repository, times(1)).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void writingEvictsTheCachedList() {
        service.list();
        when(repository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        service.create("something new");

        assertThat(service.list().source()).isEqualTo(TaskPage.FROM_DATABASE);
        verify(repository, times(2)).findAllByOrderByCreatedAtDesc();
    }

    @Test
    void updatingEvictsTheCachedList() {
        service.list();
        Task task = new Task("old title");
        when(repository.findById(1L)).thenReturn(java.util.Optional.of(task));
        when(repository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));

        service.update(1L, "new title", true);

        assertThat(service.list().source()).isEqualTo(TaskPage.FROM_DATABASE);
    }

    @Test
    void deletingEvictsTheCachedList() {
        service.list();
        when(repository.existsById(1L)).thenReturn(true);

        assertThat(service.delete(1L)).isTrue();
        assertThat(service.list().source()).isEqualTo(TaskPage.FROM_DATABASE);
    }

    @Test
    void anUnavailableCacheStillServesTheList() {
        cache.goOffline();

        TaskPage page = service.list();

        assertThat(page.source()).isEqualTo(TaskPage.FROM_DATABASE_DEGRADED);
        assertThat(page.items()).hasSize(1);
    }

    @Test
    void cachedItemsRoundTripTheirTimestamps() {
        service.list();

        TaskPage page = service.list();

        assertThat(page.items().get(0).createdAt()).isNotNull().isBefore(Instant.now().plusSeconds(1));
    }

    @Test
    void writeEvictsOnlyAfterTransactionCommit() {
        service.list();
        when(repository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.create("committed write");

            assertThat(service.list().source()).isEqualTo(TaskPage.FROM_CACHE);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(service.list().source()).isEqualTo(TaskPage.FROM_DATABASE);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rolledBackWriteDoesNotEvictTheCache() {
        service.list();
        when(repository.save(any(Task.class))).thenAnswer(call -> call.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            service.create("rolled back write");

            assertThat(service.list().source()).isEqualTo(TaskPage.FROM_CACHE);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void staleReadCannotRepopulateAfterAWrite() {
        TaskCache.Lookup miss = cache.read();
        cache.evict();
        cache.write(List.of(new TaskView(1L, "stale", false, Instant.now(), Instant.now())),
                miss.generation());

        assertThat(cache.read().tasks()).isEmpty();
    }
}
