package dev.clippy.linux;

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
    private static final String REMOTE_SERVER_URL = "REMOTE_SERVER_URL";
    private static final String CLIENT_ID = "CLIENT_ID";
    private static final String POLL_INTERVAL_MS = "CLIPBOARD_POLL_INTERVAL_MS";
    private static final String CLIPBOARD_BACKEND = "CLIPBOARD_BACKEND";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

    private final ClipboardReader clipboardReader;
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String clientId;
    private String lastSentContent;
    private boolean previousSendFailed;
    private boolean previousReadFailed;

    private LinuxClipboardClientApp(ClipboardReader clipboardReader, URI endpoint, String clientId) {
        this.clipboardReader = clipboardReader;
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
        long pollIntervalMs = parsePollIntervalMs(envOrDefault(POLL_INTERVAL_MS, "1000"));
        ClipboardReader clipboardReader = createClipboardReader();

        LinuxClipboardClientApp app = new LinuxClipboardClientApp(clipboardReader, endpoint, clientId);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(scheduler)));

        System.out.printf("Clippy Linux client started. clientId=%s endpoint=%s pollIntervalMs=%d clipboardBackend=%s%n",
                clientId, endpoint, pollIntervalMs, clipboardReader.name());
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
            int statusCode = send(content);
            if (statusCode >= 200 && statusCode < 300) {
                lastSentContent = content;
                previousSendFailed = false;
                System.out.printf("Sent clipboard change. chars=%d%n", content.length());
            } else {
                logSendFailure("Server responded with HTTP " + statusCode);
            }
        } catch (IOException exception) {
            logSendFailure(exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logSendFailure("Interrupted while sending clipboard content.");
        }
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

    private void logReadFailure(String message) {
        if (!previousReadFailed) {
            System.err.printf("Clipboard read failed: %s%n", message);
            previousReadFailed = true;
        }
    }

    private static ClipboardReader createClipboardReader() {
        String requestedBackend = System.getenv(CLIPBOARD_BACKEND);
        if (requestedBackend != null && !requestedBackend.isBlank()) {
            ClipboardReader reader = switch (requestedBackend.trim().toLowerCase()) {
                case "wl-paste", "wayland" -> new CommandClipboardReader("wl-paste", List.of("wl-paste", "--no-newline", "--type", "text/plain"));
                case "xclip" -> new CommandClipboardReader("xclip", List.of("xclip", "-selection", "clipboard", "-out", "-target", "UTF8_STRING"));
                case "xsel" -> new CommandClipboardReader("xsel", List.of("xsel", "--clipboard", "--output"));
                case "awt", "java" -> new AwtClipboardReader();
                default -> throw new IllegalArgumentException("Unsupported " + CLIPBOARD_BACKEND + ": " + requestedBackend);
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
        if (parsed < 100) {
            throw new IllegalArgumentException(POLL_INTERVAL_MS + " must be at least 100.");
        }
        return parsed;
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
