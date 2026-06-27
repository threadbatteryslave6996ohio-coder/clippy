package dev.clippy.client;

import dev.clippy.auth.client.AuthClientException;
import dev.clippy.clients.envs.ClientAuthSession;
import dev.clippy.clients.envs.ClientEnvs;
import dev.clippy.filelocker.OfflineFileLockerClient;
import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.clipboard.ClipboardLimits;

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
import java.nio.file.Path;
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
    private final String authServerUrl;
    private final String clientId;
    private final ClientAuthSession authSession;
    private final OfflineFileLockerClient fileLocker;
    private String lastSentContent;
    private boolean previousReadFailed;
    private boolean previousSendFailed;
    private boolean previousAuthFailed;
    private ClipboardPayload pendingOfflinePayload;

    private ClipboardClientApp(
            Clipboard clipboard,
            URI endpoint,
            String authServerUrl,
            String clientId,
            ClientAuthSession authSession,
            OfflineFileLockerClient fileLocker
    ) {
        this.clipboard = clipboard;
        this.endpoint = endpoint;
        this.authServerUrl = authServerUrl;
        this.clientId = clientId;
        this.authSession = authSession;
        this.fileLocker = fileLocker;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void main(String[] args) throws IOException {
        Env env = ClientEnvs.load();
        URI endpoint = clipboardEndpoint(env.get(ClientEnvs.REMOTE_SERVER_URL));
        String authServerUrl = env.has(ClientEnvs.AUTH_SERVER_URL) ? env.get(ClientEnvs.AUTH_SERVER_URL) : null;
        String clientId = env.has(ClientEnvs.CLIENT_ID) ? env.get(ClientEnvs.CLIENT_ID) : defaultClientId();
        String clientSecret = env.has(ClientEnvs.CLIENT_SECRET) ? env.get(ClientEnvs.CLIENT_SECRET) : null;
        String clientToken = env.has(ClientEnvs.CLIENT_TOKEN) ? env.get(ClientEnvs.CLIENT_TOKEN) : null;
        long pollIntervalMs = env.has(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS)
                ? validatePollIntervalMs(env.get(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS))
                : 1L;

        Clipboard clipboard;
        try {
            clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (HeadlessException exception) {
            throw new IllegalStateException("No graphical clipboard is available in this environment.", exception);
        }

        ClientAuthSession authSession = new ClientAuthSession(authServerUrl, clientId, clientSecret, clientToken);
        Path fileLockerSocket = env.has(ClientEnvs.OFFLINE_FILE_LOCKER_SOCKET)
                ? Path.of(env.get(ClientEnvs.OFFLINE_FILE_LOCKER_SOCKET))
                : OfflineFileLockerClient.DEFAULT_SOCKET_PATH;
        OfflineFileLockerClient fileLocker = new OfflineFileLockerClient(fileLockerSocket);
        fileLocker.ping();
        if (authSession.canRefresh()) {
            if (authServerUrl == null) {
                throw new IllegalStateException("AUTH_SERVER_URL is required when CLIENT_SECRET is set.");
            }
            System.out.printf("Refreshing auth token from %s for clientId=%s%n", authServerUrl, clientId);
            authSession.refresh();
        } else if (!authSession.hasToken()) {
            throw new IllegalStateException("Set CLIENT_SECRET for auth login or CLIENT_TOKEN for a static token.");
        }

        ClipboardClientApp app = new ClipboardClientApp(
                clipboard, endpoint, authServerUrl, clientId, authSession, fileLocker);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(scheduler)));

        System.out.printf("Clippy client started. clientId=%s endpoint=%s authServer=%s pollIntervalMs=%d tokenSource=%s%n",
                clientId, endpoint, authServerUrl == null ? "unset" : authServerUrl, pollIntervalMs,
                authSession.canRefresh() ? "CLIENT_SECRET" : "CLIENT_TOKEN");
        scheduler.scheduleWithFixedDelay(app::pollAndSendIfChanged, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void pollAndSendIfChanged() {
        String content;
        try {
            content = readClipboardText();
            if (previousReadFailed) {
                logInfo("Clipboard read recovered.");
            }
            previousReadFailed = false;
        } catch (Exception exception) {
            logReadFailure(exception.getMessage());
            return;
        }

        if (pendingOfflinePayload != null) {
            if (!appendPendingOffline()) {
                return;
            }
            if (Objects.equals(content, lastSentContent)) {
                return;
            }
        }

        if (content == null || Objects.equals(content, lastSentContent)) {
            return;
        }
        if (!ClipboardLimits.isWithinContentLimit(content)) {
            lastSentContent = content;
            System.err.printf("Skipping oversized clipboard change. chars=%d maxChars=%d%n",
                    content.length(), ClipboardLimits.MAX_CONTENT_CHARACTERS);
            return;
        }

        ClipboardPayload payload = new ClipboardPayload(clientId, content, Instant.now());
        try {
            int statusCode = sendWithAuthRetry(payload);
            if (statusCode >= 200 && statusCode < 300) {
                lastSentContent = content;
                previousSendFailed = false;
                previousAuthFailed = false;
                System.out.printf("Sent clipboard change. chars=%d%n", content.length());
            } else if (statusCode == 401) {
                logAuthFailure("Remote server rejected the bearer token with HTTP 401.");
                logOffline(payload, "Unauthorized");
            } else {
                logSendFailure("Server responded with HTTP " + statusCode);
                logOffline(payload, "Server responded with HTTP " + statusCode);
            }
        } catch (IOException exception) {
            logSendFailure(exception.getMessage());
            logOffline(payload, exception.getMessage());
        } catch (AuthClientException exception) {
            logAuthFailure(authFailureMessage(exception));
            logOffline(payload, authFailureMessage(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logSendFailure("Interrupted while sending clipboard content.");
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

    private int sendWithAuthRetry(ClipboardPayload payload) throws IOException, InterruptedException {
        int statusCode = send(payload, authSession.token());
        if (statusCode != 401 || !authSession.canRefresh()) {
            return statusCode;
        }

        logAuthFailure("Remote server rejected the bearer token with HTTP 401. Refreshing from auth server.");
        authSession.refresh();
        previousAuthFailed = false;
        return send(payload, authSession.token());
    }

    private int send(ClipboardPayload payload, String clientToken) throws IOException, InterruptedException {
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

    private void logReadFailure(String message) {
        if (!previousReadFailed) {
            System.err.printf("Clipboard read failed. clientId=%s error=%s%n", clientId, message);
            previousReadFailed = true;
        }
    }

    private void logSendFailure(String message) {
        if (!previousSendFailed) {
            System.err.printf("Clipboard send failed. clientId=%s endpoint=%s error=%s%n", clientId, endpoint, message);
            previousSendFailed = true;
        }
    }

    private void logAuthFailure(String message) {
        if (!previousAuthFailed) {
            System.err.printf("Auth refresh failed. clientId=%s authServer=%s error=%s%n",
                    clientId, authServerUrl == null ? "unset" : authServerUrl, message);
            previousAuthFailed = true;
        }
    }

    private void logOffline(ClipboardPayload payload, String message) {
        String failureMessage = message == null || message.isBlank() ? "unknown error" : message;
        System.err.printf("Cannot reach remote server. clientId=%s endpoint=%s error=%s%n", clientId, endpoint, failureMessage);
        pendingOfflinePayload = payload;
        appendPendingOffline();
    }

    private boolean appendPendingOffline() {
        ClipboardPayload payload = pendingOfflinePayload;
        if (payload == null) {
            return true;
        }
        try {
            fileLocker.append(OFFLINE_LOG_PATH, payload.toJson());
            lastSentContent = payload.content();
            pendingOfflinePayload = null;
            System.err.printf("Logged clipboard message to %s. clientId=%s chars=%d%n",
                    OFFLINE_LOG_PATH.toAbsolutePath(), clientId, payload.content().length());
            return true;
        } catch (IOException exception) {
            System.err.printf("Clipboard send failed and local JSON log failed. clientId=%s error=%s%n",
                    clientId, exception.getMessage());
            return false;
        }
    }

    private void logInfo(String message) {
        System.out.printf("INFO clientId=%s endpoint=%s authServer=%s %s%n",
                clientId, endpoint, authServerUrl == null ? "unset" : authServerUrl, message);
    }

    private static String authFailureMessage(AuthClientException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        Throwable cause = exception.getCause();
        if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
            return message;
        }
        return message + ": " + cause.getMessage();
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
