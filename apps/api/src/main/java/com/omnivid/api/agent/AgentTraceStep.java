package com.omnivid.api.agent;

public record AgentTraceStep(
        String name,
        String status,
        String detail
) {
    public AgentTraceStep {
        name = name == null || name.isBlank() ? "step" : name;
        status = status == null || status.isBlank() ? "done" : status;
        detail = detail == null ? "" : detail;
    }
}
