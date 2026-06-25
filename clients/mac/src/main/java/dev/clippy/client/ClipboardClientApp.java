package dev.clippy.client;

import dev.clippy.clients.envs.ClientEnvs;
import dev.clippy.utils.envmanager.Env;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ClipboardClientApp {
    private static final Path OFFLINE_LOG_PATH = Path.of("clippy-offline-clipboard.json");

    private final Clipboard clipboard;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String clientId;
    private final String clientToken;
    private String lastSentContent;

    private ClipboardClientApp(Clipboard clipboard, URI endpoint, String clientId, String clientToken) {
        this.clipboard = clipboard;
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.clientToken = clientToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void main(String[] args) throws IOException {
        Env env = ClientEnvs.load();
        URI endpoint = clipboardEndpoint(env.get(ClientEnvs.REMOTE_SERVER_URL));
        String clientId = env.has(ClientEnvs.CLIENT_ID) ? env.get(ClientEnvs.CLIENT_ID) : defaultClientId();
        String clientToken = env.get(ClientEnvs.CLIENT_TOKEN);
        long pollIntervalMs = env.has(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS)
                ? validatePollIntervalMs(env.get(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS))
                : 1L;

        Clipboard clipboard;
        try {
            clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (HeadlessException exception) {
            throw new IllegalStateException("No graphical clipboard is available in this environment.", exception);
        }

        ClipboardClientApp app = new ClipboardClientApp(clipboard, endpoint, clientId, clientToken);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(scheduler)));

        System.out.printf("Clippy client started. clientId=%s endpoint=%s pollIntervalMs=%d%n",
                clientId, endpoint, pollIntervalMs);
        scheduler.scheduleWithFixedDelay(app::pollAndSendIfChanged, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void pollAndSendIfChanged() {
        String content;
        try {
            content = readClipboardText();
        } catch (Exception exception) {
            System.err.printf("Clipboard read failed: %s%n", exception.getMessage());
            return;
        }

        if (content == null || Objects.equals(content, lastSentContent)) {
            return;
        }

        ClipboardPayload payload = new ClipboardPayload(clientId, content, Instant.now());
        try {
            int statusCode = send(payload);
            if (statusCode >= 200 && statusCode < 300) {
                lastSentContent = content;
                System.out.printf("Sent clipboard change. chars=%d%n", content.length());
            } else {
                logOffline(payload, "Server responded with HTTP " + statusCode);
            }
        } catch (IOException exception) {
            logOffline(payload, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logOffline(payload, "Interrupted while sending clipboard content.");
        }
    }

    private String readClipboardText() throws IOException, UnsupportedFlavorException {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            return null;
        }
        Object data = clipboard.getData(DataFlavor.stringFlavor);
        return data instanceof String text ? text : null;
    }

    private int send(ClipboardPayload payload) throws IOException, InterruptedException {
        String json = """
                {"clientId":"%s","content":"%s","timestamp":"%s"}"""
                .formatted(jsonEscape(payload.clientId()), jsonEscape(payload.content()), payload.timestamp());

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + clientToken)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void logOffline(ClipboardPayload payload, String message) {
        String failureMessage = message == null || message.isBlank() ? "unknown error" : message;
        System.err.printf("Cannot reach remote server: %s%n", failureMessage);
        try {
            appendOfflinePayload(payload);
            lastSentContent = payload.content();
            System.err.printf("Logged clipboard message to %s. chars=%d%n",
                    OFFLINE_LOG_PATH.toAbsolutePath(), payload.content().length());
        } catch (IOException exception) {
            System.err.printf("Clipboard send failed and local JSON log failed: %s%n", exception.getMessage());
        }
    }

    private static void appendOfflinePayload(ClipboardPayload payload) throws IOException {
        String entry = "  " + payload.toJson();
        if (!Files.exists(OFFLINE_LOG_PATH) || Files.size(OFFLINE_LOG_PATH) == 0) {
            Files.writeString(OFFLINE_LOG_PATH, "[\n" + entry + "\n]\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            return;
        }

        String existing = Files.readString(OFFLINE_LOG_PATH, StandardCharsets.UTF_8);
        int arrayEnd = existing.lastIndexOf(']');
        if (arrayEnd < 0) {
            throw new IOException("Offline JSON log is not a JSON array: " + OFFLINE_LOG_PATH.toAbsolutePath());
        }

        String beforeEnd = existing.substring(0, arrayEnd).stripTrailing();
        String separator = beforeEnd.endsWith("[") ? "\n" : ",\n";
        Files.writeString(OFFLINE_LOG_PATH, beforeEnd + separator + entry + "\n]\n", StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private static URI clipboardEndpoint(String remoteUrl) {
        String trimmed = remoteUrl.trim();
        if (trimmed.endsWith("/clipboard")) {
            return URI.create(trimmed);
        }
        return URI.create(trimmed.replaceAll("/+$", "") + "/clipboard");
    }

    private static long validatePollIntervalMs(long value) {
        if (value < 1) {
            throw new IllegalArgumentException(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS.name() + " must be at least 1.");
        }
        return value;
    }

    private static String defaultClientId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "client-" + UUID.randomUUID();
        }
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

    private static void shutdown(ScheduledExecutorService scheduler) {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                System.err.println("Timed out while stopping clipboard client.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private record ClipboardPayload(String clientId, String content, Instant timestamp) {
        private String toJson() {
            return """
                    {"clientId":"%s","content":"%s","timestamp":"%s"}"""
                    .formatted(jsonEscape(clientId), jsonEscape(content), timestamp);
        }
    }
}
