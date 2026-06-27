package dev.clippy.linux;

import dev.clippy.auth.client.AuthClientException;
import dev.clippy.clients.envs.ClientAuthSession;
import dev.clippy.clients.envs.ClientEnvs;
import dev.clippy.filelocker.OfflineFileLockerClient;
import dev.clippy.utils.envmanager.Env;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class LinuxClipboardClientApp {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);
    private static final Path OFFLINE_LOG_PATH = Path.of("clippy-offline-clipboard.json");

    private final ClipboardReader clipboardReader;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String authServerUrl;
    private final String clientId;
    private final ClientAuthSession authSession;
    private final OfflineFileLockerClient fileLocker;
    private String lastSentContent;
    private boolean previousSendFailed;
    private boolean previousReadFailed;
    private boolean previousAuthFailed;

    private LinuxClipboardClientApp(
            ClipboardReader clipboardReader,
            URI endpoint,
            String authServerUrl,
            String clientId,
            ClientAuthSession authSession,
            OfflineFileLockerClient fileLocker
    ) {
        this.clipboardReader = clipboardReader;
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
                : 1000L;
        ClipboardReader clipboardReader = createClipboardReader(env);
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
            logAuthOperation(fileLocker, clientId, authServerUrl, "refresh", "started", "startup token refresh");
            try {
                authSession.refresh();
                logAuthOperation(fileLocker, clientId, authServerUrl, "refresh", "succeeded", "startup token refresh");
            } catch (RuntimeException exception) {
                logAuthOperation(fileLocker, clientId, authServerUrl, "refresh", "failed", authFailureMessage(exception));
                throw exception;
            }
        } else if (!authSession.hasToken()) {
            throw new IllegalStateException("Set CLIENT_SECRET for auth login or CLIENT_TOKEN for a static token.");
        }

        LinuxClipboardClientApp app = new LinuxClipboardClientApp(
                clipboardReader, endpoint, authServerUrl, clientId, authSession, fileLocker);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(scheduler)));

        System.out.printf("Clippy Linux client started. clientId=%s endpoint=%s authServer=%s pollIntervalMs=%d clipboardBackend=%s tokenSource=%s%n",
                clientId, endpoint, authServerUrl == null ? "unset" : authServerUrl, pollIntervalMs, clipboardReader.name(),
                authSession.canRefresh() ? "CLIENT_SECRET" : "CLIENT_TOKEN");
        scheduler.scheduleWithFixedDelay(app::pollAndSendIfChanged, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void pollAndSendIfChanged() {
        String content;
        try {
            content = clipboardReader.readText();
            previousReadFailed = false;
        } catch (Exception exception) {
            logReadFailure(exception.getMessage());
            return;
        }

        if (content == null || content.isEmpty() || Objects.equals(content, lastSentContent)) {
            return;
        }

        try {
            ClipboardPayload payload = new ClipboardPayload(clientId, content, Instant.now());
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
            logOffline(new ClipboardPayload(clientId, content, Instant.now()), exception.getMessage());
        } catch (AuthClientException exception) {
            logAuthFailure(authFailureMessage(exception));
            logOffline(new ClipboardPayload(clientId, content, Instant.now()), authFailureMessage(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logSendFailure("Interrupted while sending clipboard content.");
            logOffline(new ClipboardPayload(clientId, content, Instant.now()), "Interrupted while sending clipboard content.");
        }
    }

    private int sendWithAuthRetry(ClipboardPayload payload) throws IOException, InterruptedException {
        int statusCode = send(payload.content(), authSession.token());
        if (statusCode != 401 || !authSession.canRefresh()) {
            return statusCode;
        }

        logAuthFailure("Remote server rejected the bearer token with HTTP 401. Refreshing from auth server.");
        logAuthOperation(fileLocker, clientId, authServerUrl, "refresh", "started", "HTTP 401 token refresh");
        try {
            authSession.refresh();
            previousAuthFailed = false;
            logAuthOperation(fileLocker, clientId, authServerUrl, "refresh", "succeeded", "HTTP 401 token refresh");
        } catch (RuntimeException exception) {
            logAuthOperation(fileLocker, clientId, authServerUrl, "refresh", "failed", authFailureMessage(exception));
            throw exception;
        }
        return send(payload.content(), authSession.token());
    }

    private int send(String content, String clientToken) throws IOException, InterruptedException {
        String json = """
                {"clientId":"%s","content":"%s","timestamp":"%s"}"""
                .formatted(jsonEscape(clientId), jsonEscape(content), Instant.now());

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + clientToken)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private void logSendFailure(String message) {
        if (!previousSendFailed) {
            System.err.printf("Clipboard send failed. clientId=%s endpoint=%s error=%s%n", clientId, endpoint, message);
            previousSendFailed = true;
        }
    }

    private void logReadFailure(String message) {
        if (!previousReadFailed) {
            System.err.printf("Clipboard read failed. clientId=%s backend=%s error=%s%n", clientId, clipboardReader.name(), message);
            previousReadFailed = true;
        }
    }

    private void logAuthFailure(String message) {
        if (!previousAuthFailed) {
            System.err.printf("Auth refresh failed. clientId=%s authServer=%s error=%s%n", clientId, authServerUrl, message);
            previousAuthFailed = true;
        }
    }

    private void logOffline(ClipboardPayload payload, String message) {
        String failureMessage = message == null || message.isBlank() ? "unknown error" : message;
        System.err.printf("Cannot reach remote server. clientId=%s endpoint=%s error=%s%n", clientId, endpoint, failureMessage);
        try {
            fileLocker.append(OFFLINE_LOG_PATH, payload.toJson());
            lastSentContent = payload.content();
            System.err.printf("Logged clipboard message to %s. clientId=%s chars=%d%n",
                    OFFLINE_LOG_PATH.toAbsolutePath(), clientId, payload.content().length());
        } catch (IOException exception) {
            System.err.printf("Clipboard send failed and local JSON log failed. clientId=%s error=%s%n",
                    clientId, exception.getMessage());
        }
    }

    private static void logAuthOperation(
            OfflineFileLockerClient fileLocker,
            String clientId,
            String authServerUrl,
            String operation,
            String status,
            String message
    ) {
        try {
            fileLocker.append(OFFLINE_LOG_PATH,
                    new AuthLogEntry(clientId, authServerUrl, operation, status, message, Instant.now()).toJson());
        } catch (IOException exception) {
            System.err.printf("Auth operation log failed. clientId=%s authServer=%s error=%s%n",
                    clientId, authServerUrl == null ? "unset" : authServerUrl, exception.getMessage());
        }
    }

    private static String authFailureMessage(RuntimeException exception) {
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

    private static ClipboardReader createClipboardReader(Env env) {
        if (env.has(ClientEnvs.CLIPBOARD_BACKEND)) {
            String requestedBackend = env.get(ClientEnvs.CLIPBOARD_BACKEND);
            ClipboardReader reader = switch (requestedBackend.trim().toLowerCase()) {
                case "wl-paste", "wayland" -> new CommandClipboardReader("wl-paste", List.of("wl-paste", "--no-newline", "--type", "text/plain"));
                case "xclip" -> new CommandClipboardReader("xclip", List.of("xclip", "-selection", "clipboard", "-out", "-target", "UTF8_STRING"));
                case "xsel" -> new CommandClipboardReader("xsel", List.of("xsel", "--clipboard", "--output"));
                case "awt", "java" -> new AwtClipboardReader();
                default -> throw new IllegalArgumentException("Unsupported " + ClientEnvs.CLIPBOARD_BACKEND.name() + ": " + requestedBackend);
            };
            if (!reader.isAvailable()) {
                throw new IllegalStateException("Requested clipboard backend is not available: " + requestedBackend);
            }
            return reader;
        }

        List<ClipboardReader> candidates = new ArrayList<>();
        boolean wayland = envPresent("WAYLAND_DISPLAY");
        boolean x11 = envPresent("DISPLAY");

        if (wayland && executableExists("wl-paste")) {
            candidates.add(new CommandClipboardReader("wl-paste", List.of("wl-paste", "--no-newline", "--type", "text/plain")));
        }
        if (x11 && executableExists("xclip")) {
            candidates.add(new CommandClipboardReader("xclip", List.of("xclip", "-selection", "clipboard", "-out", "-target", "UTF8_STRING")));
        }
        if (x11 && executableExists("xsel")) {
            candidates.add(new CommandClipboardReader("xsel", List.of("xsel", "--clipboard", "--output")));
        }
        candidates.add(new AwtClipboardReader());

        return candidates.stream()
                .filter(ClipboardReader::isAvailable)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No clipboard backend is available. Install wl-clipboard on GNOME Wayland, or xclip/xsel on X11."));
    }

    private static boolean envPresent(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static boolean executableExists(String name) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String directory : path.split(":")) {
            if (Files.isExecutable(Path.of(directory, name))) {
                return true;
            }
        }
        return false;
    }

    private static URI clipboardEndpoint(String remoteUrl) {
        String trimmed = remoteUrl.trim();
        if (trimmed.endsWith("/clipboard")) {
            return URI.create(trimmed);
        }
        return URI.create(trimmed.replaceAll("/+$", "") + "/clipboard");
    }

    private static long validatePollIntervalMs(long value) {
        if (value < 100) {
            throw new IllegalArgumentException(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS.name() + " must be at least 100.");
        }
        return value;
    }

    private static String defaultClientId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "linux-" + UUID.randomUUID();
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

    private record AuthLogEntry(
            String clientId,
            String authServerUrl,
            String operation,
            String status,
            String message,
            Instant timestamp
    ) {
        private String toJson() {
            return """
                    {"type":"auth","clientId":"%s","authServer":"%s","operation":"%s","status":"%s","message":"%s","timestamp":"%s"}"""
                    .formatted(
                            jsonEscape(clientId),
                            jsonEscape(authServerUrl == null ? "unset" : authServerUrl),
                            jsonEscape(operation),
                            jsonEscape(status),
                            jsonEscape(message == null ? "" : message),
                            timestamp
                    );
        }
    }

    private interface ClipboardReader {
        String name();

        boolean isAvailable();

        String readText() throws IOException, InterruptedException, UnsupportedFlavorException;
    }

    private static final class CommandClipboardReader implements ClipboardReader {
        private final String name;
        private final List<String> command;

        private CommandClipboardReader(String name, List<String> command) {
            this.name = name;
            this.command = command;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isAvailable() {
            return executableExists(command.get(0));
        }

        @Override
        public String readText() throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return readAll(process.getInputStream());
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });

            boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException(name + " timed out");
            }

            byte[] stdout = readProcessOutput(output);
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String message = new String(stdout, StandardCharsets.UTF_8).trim();
                if (message.isBlank()) {
                    message = name + " exited with status " + exitCode;
                }
                throw new IOException(message);
            }

            String text = new String(stdout, StandardCharsets.UTF_8);
            return text.isEmpty() ? null : text;
        }

        private static byte[] readProcessOutput(CompletableFuture<byte[]> output) throws IOException {
            try {
                return output.join();
            } catch (CompletionException exception) {
                if (exception.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                throw exception;
            }
        }

        private static byte[] readAll(java.io.InputStream inputStream) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            inputStream.transferTo(output);
            return output.toByteArray();
        }
    }

    private static final class AwtClipboardReader implements ClipboardReader {
        @Override
        public String name() {
            return "awt";
        }

        @Override
        public boolean isAvailable() {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard();
                return true;
            } catch (HeadlessException exception) {
                return false;
            }
        }

        @Override
        public String readText() throws IOException, UnsupportedFlavorException {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return null;
            }

            Object data = clipboard.getData(DataFlavor.stringFlavor);
            return data instanceof String text ? text : null;
        }
    }
}
