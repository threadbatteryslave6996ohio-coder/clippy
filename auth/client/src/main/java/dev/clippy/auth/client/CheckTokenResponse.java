package dev.clippy.auth.client;

public record CheckTokenResponse(
        boolean valid,
        String clientId
) {
}
