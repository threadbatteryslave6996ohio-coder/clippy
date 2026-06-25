package dev.clippy.auth;

public record LoginResponse(
        String clientId,
        String token
) {
}
