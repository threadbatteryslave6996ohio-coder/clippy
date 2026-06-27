package dev.clippy.client;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClipboardClientAppTest {
    @Test
    void normalizesClipboardEndpoints() throws Exception {
        assertEquals(URI.create("http://localhost:8080/clipboard"), invokeStaticUri("clipboardEndpoint", "http://localhost:8080"));
        assertEquals(URI.create("http://localhost:8080/clipboard"), invokeStaticUri("clipboardEndpoint", "http://localhost:8080/"));
        assertEquals(URI.create("http://localhost:8080/clipboard"), invokeStaticUri("clipboardEndpoint", "http://localhost:8080/clipboard"));
    }

    @Test
    void escapesJsonContent() throws Exception {
        assertEquals("quote\\\" newline\\n tab\\t slash\\\\", invokeStaticString("jsonEscape", "quote\" newline\n tab\t slash\\"));
    }

    @Test
    void clipboardPayloadSerializesEscapedJson() throws Exception {
        Object payload = newPayload("client-a", "line1\nline2", Instant.parse("2026-06-26T18:00:00Z"));
        Method method = payload.getClass().getDeclaredMethod("toJson");
        method.setAccessible(true);

        String json = (String) method.invoke(payload);

        assertEquals("""
                {"clientId":"client-a","content":"line1\\nline2","timestamp":"2026-06-26T18:00:00Z"}""", json);
    }

    private static URI invokeStaticUri(String methodName, String argument) throws Exception {
        Method method = ClipboardClientApp.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (URI) method.invoke(null, argument);
    }

    private static String invokeStaticString(String methodName, String argument) throws Exception {
        Method method = ClipboardClientApp.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, argument);
    }

    private static Object newPayload(String clientId, String content, Instant timestamp) throws Exception {
        Class<?> payloadClass = Class.forName("dev.clippy.client.ClipboardClientApp$ClipboardPayload");
        Constructor<?> constructor = payloadClass.getDeclaredConstructor(String.class, String.class, Instant.class);
        constructor.setAccessible(true);
        return constructor.newInstance(clientId, content, timestamp);
    }
}
