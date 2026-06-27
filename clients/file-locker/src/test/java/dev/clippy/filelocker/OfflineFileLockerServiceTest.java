package dev.clippy.filelocker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineFileLockerServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void serializesConcurrentProcessRequestsWithoutLosingEntries() throws Exception {
        Path socket = tempDir.resolve("locker.sock");
        Path log = tempDir.resolve("offline.json");
        OfflineFileLockerService service = new OfflineFileLockerService(socket);
        Thread serviceThread = Thread.ofPlatform().start(() -> {
            try {
                service.run();
            } catch (java.nio.channels.AsynchronousCloseException ignored) {
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });

        try {
            waitForSocket(socket);
            OfflineFileLockerClient client = new OfflineFileLockerClient(socket);
            int entryCount = 40;
            client.append(log, "{\"value\":0}");
            try (ExecutorService writers = Executors.newFixedThreadPool(8)) {
                List<Future<Void>> writes = new ArrayList<>();
                for (int index = 1; index < entryCount; index++) {
                    int value = index;
                    writes.add(writers.submit(() -> {
                        client.append(log, "{\"value\":" + value + "}");
                        return null;
                    }));
                }
                writers.shutdown();
                do {
                    String snapshot = client.read(log);
                    assertTrue(snapshot.startsWith("[\n"));
                    assertTrue(snapshot.endsWith("]\n"));
                } while (!writers.awaitTermination(1, TimeUnit.MILLISECONDS));
                for (Future<Void> write : writes) {
                    write.get();
                }
            }

            String content = client.read(log);
            assertTrue(content.startsWith("[\n"));
            assertTrue(content.endsWith("]\n"));
            assertEquals(entryCount, content.split("\\{\\\"value\\\":", -1).length - 1);
        } finally {
            service.close();
            serviceThread.join(Duration.ofSeconds(5));
        }
    }

    private static void waitForSocket(Path socket) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!Files.exists(socket)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("File-locker socket was not created.");
            }
            Thread.sleep(10);
        }
    }
}
