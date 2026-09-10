package com.leandre.todoecs;

import jakarta.validation.constraints.Size;

public record TaskUpdateRequest(
        @Size(max = 200, message = "title must be 200 characters or fewer")
        String title,
        Boolean completed) {
}
