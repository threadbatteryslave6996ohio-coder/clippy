package dev.clippy.client;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ClipboardClientApp {
    private static final String REMOTE_SERVER_URL = "REMOTE_SERVER_URL";
    private static final String CLIENT_ID = "CLIENT_ID";
    private static final String POLL_INTERVAL_MS = "CLIPBOARD_POLL_INTERVAL_MS";

    private final Clipboard clipboard;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String clientId;
    private String lastSentContent;
    private boolean previousSendFailed;

    private ClipboardClientApp(Clipboard clipboard, URI endpoint, String clientId) {
        this.clipboard = clipboard;
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void main(String[] args) {
        String remoteUrl = requireEnv(REMOTE_SERVER_URL);
        URI endpoint = clipboardEndpoint(remoteUrl);
        String clientId = envOrDefault(CLIENT_ID, defaultClientId());
        long pollIntervalMs = parsePollIntervalMs(envOrDefault(POLL_INTERVAL_MS, "1"));

        Clipboard clipboard;
        try {
            clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (HeadlessException exception) {
            throw new IllegalStateException("No graphical clipboard is available in this environment.", exception);
        }

        ClipboardClientApp app = new ClipboardClientApp(clipboard, endpoint, clientId);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(scheduler)));

        System.out.printf("Clippy client started. clientId=%s endpoint=%s pollIntervalMs=%d%n",
                clientId, endpoint, pollIntervalMs);
        scheduler.scheduleWithFixedDelay(app::pollAndSendIfChanged, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void pollAndSendIfChanged() {
        try {
            String content = readClipboardText();
            if (content == null || Objects.equals(content, lastSentContent)) {
                return;
            }

            int statusCode = send(content);
            if (statusCode >= 200 && statusCode < 300) {
                lastSentContent = content;
                previousSendFailed = false;
                System.out.printf("Sent clipboard change. chars=%d%n", content.length());
            } else {
                logSendFailure("Server responded with HTTP " + statusCode);
            }
        } catch (Exception exception) {
            logSendFailure(exception.getMessage());
        }
    }

    private String readClipboardText() throws IOException, UnsupportedFlavorException {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            return null;
        }
        Object data = clipboard.getData(DataFlavor.stringFlavor);
        return data instanceof String text ? text : null;
    }

    private int send(String content) throws IOException, InterruptedException {
        String json = """
                {"clientId":"%s","content":"%s","timestamp":"%s"}"""
                .formatted(jsonEscape(clientId), jsonEscape(content), Instant.now());

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void logSendFailure(String message) {
        if (!previousSendFailed) {
            System.err.printf("Clipboard send failed: %s%n", message);
            previousSendFailed = true;
        }
    }

    private static URI clipboardEndpoint(String remoteUrl) {
        String trimmed = remoteUrl.trim();
        if (trimmed.endsWith("/clipboard")) {
            return URI.create(trimmed);
        }
        return URI.create(trimmed.replaceAll("/+$", "") + "/clipboard");
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable: " + name);
        }
        return value;
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static long parsePollIntervalMs(String value) {
        long parsed = Long.parseLong(value);
        if (parsed < 1) {
            throw new IllegalArgumentException(POLL_INTERVAL_MS + " must be at least 1.");
        }
        return parsed;
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
}
