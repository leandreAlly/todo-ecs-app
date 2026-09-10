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
}
