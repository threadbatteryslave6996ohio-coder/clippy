package dev.clippy.linux;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LinuxClipboardClientAppTest {
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
}
