package dev.clippy.dummy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;

public final class DummyClientApp {
    private static final String REMOTE_SERVER_URL = "REMOTE_SERVER_URL";
    private static final String CLIENT_ID = "CLIENT_ID";
    private static final String DOTENV_FILE = ".env";

    private final HttpClient httpClient;
    private final URI endpoint;
    private final String clientId;

    private DummyClientApp(URI endpoint, String clientId) {
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, String> dotenv = loadDotenv();
        String remoteUrl = requireConfig(REMOTE_SERVER_URL, dotenv);
        DummyClientApp app = new DummyClientApp(
                clipboardEndpoint(remoteUrl),
                configOrDefault(CLIENT_ID, defaultClientId(), dotenv)
        );

        if (args.length > 0) {
            app.sendCommand(joinArgs(args));
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

    private void sendCommand(String command) throws IOException, InterruptedException {
        String json = """
                {"clientId":"%s","content":"%s","timestamp":"%s"}"""
                .formatted(jsonEscape(clientId), jsonEscape(command), Instant.now());

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        int statusCode = httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("Server responded with HTTP " + statusCode);
        }

        System.out.printf("Sent command. clientId=%s chars=%d%n", clientId, command.length());
    }

    private static URI clipboardEndpoint(String remoteUrl) {
        String trimmed = remoteUrl.trim();
        if (trimmed.endsWith("/clipboard")) {
            return URI.create(trimmed);
        }
        return URI.create(trimmed.replaceAll("/+$", "") + "/clipboard");
    }

    private static String requireConfig(String name, Map<String, String> dotenv) {
        String value = config(name, dotenv);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required configuration: " + name);
        }
        return value;
    }

    private static String configOrDefault(String name, String defaultValue, Map<String, String> dotenv) {
        String value = config(name, dotenv);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String config(String name, Map<String, String> dotenv) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? dotenv.get(name) : value;
    }

    private static Map<String, String> loadDotenv() throws IOException {
        Path path = findDotenv();
        if (path == null) {
            return Map.of();
        }

        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }

            String key = trimmed.substring(0, separator).trim();
            String value = unquote(trimmed.substring(separator + 1).trim());
            if (!key.isEmpty()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private static Path findDotenv() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(DOTENV_FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return null;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
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
