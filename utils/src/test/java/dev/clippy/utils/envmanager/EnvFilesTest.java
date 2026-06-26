package dev.clippy.utils.envmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnvFilesTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsDotenvFromAncestralDirectoryAndUnquotesValues() throws IOException {
        Path nestedDirectory = Files.createDirectories(tempDir.resolve("nested/child"));
        Files.writeString(
                tempDir.resolve(".env"),
                """
                        # comment
                        REMOTE_SERVER_URL = "http://localhost:8080"
                        CLIENT_ID='clippy-client'
                        CLIENT_TOKEN=token-123
                        EMPTY_LINE=
                        INVALID_LINE
                        """
        );

        Map<String, String> values = EnvFiles.load(nestedDirectory);

        assertEquals("http://localhost:8080", values.get("REMOTE_SERVER_URL"));
        assertEquals("clippy-client", values.get("CLIENT_ID"));
        assertEquals("token-123", values.get("CLIENT_TOKEN"));
    }
}
