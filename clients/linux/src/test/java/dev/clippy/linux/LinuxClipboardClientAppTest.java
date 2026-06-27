package dev.clippy.linux;

import com.sun.net.httpserver.HttpServer;
import dev.clippy.clients.envs.ClientAuthSession;
import dev.clippy.filelocker.OfflineFileLockerClient;
import dev.clippy.filelocker.OfflineFileLockerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxClipboardClientAppTest {
    @TempDir
    Path tempDir;

    @Test
    void normalizesClipboardEndpoints() throws Exception {
        assertEquals(URI.create("http://localhost:8080/clipboard"), invokeStaticUri("clipboardEndpoint", "http://localhost:8080"));
        assertEquals(URI.create("http://localhost:8080/clipboard"), invokeStaticUri("clipboardEndpoint", "http://localhost:8080/"));
        assertEquals(URI.create("http://localhost:8080/clipboard"), invokeStaticUri("clipboardEndpoint", "http://localhost:8080/clipboard"));
    }

    @Test
    void validatesPollIntervals() throws Exception {
        assertEquals(100L, invokeStaticLong("validatePollIntervalMs", 100L));
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> invokeStaticLong("validatePollIntervalMs", 99L));
        assertEquals("CLIPBOARD_POLL_INTERVAL_MS must be at least 100.", exception.getMessage());
    }

    @Test
    void escapesJsonContentAndSerializesClipboardPayload() throws Exception {
        assertEquals("quote\\\" newline\\n tab\\t slash\\\\", invokeStaticString("jsonEscape", "quote\" newline\n tab\t slash\\"));

        Object payload = newPayload("client-a", "line1\nline2", Instant.parse("2026-06-26T18:00:00Z"));
        Method method = payload.getClass().getDeclaredMethod("toJson");
        method.setAccessible(true);

        String json = (String) method.invoke(payload);

        assertEquals("""
                {"clientId":"client-a","content":"line1\\nline2","timestamp":"2026-06-26T18:00:00Z"}""", json);
    }

    @Test
    void stripsDesktopLaunchMetadataFromClipboardHelperEnvironment() {
        Map<String, String> environment = new HashMap<>(Map.of(
                "PATH", "/usr/bin",
                "DESKTOP_STARTUP_ID", "startup-id",
                "XDG_ACTIVATION_TOKEN", "activation-token",
                "GIO_LAUNCHED_DESKTOP_FILE", "/tmp/clippy.desktop",
                "GIO_LAUNCHED_DESKTOP_FILE_PID", "123",
                "BAMF_DESKTOP_FILE_HINT", "/tmp/clippy.desktop"
        ));

        LinuxClipboardClientApp.stripDesktopLaunchEnvironment(environment);

        assertEquals(Map.of("PATH", "/usr/bin"), environment);
    }

    @Test
    void retriesPendingOfflinePayloadBeforeSkippingNullClipboardContent() throws Exception {
        Path socket = tempDir.resolve("locker.sock");
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
            LinuxClipboardClientApp app = newApp(
                    newClipboardReader(null),
                    URI.create("http://localhost:8080/clipboard"),
                    new ClientAuthSession(null, "client-a", null, "token"),
                    new OfflineFileLockerClient(socket)
            );
            setField(app, "pendingOfflinePayload", newPayload("client-a", "pending text", Instant.parse("2026-06-27T12:00:00Z")));

            invokePoll(app);

            assertEquals("pending text", getField(app, "lastSentContent"));
            assertFalse(hasPendingOfflinePayload(app));
        } finally {
            Files.deleteIfExists(Path.of("clippy-offline-clipboard.json"));
            service.close();
            serviceThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void flushesPendingOfflinePayloadWhenClipboardReadFails() throws Exception {
        Path socket = tempDir.resolve("locker.sock");
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
            LinuxClipboardClientApp app = newApp(
                    newClipboardReaderThrows(new java.io.IOException("clipboard helper failed")),
                    URI.create("http://localhost:8080/clipboard"),
                    new ClientAuthSession(null, "client-a", null, "token"),
                    new OfflineFileLockerClient(socket)
            );
            Object pendingPayload = newPayload("client-a", "pending text", Instant.parse("2026-06-27T12:00:00Z"));
            setField(app, "pendingOfflinePayload", pendingPayload);

            invokePoll(app);

            assertEquals("pending text", getField(app, "lastSentContent"));
            assertFalse(hasPendingOfflinePayload(app));
            assertEquals("""
                    [
                      {"clientId":"client-a","content":"pending text","timestamp":"2026-06-27T12:00:00Z"}
                    ]
                    """,
                    new OfflineFileLockerClient(socket).read(Path.of("clippy-offline-clipboard.json")));
        } finally {
            Files.deleteIfExists(Path.of("clippy-offline-clipboard.json"));
            service.close();
            serviceThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    @Test
    void sendsPayloadTimestampInHttpRequestBody() throws Exception {
        CountDownLatch requestSeen = new CountDownLatch(1);
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/clipboard", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            requestSeen.countDown();
        });
        server.start();

        try {
            LinuxClipboardClientApp app = newApp(
                    newClipboardReader("ignored"),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/clipboard"),
                    new ClientAuthSession(null, "client-a", null, "token"),
                    new OfflineFileLockerClient(tempDir.resolve("unused.sock"))
            );
            Object payload = newPayload("client-a", "hello", Instant.parse("2026-06-27T15:30:45Z"));

            assertEquals(204, invokeSend(app, payload, "token"));
            assertTrue(requestSeen.await(5, TimeUnit.SECONDS));
            assertEquals("""
                    {"clientId":"client-a","content":"hello","timestamp":"2026-06-27T15:30:45Z"}""", requestBody.get());
        } finally {
            server.stop(0);
        }
    }

    private static URI invokeStaticUri(String methodName, String argument) throws Exception {
        Method method = LinuxClipboardClientApp.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (URI) method.invoke(null, argument);
    }

    private static long invokeStaticLong(String methodName, long argument) throws Exception {
        Method method = LinuxClipboardClientApp.class.getDeclaredMethod(methodName, long.class);
        method.setAccessible(true);
        try {
            return (long) method.invoke(null, argument);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception cast) {
                throw cast;
            }
            throw exception;
        }
    }

    private static String invokeStaticString(String methodName, String argument) throws Exception {
        Method method = LinuxClipboardClientApp.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, argument);
    }

    private static Object newPayload(String clientId, String content, Instant timestamp) throws Exception {
        Class<?> payloadClass = Class.forName("dev.clippy.linux.LinuxClipboardClientApp$ClipboardPayload");
        Constructor<?> constructor = payloadClass.getDeclaredConstructor(String.class, String.class, Instant.class);
        constructor.setAccessible(true);
        return constructor.newInstance(clientId, content, timestamp);
    }

    private static LinuxClipboardClientApp newApp(
            Object clipboardReader,
            URI endpoint,
            ClientAuthSession authSession,
            OfflineFileLockerClient fileLocker
    ) throws Exception {
        Class<?> clipboardReaderClass = Class.forName("dev.clippy.linux.LinuxClipboardClientApp$ClipboardReader");
        Constructor<LinuxClipboardClientApp> constructor = LinuxClipboardClientApp.class.getDeclaredConstructor(
                clipboardReaderClass,
                URI.class,
                String.class,
                String.class,
                ClientAuthSession.class,
                OfflineFileLockerClient.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(clipboardReader, endpoint, null, "client-a", authSession, fileLocker);
    }

    private static Object newClipboardReader(String content) throws Exception {
        Class<?> clipboardReaderClass = Class.forName("dev.clippy.linux.LinuxClipboardClientApp$ClipboardReader");
        return Proxy.newProxyInstance(
                LinuxClipboardClientAppTest.class.getClassLoader(),
                new Class<?>[]{clipboardReaderClass},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> "test";
                    case "isAvailable" -> true;
                    case "readText" -> content;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static Object newClipboardReaderThrows(Exception exception) throws Exception {
        Class<?> clipboardReaderClass = Class.forName("dev.clippy.linux.LinuxClipboardClientApp$ClipboardReader");
        return Proxy.newProxyInstance(
                LinuxClipboardClientAppTest.class.getClassLoader(),
                new Class<?>[]{clipboardReaderClass},
                (proxy, method, args) -> switch (method.getName()) {
                    case "name" -> "test";
                    case "isAvailable" -> true;
                    case "readText" -> throw exception;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private static void invokePoll(LinuxClipboardClientApp app) throws Exception {
        Method method = LinuxClipboardClientApp.class.getDeclaredMethod("pollAndSendIfChanged");
        method.setAccessible(true);
        method.invoke(app);
    }

    private static int invokeSend(LinuxClipboardClientApp app, Object payload, String token) throws Exception {
        Class<?> payloadClass = Class.forName("dev.clippy.linux.LinuxClipboardClientApp$ClipboardPayload");
        Method method = LinuxClipboardClientApp.class.getDeclaredMethod("send", payloadClass, String.class);
        method.setAccessible(true);
        return (int) method.invoke(app, payload, token);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean hasPendingOfflinePayload(LinuxClipboardClientApp app) throws Exception {
        return getField(app, "pendingOfflinePayload") != null;
    }

    private static void waitForSocket(Path socket) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!java.nio.file.Files.exists(socket)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("File-locker socket was not created.");
            }
            Thread.sleep(10);
        }
    }
}
