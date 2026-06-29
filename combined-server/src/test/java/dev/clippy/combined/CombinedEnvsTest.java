package dev.clippy.combined;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CombinedEnvsTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultEnvFileResolvesFromTheCombinedServerModuleDirectory() throws Exception {
        Path codeSourceLocation = Path.of(
                CombinedEnvs.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toAbsolutePath().normalize();
        Path unrelatedWorkingDirectory = tempDir.resolve("work");
        Files.createDirectories(unrelatedWorkingDirectory);
        Path expected = codeSourceLocation.getParent().getParent().resolve(".env");

        assertThat(CombinedEnvs.defaultEnvFile(codeSourceLocation, unrelatedWorkingDirectory)).isEqualTo(expected);
    }

    @Test
    void defaultEnvFilePrefersCurrentWorkingDirectoryEnvFile() throws Exception {
        Path codeSourceLocation = Path.of(
                CombinedEnvs.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toAbsolutePath().normalize();
        Path envFile = tempDir.resolve(".env");
        Files.writeString(envFile, "COMBINED_SERVER_PORT=8080\n");

        assertThat(CombinedEnvs.defaultEnvFile(codeSourceLocation, tempDir)).isEqualTo(envFile);
    }

    @Test
    void defaultEnvFileReportsSimpleActionableErrorWhenModuleDirectoryCannotBeResolved() {
        Path workingDirectoryWithoutEnv = tempDir.resolve("missing-env");
        assertThatThrownBy(() -> CombinedEnvs.defaultEnvFile(Path.of("/"), workingDirectoryWithoutEnv))
                .isInstanceOf(IOException.class)
                .hasMessage("Missing combined server env file. Create combined-server/.env or set CLIPPY_ENV_FILE to its absolute path.");
    }

    @Test
    void startupErrorMessageUsesSingleConcisePrefix() {
        IOException error = new IOException("Missing combined server env file. Create combined-server/.env or set CLIPPY_ENV_FILE to its absolute path.");

        assertThat(CombinedServerApplication.startupErrorMessage(error))
                .isEqualTo("Combined server startup error: Missing combined server env file. Create combined-server/.env or set CLIPPY_ENV_FILE to its absolute path.");
    }
}
