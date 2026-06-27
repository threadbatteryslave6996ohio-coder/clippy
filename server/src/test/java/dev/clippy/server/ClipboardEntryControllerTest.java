package dev.clippy.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardEntryControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void logsClipboardEntryCreationForClient() throws IOException {
        String originalLoggerDir = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", tempDir.toString());

        try {
            ClipboardEntryRepository repository = clipboardEntryRepository();
            AuthTokenVerifier authTokenVerifier = (clientId, token) -> "android-pixel-8".equals(clientId)
                    && "valid-token".equals(token);

            ClipboardEntryController controller = new ClipboardEntryController(repository, authTokenVerifier);
            ClipboardEntryResponse response = controller.create(
                    new ClipboardEntryRequest("android-pixel-8", "clipboard text", Instant.parse("2026-06-23T12:00:00Z")),
                    "Bearer valid-token"
            );

            assertThat(response.clientId()).isEqualTo("android-pixel-8");
            assertThat(response.id()).isEqualTo(42L);

            String content = Files.readString(tempDir.resolve("clippy-server.txt"));
            assertThat(content).contains("Added clipboard entry for clientId=android-pixel-8");
            assertThat(content).contains("entryId=42");
            assertThat(content).doesNotContain("clipboard text");
        } finally {
            if (originalLoggerDir == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", originalLoggerDir);
            }
        }
    }

    @Test
    void clipboardWriteStillSucceedsWhenAuditLoggingFails() throws IOException {
        Path loggerTarget = Files.createTempFile(tempDir, "logger-target", ".txt");
        String originalLoggerDir = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", loggerTarget.toString());

        try {
            ClipboardEntryRepository repository = clipboardEntryRepository();
            AuthTokenVerifier authTokenVerifier = (clientId, token) -> "android-pixel-8".equals(clientId)
                    && "valid-token".equals(token);

            ClipboardEntryController controller = new ClipboardEntryController(repository, authTokenVerifier);
            ClipboardEntryResponse response = controller.create(
                    new ClipboardEntryRequest("android-pixel-8", "clipboard text", Instant.parse("2026-06-23T12:00:00Z")),
                    "Bearer valid-token"
            );

            assertThat(response.clientId()).isEqualTo("android-pixel-8");
            assertThat(response.id()).isEqualTo(42L);
        } finally {
            if (originalLoggerDir == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", originalLoggerDir);
            }
        }
    }

    @Test
    void returnsClipboardEntriesWithinAuthenticatedClientTimeframe() {
        Instant from = Instant.parse("2026-06-23T12:00:00Z");
        Instant to = Instant.parse("2026-06-23T13:00:00Z");
        ClipboardEntryRepository repository = clipboardEntryRepository(List.of(
                new ClipboardEntry("client-a", "first", from),
                new ClipboardEntry("client-a", "second", to)
        ));
        AuthTokenVerifier authTokenVerifier = (clientId, token) -> "client-a".equals(clientId)
                && "valid-token".equals(token);

        ClipboardEntryController controller = new ClipboardEntryController(repository, authTokenVerifier);
        List<ClipboardEntryDetailsResponse> response = controller.findWithinTimeframe(
                "client-a", from, to, "Bearer valid-token"
        );

        assertThat(response).extracting(ClipboardEntryDetailsResponse::content)
                .containsExactly("first", "second");
    }

    private static ClipboardEntryRepository clipboardEntryRepository() {
        return clipboardEntryRepository(List.of());
    }

    private static ClipboardEntryRepository clipboardEntryRepository(List<ClipboardEntry> entries) {
        InvocationHandler handler = (proxy, method, args) -> handleRepositoryCall(method, args, entries);
        return (ClipboardEntryRepository) Proxy.newProxyInstance(
                ClipboardEntryRepository.class.getClassLoader(),
                new Class<?>[]{ClipboardEntryRepository.class},
                handler
        );
    }

    private static Object handleRepositoryCall(Method method, Object[] args, List<ClipboardEntry> entries) {
        if ("save".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof ClipboardEntry entry) {
            try {
                java.lang.reflect.Field idField = ClipboardEntry.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(entry, 42L);
                return entry;
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        }

        if ("findByClientIdAndTimestampBetweenOrderByTimestampAscIdAsc".equals(method.getName())) {
            return entries;
        }

        throw new UnsupportedOperationException("Unexpected repository call: " + method.getName());
    }
}
