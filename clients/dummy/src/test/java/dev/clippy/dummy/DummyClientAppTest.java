package dev.clippy.dummy;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DummyClientAppTest {
    @Test
    void joinsCommandLineArguments() throws Exception {
        assertEquals("hello world from clippy", invokeStaticString("joinArgs", new String[] {"hello", "world", "from", "clippy"}));
    }

    private static String invokeStaticString(String methodName, String[] arguments) throws Exception {
        Method method = DummyClientApp.class.getDeclaredMethod(methodName, String[].class);
        method.setAccessible(true);
        return (String) method.invoke(null, (Object) arguments);
    }

}
