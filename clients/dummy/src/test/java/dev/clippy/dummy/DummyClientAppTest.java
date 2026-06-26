package dev.clippy.dummy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DummyClientAppTest {
    @Test
    void normalizesClipboardEndpoints() throws Exception {
        assertEquals(URI.create("http://localhost:8080/clipboard"), invokeStaticUri("clipboardEndpoint", "http://localhost:8080"));
        assertEquals(URI.create("http://localhost:8080/clipboard"), invokeStaticUri("clipboardEndpoint", "http://localhost:8080/"));
        assertEquals(URI.create("http://localhost:8080/clipboard"), invokeStaticUri("clipboardEndpoint", "http://localhost:8080/clipboard"));
    }

    @Test
    void joinsCommandLineArgumentsAndEscapesJson() throws Exception {
        assertEquals("hello world from clippy", invokeStaticString("joinArgs", new String[] {"hello", "world", "from", "clippy"}));
        assertEquals("quote\\\" newline\\n tab\\t slash\\\\", invokeStaticString("jsonEscape", "quote\" newline\n tab\t slash\\"));
    }

    private static URI invokeStaticUri(String methodName, String argument) throws Exception {
        Method method = DummyClientApp.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (URI) method.invoke(null, argument);
    }

    private static String invokeStaticString(String methodName, String[] arguments) throws Exception {
        Method method = DummyClientApp.class.getDeclaredMethod(methodName, String[].class);
        method.setAccessible(true);
        return (String) method.invoke(null, (Object) arguments);
    }

    private static String invokeStaticString(String methodName, String argument) throws Exception {
        Method method = DummyClientApp.class.getDeclaredMethod(methodName, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, argument);
    }
}
