package dev.clippy.auth.client;

public record LoginResponse(
        String clientId,
        String token
) {
}
