package com.omnivid.api.account;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ForgotPasswordRequest(
        @Email @NotBlank @Size(max = 160) String email
) {
}
