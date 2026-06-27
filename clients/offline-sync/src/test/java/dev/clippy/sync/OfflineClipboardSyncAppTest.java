package dev.clippy.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.clippy.clients.envs.ClientAuthSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
