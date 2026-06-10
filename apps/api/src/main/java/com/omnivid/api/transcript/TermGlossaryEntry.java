package com.omnivid.api.transcript;

public record TermGlossaryEntry(
        long id,
        String sourcePattern,
        String replacement,
        boolean enabled
) {
}
