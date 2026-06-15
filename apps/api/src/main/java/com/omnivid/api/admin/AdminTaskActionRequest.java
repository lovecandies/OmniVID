package com.omnivid.api.admin;

import jakarta.validation.constraints.Size;

public record AdminTaskActionRequest(
        @Size(max = 500) String message
) {
}
