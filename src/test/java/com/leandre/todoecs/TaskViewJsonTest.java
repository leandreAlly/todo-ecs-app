package com.leandre.todoecs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Guards the quietest failure mode in the whole application: if the cache
 * serialiser is handed an ObjectMapper without JavaTimeModule, every write of
 * a TaskView throws on its Instant fields, the exception is swallowed as a
 * cache failure, and the cache silently never serves a hit. This asserts that
 * the mapper Spring builds - the one RedisTaskCache is given - round-trips the
 * payload.
 */
@JsonTest
class TaskViewJsonTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void theContextMapperRoundTripsATaskList() throws Exception {
        List<TaskView> original = List.of(
                new TaskView(1L, "write the lab report", false, Instant.now(), Instant.now()));

        String json = mapper.writeValueAsString(original);
        List<TaskView> restored = mapper.readValue(json, new TypeReference<List<TaskView>>() {
        });

        assertThat(restored).isEqualTo(original);
    }
}
