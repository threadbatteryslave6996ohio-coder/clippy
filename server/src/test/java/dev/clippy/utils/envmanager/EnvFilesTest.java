package dev.clippy.utils.envmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EnvFilesTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsValuesFromExplicitDotenvFile() throws IOException {
        Path dotenvFile = tempDir.resolve("combined.env");
        Files.writeString(
                dotenvFile,
                """
                        COMBINED_SERVER_PORT=8080
                        CLIPPY_AUTH_ROUTE_PREFIX=/auth
                        """
        );

        Map<String, String> values = EnvFiles.loadFile(dotenvFile);

        assertEquals("8080", values.get("COMBINED_SERVER_PORT"));
        assertEquals("/auth", values.get("CLIPPY_AUTH_ROUTE_PREFIX"));
    }

    @Test
    void loadRequiredFileRejectsMissingFile() {
        Path dotenvFile = tempDir.resolve("missing.env");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> EnvFiles.loadRequiredFile(dotenvFile));

        assertEquals("Env file is not present: " + dotenvFile.toAbsolutePath().normalize(), exception.getMessage());
    }
}
