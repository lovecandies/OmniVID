package com.omnivid.api.transcript;

public record TermGlossaryCreateRequest(
        String sourcePattern,
        String replacement
) {
}
