package dev.clippy.server;

import dev.clippy.utils.envmanager.Env;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerEnvsTest {
    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        Env env = ServerEnvs.from(Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://database/clippy",
                "SPRING_DATASOURCE_USERNAME", "user",
                "SPRING_DATASOURCE_PASSWORD", "password",
                "SERVER_PORT", "9090",
                "CLIPPY_AUTH_BASE_URL", "http://auth",
                "LOGGING_FILE_NAME", "/tmp/clippy.log"
        ));

        assertEquals(Map.of(
                "spring.datasource.url", "jdbc:postgresql://database/clippy",
                "spring.datasource.username", "user",
                "spring.datasource.password", "password",
                "server.port", "9090",
                "clippy.auth.base-url", "http://auth",
                "logging.file.name", "/tmp/clippy.log"
        ), ServerEnvs.springDefaults(env));
    }
}
