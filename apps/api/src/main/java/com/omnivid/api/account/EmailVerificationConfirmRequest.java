package com.omnivid.api.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailVerificationConfirmRequest(
        @NotBlank @Size(min = 32, max = 128) String token
) {
}
