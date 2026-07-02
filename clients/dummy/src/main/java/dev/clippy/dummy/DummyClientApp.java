package dev.clippy.dummy;

import dev.clippy.clients.envs.ClientAuthSession;
import dev.clippy.clients.envs.ClientEnvs;
import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvSnapshotLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.StringJoiner;
import java.util.UUID;

public final class DummyClientApp {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String clientId;
    private final ClientAuthSession authSession;

    private DummyClientApp(URI endpoint, String clientId, ClientAuthSession authSession) {
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.authSession = authSession;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Env env = ClientEnvs.load(EnvSnapshotLogger.consoleOnly("dummy-client-env"));
        String authServerUrl = env.has(ClientEnvs.AUTH_SERVER_URL) ? env.get(ClientEnvs.AUTH_SERVER_URL) : null;
        String clientId = env.has(ClientEnvs.CLIENT_ID) ? env.get(ClientEnvs.CLIENT_ID) : defaultClientId();
        String clientSecret = env.has(ClientEnvs.CLIENT_SECRET) ? env.get(ClientEnvs.CLIENT_SECRET) : null;
        String clientToken = env.has(ClientEnvs.CLIENT_TOKEN) ? env.get(ClientEnvs.CLIENT_TOKEN) : null;

        ClientAuthSession authSession = new ClientAuthSession(authServerUrl, clientId, clientSecret, clientToken);
        if (authSession.canRefresh()) {
            if (authServerUrl == null) {
                throw new IllegalStateException("AUTH_SERVER_URL is required when CLIENT_SECRET is set.");
            }
            System.out.printf("Refreshing auth token from %s for clientId=%s%n", authServerUrl, clientId);
            authSession.refresh();
        } else if (!authSession.hasToken()) {
            throw new IllegalStateException("Set CLIENT_SECRET for auth login or CLIENT_TOKEN for a static token.");
        }

        DummyClientApp app = new DummyClientApp(
                clipboardEndpoint(env.get(ClientEnvs.REMOTE_SERVER_URL)),
                clientId,
                authSession
        );

        if (args.length > 0) {
            if (!app.sendCommand(joinArgs(args))) {
                System.exit(1);
            }
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String command;
            while ((command = reader.readLine()) != null) {
                if (!command.isBlank()) {
                    app.sendCommand(command);
                }
            }
        }
    }

    private boolean sendCommand(String command) {
        try {
            int statusCode = send(command, authSession.token());
            if (statusCode == 401 && authSession.canRefresh()) {
                System.err.printf("Remote server rejected the bearer token with HTTP 401. Refreshing from auth server. endpoint=%s%n", endpoint);
                authSession.refresh();
                statusCode = send(command, authSession.token());
            }
            if (statusCode < 200 || statusCode >= 300) {
                System.err.printf("Remote server rejected command. endpoint=%s httpStatus=%d%n", endpoint, statusCode);
                return false;
            }

            System.out.printf("Sent command. clientId=%s chars=%d%n", clientId, command.length());
            return true;
        } catch (IOException exception) {
            System.err.printf("Cannot reach remote server. endpoint=%s error=%s%n", endpoint, failureMessage(exception));
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.printf("Interrupted while sending command. endpoint=%s%n", endpoint);
            return false;
        }
    }

    private int send(String command, String clientToken) throws IOException, InterruptedException {
        String json = """
                {"clientId":"%s","content":"%s","timestamp":"%s"}"""
                .formatted(jsonEscape(clientId), jsonEscape(command), Instant.now());

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + clientToken)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static URI clipboardEndpoint(String remoteUrl) {
        String trimmed = remoteUrl.trim();
        if (trimmed.endsWith("/clipboard")) {
            return URI.create(trimmed);
        }
        return URI.create(trimmed.replaceAll("/+$", "") + "/clipboard");
    }

    private static String defaultClientId() {
        try {
            return "dummy-" + InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "dummy-" + UUID.randomUUID();
        }
    }

    private static String joinArgs(String[] args) {
        StringJoiner joiner = new StringJoiner(" ");
        for (String arg : args) {
            joiner.add(arg);
        }
        return joiner.toString();
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append("\\u%04x".formatted((int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
