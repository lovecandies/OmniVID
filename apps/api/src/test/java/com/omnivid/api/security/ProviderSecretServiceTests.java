package com.omnivid.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderSecretServiceTests {
    @Test
    void encryptsAndDecryptsProviderSecrets() {
        ProviderSecretService service = new ProviderSecretService("test-secret-for-provider-keys");

        String encrypted = service.encrypt("sk-test-secret");

        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(encrypted).doesNotContain("sk-test-secret");
        assertThat(service.decrypt(encrypted)).isEqualTo("sk-test-secret");
    }

    @Test
    void readsLegacyBase64Secrets() {
        ProviderSecretService service = new ProviderSecretService("test-secret-for-provider-keys");

        assertThat(service.decrypt("c2stdGVzdC1zZWNyZXQ=")).isEqualTo("sk-test-secret");
    }
}
