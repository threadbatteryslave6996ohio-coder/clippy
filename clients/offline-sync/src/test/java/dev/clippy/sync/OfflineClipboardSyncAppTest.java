package dev.clippy.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.clippy.clients.envs.ClientAuthSession;
import dev.clippy.utils.clipboard.ClipboardLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineClipboardSyncAppTest {
    @TempDir
    Path tempDir;

    @Test
    void readsClipboardEntriesAndIgnoresAuthAuditEntries() throws Exception {
        Path log = tempDir.resolve("offline.json");
        String content = """
                [
                  {"clientId":"client-a","content":"offline text","timestamp":"2026-06-23T12:00:00Z"},
                  {"type":"auth","clientId":"client-a","operation":"refresh","timestamp":"2026-06-23T12:01:00Z"}
                ]
                """;

        List<OfflineClipboardSyncApp.ClipboardRecord> records = OfflineClipboardSyncApp.parseClipboardRecords(content, log);

        assertEquals(1, records.size());
        assertEquals("offline text", records.getFirst().content());
        assertEquals(Instant.parse("2026-06-23T12:00:00Z"), records.getFirst().timestamp());
    }

    @Test
    void skipsLegacyOversizedClipboardEntriesWithoutContactingServer() throws Exception {
        Path log = tempDir.resolve("offline.json");
        String content = """
                [{"clientId":"client-a","content":"%s","timestamp":"2026-06-23T12:00:00Z"}]
                """.formatted("x".repeat(ClipboardLimits.MAX_CONTENT_CHARACTERS + 1));

        List<OfflineClipboardSyncApp.ClipboardRecord> records =
                OfflineClipboardSyncApp.parseClipboardRecords(content, log);
        OfflineClipboardSyncApp app = new OfflineClipboardSyncApp(
                URI.create("http://localhost:1/clipboard"),
                "client-a",
                new ClientAuthSession(null, "client-a", null, "token-a")
        );

        assertEquals(1, records.size());
        assertEquals(new OfflineClipboardSyncApp.SyncResult(0, 0), app.sync(records));
    }

    @Test
    void excludesOversizedOldClientBeforeOwnershipValidation() throws Exception {
        List<OfflineClipboardSyncApp.ClipboardRecord> records = List.of(
                new OfflineClipboardSyncApp.ClipboardRecord(
                        "old-client",
                        "x".repeat(ClipboardLimits.MAX_CONTENT_CHARACTERS + 1),
                        Instant.parse("2026-06-23T11:00:00Z")),
                new OfflineClipboardSyncApp.ClipboardRecord(
                        "client-a", "valid", Instant.parse("2026-06-23T12:00:00Z"))
        );

        List<OfflineClipboardSyncApp.ClipboardRecord> syncable =
                OfflineClipboardSyncApp.syncableRecords(records, false);

        assertEquals(1, syncable.size());
        assertEquals("client-a", syncable.getFirst().clientId());
    }

    @Test
    void queriesTimeframeAndPostsOnlyMissingEntriesWithOriginalTimestamp() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/clipboard", exchange -> handleClipboard(exchange, requests));
        server.start();

        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/clipboard");
            ClientAuthSession auth = new ClientAuthSession(null, "client-a", null, "token-a");
            OfflineClipboardSyncApp app = new OfflineClipboardSyncApp(endpoint, "client-a", auth);
            List<OfflineClipboardSyncApp.ClipboardRecord> records = List.of(
                    new OfflineClipboardSyncApp.ClipboardRecord(
                            "client-a", "already there", Instant.parse("2026-06-23T12:00:00.123456789Z")),
                    new OfflineClipboardSyncApp.ClipboardRecord(
                            "client-a", "send me", Instant.parse("2026-06-23T13:00:00Z"))
            );

            OfflineClipboardSyncApp.SyncResult result = app.sync(records);

            assertEquals(1, result.alreadyPresent());
            assertEquals(1, result.sent());
            assertEquals(2, requests.size());
            assertTrue(requests.getFirst().startsWith("GET "));
            assertTrue(requests.getFirst().contains("from=2026-06-23T12%3A00%3A00.123455789Z"));
            assertTrue(requests.getFirst().contains("to=2026-06-23T13%3A00%3A00.000001Z"));
            assertTrue(requests.get(1).contains("\"content\":\"send me\""));
            assertTrue(requests.get(1).contains("\"timestamp\":\"2026-06-23T13:00:00Z\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void waitsThirtyMinutesThenSyncsNewFileEntries() throws Exception {
        List<String> requests = new ArrayList<>();
        List<Duration> sleepDurations = new ArrayList<>();
        List<String> clearedSnapshots = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/clipboard", exchange -> handleClipboard(exchange, requests));
        server.start();

        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/clipboard");
            ClientAuthSession auth = new ClientAuthSession(null, "client-a", null, "token-a");
            OfflineClipboardSyncApp app = new OfflineClipboardSyncApp(endpoint, "client-a", auth);
            List<OfflineClipboardSyncApp.ClipboardRecord> changedRecords = List.of(
                    new OfflineClipboardSyncApp.ClipboardRecord(
                            "client-a", "send me", Instant.parse("2026-06-23T13:00:00Z"))
            );
            OfflineClipboardSyncApp.ClipboardSnapshot changedSnapshot =
                    new OfflineClipboardSyncApp.ClipboardSnapshot("[changed]", changedRecords);
            app.monitor(() -> changedSnapshot,
                    snapshot -> {
                        clearedSnapshots.add(snapshot.content());
                        return true;
                    },
                    new OfflineClipboardSyncApp.ClipboardSnapshot("[]", List.of()),
                    OfflineClipboardSyncApp.DEFAULT_SYNC_INTERVAL, duration -> {
                sleepDurations.add(duration);
                if (sleepDurations.size() == 2) {
                    throw new InterruptedException("stop test loop");
                }
            });
        } catch (InterruptedException expected) {
            assertEquals("stop test loop", expected.getMessage());
        } finally {
            server.stop(0);
        }

        assertEquals(List.of(Duration.ofMinutes(30), Duration.ofMinutes(30)), sleepDurations);
        assertEquals(List.of("[]", "[changed]"), clearedSnapshots);
        assertEquals(2, requests.size());
        assertTrue(requests.getFirst().startsWith("GET "));
        assertTrue(requests.get(1).contains("\"content\":\"send me\""));
    }

    @Test
    void retriesInitialReadFailureInsteadOfExiting() throws Exception {
        List<Duration> sleepDurations = new ArrayList<>();
        List<OfflineClipboardSyncApp.ClipboardRecord> expectedRecords = List.of(
                new OfflineClipboardSyncApp.ClipboardRecord(
                        "client-a", "available later", Instant.parse("2026-06-23T14:00:00Z"))
        );
        int[] attempts = {0};

        OfflineClipboardSyncApp.ClipboardSnapshot expectedSnapshot =
                new OfflineClipboardSyncApp.ClipboardSnapshot("[available]", expectedRecords);
        OfflineClipboardSyncApp.ClipboardSnapshot snapshot = OfflineClipboardSyncApp.awaitInitialSnapshot(
                () -> {
                    if (attempts[0]++ == 0) {
                        throw new IOException("file does not exist yet");
                    }
                    return expectedSnapshot;
                },
                false,
                OfflineClipboardSyncApp.DEFAULT_SYNC_INTERVAL,
                sleepDurations::add
        );

        assertEquals(expectedSnapshot, snapshot);
        assertEquals(2, attempts[0]);
        assertEquals(List.of(Duration.ofMinutes(30)), sleepDurations);
    }

    @Test
    void doesNotClearSnapshotWhenSyncFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/clipboard", exchange -> respond(exchange, 503, "temporarily unavailable"));
        server.start();
        int[] clearAttempts = {0};

        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/clipboard");
            OfflineClipboardSyncApp app = new OfflineClipboardSyncApp(
                    endpoint, "client-a", new ClientAuthSession(null, "client-a", null, "token-a"));
            OfflineClipboardSyncApp.ClipboardSnapshot snapshot = new OfflineClipboardSyncApp.ClipboardSnapshot(
                    "[failed]",
                    List.of(new OfflineClipboardSyncApp.ClipboardRecord(
                            "client-a", "keep me", Instant.parse("2026-06-23T15:00:00Z")))
            );

            app.monitor(() -> snapshot, ignored -> {
                clearAttempts[0]++;
                return true;
            }, snapshot, Duration.ofMinutes(30), ignored -> {
                throw new InterruptedException("stop test loop");
            });
        } catch (InterruptedException expected) {
            assertEquals("stop test loop", expected.getMessage());
        } finally {
            server.stop(0);
        }

        assertEquals(0, clearAttempts[0]);
    }

    private static void handleClipboard(HttpExchange exchange, List<String> requests) throws IOException {
        if (!"Bearer token-a".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "");
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            requests.add("GET " + exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    [{"id":1,"clientId":"client-a","content":"already there","timestamp":"2026-06-23T12:00:00.123457Z"}]
                    """);
            return;
        }
        requests.add("POST " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        respond(exchange, 201, "{}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
