package dev.clippy.auth;

public record CheckTokenResponse(
        boolean valid,
        String clientId
) {
}
