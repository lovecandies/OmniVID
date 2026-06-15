package com.omnivid.api.admin;

public record AdminProviderSummary(
        String providerType,
        long id,
        long userId,
        String providerName,
        String mode,
        String baseUrl,
        String endpoint,
        String model,
        String apiKeyMasked,
        boolean enabled,
        boolean active,
        String lastTestStatus
) {
}
