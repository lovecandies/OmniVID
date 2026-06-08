package com.omnivid.api.agent;

import jakarta.validation.constraints.NotBlank;

public record AgentAskRequest(
        @NotBlank String question
) {
}
