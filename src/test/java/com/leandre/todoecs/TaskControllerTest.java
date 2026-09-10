package com.leandre.todoecs;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A web slice, not a full context. The application depends on PostgreSQL and
 * Redis, neither of which exists on a CI runner, and this keeps the HTTP
 * contract under test without either of them.
 */
@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService tasks;

    @Test
    void healthIsShallowAndDoesNotTouchTheService() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void versionReportsTheRunningCommit() throws Exception {
        mockMvc.perform(get("/api/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.commit").exists());
    }

    @Test
    void listingReportsWhereTheDataCameFrom() throws Exception {
        TaskView task = new TaskView(1L, "write the lab report", false, Instant.now(), Instant.now());
        when(tasks.list()).thenReturn(new TaskPage(List.of(task), TaskPage.FROM_CACHE, 2L));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("cache"))
                .andExpect(jsonPath("$.latencyMs").value(2))
                .andExpect(jsonPath("$.items[0].title").value("write the lab report"));
    }

    @Test
    void creatingATaskReturns201() throws Exception {
        when(tasks.create(any())).thenReturn(new TaskView(1L, "buy milk", false, Instant.now(), Instant.now()));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"buy milk\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("buy milk"));
    }

    @Test
    void aBlankTitleIsRejected() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void deletingAnUnknownTaskReturns404() throws Exception {
        when(tasks.delete(eq(99L))).thenReturn(false);

        mockMvc.perform(delete("/api/tasks/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void updatingAnUnknownTaskReturns404() throws Exception {
        when(tasks.update(eq(99L), any(), any())).thenReturn(Optional.empty());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/tasks/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"completed\":true}"))
                .andExpect(status().isNotFound());
    }
}
