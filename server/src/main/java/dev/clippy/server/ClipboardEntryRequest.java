package dev.clippy.server;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record ClipboardEntryRequest(
        @NotBlank
        @Size(max = 128)
        String clientId,

        @NotNull
        String content,

        Instant timestamp
) {
}
