package dev.clippy.combined;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CombinedEnvsTest {
    @Test
    void defaultEnvFileResolvesFromTheCombinedServerModuleDirectory() throws Exception {
        Path codeSourceLocation = Path.of(
                CombinedEnvs.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toAbsolutePath().normalize();
        Path expected = codeSourceLocation.getParent().getParent().resolve(".env");

        assertThat(CombinedEnvs.defaultEnvFile()).isEqualTo(expected);
    }
}
