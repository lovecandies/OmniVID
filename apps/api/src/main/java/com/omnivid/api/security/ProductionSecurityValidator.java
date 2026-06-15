package com.omnivid.api.security;

import java.util.Arrays;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSecurityValidator implements InitializingBean {
    private final Environment environment;
    private final String providerSecret;
    private final String adminEmails;
    private final String databasePassword;
    private final String publicBaseUrl;

    public ProductionSecurityValidator(
            Environment environment,
            @Value("${omnivid.security.provider-key-secret:}") String providerSecret,
            @Value("${omnivid.security.admin-emails:}") String adminEmails,
            @Value("${spring.datasource.password:}") String databasePassword,
            @Value("${omnivid.security.public-base-url:}") String publicBaseUrl
    ) {
        this.environment = environment;
        this.providerSecret = providerSecret;
        this.adminEmails = adminEmails;
        this.databasePassword = databasePassword;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void afterPropertiesSet() {
        if (Arrays.stream(environment.getActiveProfiles()).noneMatch("production"::equals)) {
            return;
        }
        requireStrong("OMNIVID_PROVIDER_KEY_SECRET", providerSecret, 32);
        requireStrong("SPRING_DATASOURCE_PASSWORD", databasePassword, 16);
        if (adminEmails == null || adminEmails.isBlank()) {
            throw new IllegalStateException("OMNIVID_ADMIN_EMAILS must be configured in production");
        }
        if (publicBaseUrl == null || !publicBaseUrl.startsWith("https://")) {
            throw new IllegalStateException("OMNIVID_PUBLIC_BASE_URL must use HTTPS in production");
        }
    }

    private void requireStrong(String name, String value, int minimumLength) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (normalized.length() < minimumLength
                || normalized.contains("change-me")
                || normalized.contains("omnivid_pass")
                || normalized.contains("dev-secret")) {
            throw new IllegalStateException(name + " must be a non-placeholder secret with at least " + minimumLength + " characters");
        }
    }
}
