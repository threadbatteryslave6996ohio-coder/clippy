package dev.clippy.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.clippy.clients.envs.ClientAuthSession;
import dev.clippy.clients.envs.ClientEnvs;
import dev.clippy.filelocker.OfflineFileLockerClient;
import dev.clippy.utils.envmanager.Env;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

public final class OfflineClipboardSyncApp {
    private static final Path DEFAULT_OFFLINE_LOG = Path.of("clippy-offline-clipboard.json");
    static final Duration DEFAULT_SYNC_INTERVAL = Duration.ofMinutes(30);
    private static final int REMOTE_PAGE_SIZE = 1_000;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI clipboardEndpoint;
    private final String clientId;
    private final ClientAuthSession authSession;

    OfflineClipboardSyncApp(URI clipboardEndpoint, String clientId, ClientAuthSession authSession) {
        this.clipboardEndpoint = clipboardEndpoint;
        this.clientId = clientId;
        this.authSession = authSession;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public static void main(String[] args) throws Exception {
        Path offlineLog = args.length == 0 ? DEFAULT_OFFLINE_LOG : Path.of(args[0]);
        Env env = ClientEnvs.load();
        Path fileLockerSocket = env.has(ClientEnvs.OFFLINE_FILE_LOCKER_SOCKET)
                ? Path.of(env.get(ClientEnvs.OFFLINE_FILE_LOCKER_SOCKET))
                : OfflineFileLockerClient.DEFAULT_SOCKET_PATH;
        OfflineFileLockerClient fileLocker = new OfflineFileLockerClient(fileLockerSocket);
        RecordSource recordSource = () -> readClipboardSnapshot(offlineLog, fileLocker);
        Duration syncInterval = env.has(ClientEnvs.OFFLINE_SYNC_INTERVAL_MINUTES)
                ? syncInterval(env.get(ClientEnvs.OFFLINE_SYNC_INTERVAL_MINUTES))
                : DEFAULT_SYNC_INTERVAL;
        boolean clientIdConfigured = env.has(ClientEnvs.CLIENT_ID);
        ClipboardSnapshot initialSnapshot = awaitInitialSnapshot(
                recordSource, !clientIdConfigured, syncInterval, Thread::sleep);

        String configuredClientId = clientIdConfigured
                ? env.get(ClientEnvs.CLIENT_ID)
                : singleClientId(initialSnapshot.records());
        ensureRecordsBelongToClient(initialSnapshot.records(), configuredClientId);

        String authServerUrl = env.has(ClientEnvs.AUTH_SERVER_URL) ? env.get(ClientEnvs.AUTH_SERVER_URL) : null;
        String clientSecret = env.has(ClientEnvs.CLIENT_SECRET) ? env.get(ClientEnvs.CLIENT_SECRET) : null;
        String clientToken = env.has(ClientEnvs.CLIENT_TOKEN) ? env.get(ClientEnvs.CLIENT_TOKEN) : null;
        ClientAuthSession authSession = new ClientAuthSession(authServerUrl, configuredClientId, clientSecret, clientToken);
        if (!authSession.hasToken() && !authSession.canRefresh()) {
            throw new IllegalStateException("Set CLIENT_SECRET or CLIENT_TOKEN before syncing.");
        }

        OfflineClipboardSyncApp app = new OfflineClipboardSyncApp(
                clipboardEndpoint(env.get(ClientEnvs.REMOTE_SERVER_URL)),
                configuredClientId,
                authSession
        );
        System.out.printf("Monitoring %s for offline clipboard changes every %d minutes.%n",
                offlineLog.toAbsolutePath(), syncInterval.toMinutes());
        app.monitor(recordSource,
                snapshot -> fileLocker.clearIfUnchanged(offlineLog, snapshot.content()),
                initialSnapshot, syncInterval, Thread::sleep);
    }

    static ClipboardSnapshot awaitInitialSnapshot(RecordSource recordSource, boolean requireRecords,
                                                   Duration interval, Sleeper sleeper)
            throws InterruptedException {
        while (true) {
            try {
                ClipboardSnapshot snapshot = recordSource.read();
                if (!requireRecords || !snapshot.records().isEmpty()) {
                    return snapshot;
                }
                System.out.printf("Offline clipboard file is empty; waiting %d minutes to derive CLIENT_ID.%n",
                        interval.toMinutes());
            } catch (IOException | RuntimeException exception) {
                System.err.printf("Could not read initial offline clipboard file; will retry in %d minutes: %s%n",
                        interval.toMinutes(), exception.getMessage());
            }
            sleeper.sleep(interval);
        }
    }

    void monitor(RecordSource recordSource, SnapshotClearer snapshotClearer,
                 ClipboardSnapshot initialSnapshot, Duration interval, Sleeper sleeper)
            throws InterruptedException {
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("Sync interval must be positive.");
        }

        ClipboardSnapshot snapshot = initialSnapshot;
        String lastProcessedContent = null;
        while (true) {
            if (!snapshot.content().equals(lastProcessedContent)) {
                try {
                    List<ClipboardRecord> records = snapshot.records();
                    if (records.isEmpty()) {
                        System.out.println("No offline clipboard entries to sync. Waiting for changes.");
                    } else {
                        SyncResult result = sync(records);
                        System.out.printf("Offline clipboard sync complete. clientId=%s checked=%d alreadyPresent=%d sent=%d%n",
                                clientId, records.size(), result.alreadyPresent(), result.sent());
                    }
                    if (snapshotClearer.clear(snapshot)) {
                        System.out.println("Cleared synchronized offline clipboard file.");
                    } else {
                        System.out.println("Offline clipboard file changed during sync; preserving it for the next pass.");
                    }
                    lastProcessedContent = snapshot.content();
                } catch (IOException | RuntimeException exception) {
                    System.err.printf("Offline clipboard sync failed; will retry in %d minutes: %s%n",
                            interval.toMinutes(), exception.getMessage());
                }
            }

            sleeper.sleep(interval);
            try {
                snapshot = recordSource.read();
            } catch (IOException | RuntimeException exception) {
                System.err.printf("Could not read offline clipboard file; will retry in %d minutes: %s%n",
                        interval.toMinutes(), exception.getMessage());
            }
        }
    }

    SyncResult sync(List<ClipboardRecord> records) throws IOException, InterruptedException {
        if (records.isEmpty()) {
            return new SyncResult(0, 0);
        }
        ensureRecordsBelongToClient(records, clientId);

        Instant from = records.stream().map(ClipboardRecord::timestamp).min(Instant::compareTo).orElseThrow();
        Instant to = records.stream().map(ClipboardRecord::timestamp).max(Instant::compareTo).orElseThrow();
        RemoteRecordIndex remoteRecords = fetchRemoteRecords(from.minusNanos(1_000), to.plusNanos(1_000));
        int alreadyPresent = 0;
        int sent = 0;
        for (ClipboardRecord record : records) {
            RecordKey key = record.key();
            if (remoteRecords.contains(key)) {
                alreadyPresent++;
                continue;
            }
            post(record);
            remoteRecords.add(key);
            sent++;
        }
        return new SyncResult(alreadyPresent, sent);
    }

    private RemoteRecordIndex fetchRemoteRecords(Instant from, Instant to) throws IOException, InterruptedException {
        RemoteRecordIndex records = new RemoteRecordIndex();
        Instant afterTimestamp = null;
        long afterId = 0;
        while (true) {
            String cursor = afterTimestamp == null ? "" : "&afterTimestamp=" + encode(afterTimestamp.toString())
                    + "&afterId=" + afterId;
            URI uri = URI.create(clipboardEndpoint + "?clientId=" + encode(clientId)
                    + "&from=" + encode(from.toString()) + "&to=" + encode(to.toString())
                    + "&limit=" + REMOTE_PAGE_SIZE + cursor);
            HttpResponse<String> response = sendGet(uri);
            if (response.statusCode() == 401 && authSession.canRefresh()) {
                authSession.refresh();
                response = sendGet(uri);
            }
            requireSuccess(response, "query clipboard entries");

            JsonNode root = JSON.readTree(response.body());
            if (!root.isArray()) {
                throw new IOException("Server returned a non-array clipboard response.");
            }
            for (JsonNode node : root) {
                afterId = requiredLong(node, "id");
                afterTimestamp = parseTimestamp(requiredText(node, "timestamp"), "server response");
                records.add(new RecordKey(requiredText(node, "clientId"), requiredText(node, "content"),
                        afterTimestamp));
            }
            if (root.size() < REMOTE_PAGE_SIZE) {
                return records;
            }
        }
    }

    private void post(ClipboardRecord record) throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(JSON.createObjectNode()
                .put("clientId", record.clientId())
                .put("content", record.content())
                .put("timestamp", record.timestamp().toString()));
        HttpResponse<String> response = sendPost(body);
        if (response.statusCode() == 401 && authSession.canRefresh()) {
            authSession.refresh();
            response = sendPost(body);
        }
        requireSuccess(response, "send offline clipboard entry at " + record.timestamp());
    }

    private HttpResponse<String> sendGet(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + authSession.token())
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPost(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(clipboardEndpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + authSession.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static List<ClipboardRecord> readClipboardRecords(Path path, OfflineFileLockerClient fileLocker) throws IOException {
        return readClipboardSnapshot(path, fileLocker).records();
    }

    static ClipboardSnapshot readClipboardSnapshot(Path path, OfflineFileLockerClient fileLocker) throws IOException {
        String content = fileLocker.read(path);
        return new ClipboardSnapshot(content, parseClipboardRecords(content, path));
    }

    static List<ClipboardRecord> parseClipboardRecords(String content, Path path) throws IOException {
        JsonNode root = JSON.readTree(content);
        if (!(root instanceof ArrayNode array)) {
            throw new IOException("Offline clipboard file must contain a JSON array: " + path.toAbsolutePath());
        }

        java.util.ArrayList<ClipboardRecord> records = new java.util.ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonNode node = array.get(index);
            String type = node.path("type").asText("");
            if (!type.isEmpty() && !"clipboard".equals(type)) {
                continue;
            }
            String context = "offline entry " + index;
            records.add(new ClipboardRecord(
                    requiredText(node, "clientId"),
                    requiredText(node, "content"),
                    parseTimestamp(requiredText(node, "timestamp"), context)
            ));
        }
        return List.copyOf(records);
    }

    private static String singleClientId(List<ClipboardRecord> records) {
        Set<String> clientIds = records.stream().map(ClipboardRecord::clientId).collect(java.util.stream.Collectors.toSet());
        if (clientIds.size() != 1) {
            throw new IllegalStateException("CLIENT_ID is required when the offline file contains multiple client IDs.");
        }
        return clientIds.iterator().next();
    }

    private static void ensureRecordsBelongToClient(List<ClipboardRecord> records, String expectedClientId) {
        if (records.stream().anyMatch(record -> !record.clientId().equals(expectedClientId))) {
            throw new IllegalArgumentException("Offline clipboard file contains entries for a client other than " + expectedClientId + ".");
        }
    }

    private static URI clipboardEndpoint(String remoteUrl) {
        String trimmed = remoteUrl.trim();
        return URI.create(trimmed.endsWith("/clipboard") ? trimmed : trimmed.replaceAll("/+$", "") + "/clipboard");
    }

    private static Duration syncInterval(long minutes) {
        if (minutes < 1) {
            throw new IllegalArgumentException(ClientEnvs.OFFLINE_SYNC_INTERVAL_MINUTES.name()
                    + " must be at least 1.");
        }
        return Duration.ofMinutes(minutes);
    }

    private static String requiredText(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IOException("Clipboard JSON entry is missing string field '" + field + "'.");
        }
        return value.textValue();
    }

    private static Instant parseTimestamp(String value, String context) throws IOException {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IOException("Invalid timestamp in " + context + ": " + value, exception);
        }
    }

    private static long requiredLong(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IOException("Clipboard JSON entry is missing integer field '" + field + "'.");
        }
        return value.longValue();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void requireSuccess(HttpResponse<String> response, String operation) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Could not " + operation + ": HTTP " + response.statusCode() + " " + response.body());
        }
    }

    public record ClipboardRecord(String clientId, String content, Instant timestamp) {
        private RecordKey key() {
            return new RecordKey(clientId, content, timestamp);
        }
    }

    record SyncResult(int alreadyPresent, int sent) {
    }

    record ClipboardSnapshot(String content, List<ClipboardRecord> records) {
        ClipboardSnapshot {
            records = List.copyOf(records);
        }
    }

    @FunctionalInterface
    interface RecordSource {
        ClipboardSnapshot read() throws IOException;
    }

    @FunctionalInterface
    interface SnapshotClearer {
        boolean clear(ClipboardSnapshot snapshot) throws IOException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record RecordKey(String clientId, String content, Instant timestamp) {
    }

    private record RecordContentKey(String clientId, String content) {
    }

    private static final class RemoteRecordIndex {
        private static final long TIMESTAMP_TOLERANCE_NANOS = 999;
        private final Map<RecordContentKey, NavigableSet<Instant>> timestamps = new HashMap<>();

        void add(RecordKey record) {
            timestamps.computeIfAbsent(
                    new RecordContentKey(record.clientId(), record.content()), ignored -> new TreeSet<>())
                    .add(record.timestamp());
        }

        boolean contains(RecordKey candidate) {
            NavigableSet<Instant> matchingTimestamps = timestamps.get(
                    new RecordContentKey(candidate.clientId(), candidate.content()));
            if (matchingTimestamps == null) {
                return false;
            }
            Instant closest = matchingTimestamps.ceiling(
                    candidate.timestamp().minusNanos(TIMESTAMP_TOLERANCE_NANOS));
            return closest != null
                    && !closest.isAfter(candidate.timestamp().plusNanos(TIMESTAMP_TOLERANCE_NANOS));
        }
    }
}
