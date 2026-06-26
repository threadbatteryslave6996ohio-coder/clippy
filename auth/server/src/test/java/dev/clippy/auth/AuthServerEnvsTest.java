package dev.clippy.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.clippy.utils.envmanager.Env;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthServerEnvsTest {
    @TempDir
    Path tempDir;

    @Test
    void appliesDefaultsWhenValuesAreMissing() {
        Env env = AuthServerEnvs.from(Map.of());

        assertEquals("jdbc:postgresql://localhost:5433/auth", env.get(AuthServerEnvs.AUTH_DATASOURCE_URL));
        assertEquals("auth", env.get(AuthServerEnvs.AUTH_DATASOURCE_USERNAME));
        assertEquals("auth", env.get(AuthServerEnvs.AUTH_DATASOURCE_PASSWORD));
        assertEquals("8081", env.get(AuthServerEnvs.AUTH_SERVER_PORT));
        assertEquals("logs/clippy-auth-server.log", env.get(AuthServerEnvs.AUTH_LOGGING_FILE_NAME));
    }

    @Test
    void loadsDotenvFromAncestralDirectory() throws IOException {
        Path nestedDirectory = Files.createDirectories(tempDir.resolve("nested/child"));
        Files.writeString(
                tempDir.resolve(".env"),
                "AUTH_DATASOURCE_URL=jdbc:postgresql://example:5432/auth\n" +
                        "AUTH_DATASOURCE_USERNAME=example-user\n" +
                        "AUTH_DATASOURCE_PASSWORD=example-pass\n" +
                        "AUTH_SERVER_PORT=9090\n" +
                        "AUTH_LOGGING_FILE_NAME=/tmp/auth.log\n"
        );

        Env env = AuthServerEnvs.loadFrom(nestedDirectory);

        assertEquals("jdbc:postgresql://example:5432/auth", env.get(AuthServerEnvs.AUTH_DATASOURCE_URL));
        assertEquals("example-user", env.get(AuthServerEnvs.AUTH_DATASOURCE_USERNAME));
        assertEquals("example-pass", env.get(AuthServerEnvs.AUTH_DATASOURCE_PASSWORD));
        assertEquals("9090", env.get(AuthServerEnvs.AUTH_SERVER_PORT));
        assertEquals("/tmp/auth.log", env.get(AuthServerEnvs.AUTH_LOGGING_FILE_NAME));
    }
}
