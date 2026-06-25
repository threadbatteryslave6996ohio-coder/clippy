package dev.clippy.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank
        @Size(max = 128)
        String clientId,

        @NotBlank
        @Size(max = 256)
        String secret
) {
}
