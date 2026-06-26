package dev.clippy.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void logsLoginAndTokenCheckRequests() throws IOException {
        String originalLoggerDir = System.getProperty("custom.logger.dir");
        System.setProperty("custom.logger.dir", tempDir.toString());

        try {
            ClientIdentityRepository identities = mock(ClientIdentityRepository.class);
            ClientTokenRepository tokens = mock(ClientTokenRepository.class);
            CredentialHasher credentialHasher = new CredentialHasher();
            TokenGenerator tokenGenerator = mock(TokenGenerator.class);

            ClientIdentity identity = new ClientIdentity(
                    "dummy",
                    credentialHasher.hashSecret("change-me-please"),
                    Instant.parse("2026-06-25T12:00:00Z")
            );

            when(identities.findByClientId("dummy")).thenReturn(Optional.of(identity));
            when(tokenGenerator.newToken()).thenReturn("generated-token");
            when(tokens.save(any(ClientToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AuthController controller = new AuthController(identities, tokens, credentialHasher, tokenGenerator);

            LoginResponse loginResponse = controller.login(new LoginRequest("dummy", "change-me-please"));
            assertEquals("dummy", loginResponse.clientId());
            assertEquals("generated-token", loginResponse.token());

            String tokenHash = credentialHasher.hashToken("generated-token");
            when(tokens.findByTokenHash(tokenHash)).thenReturn(Optional.of(new ClientToken(identity, tokenHash, Instant.parse("2026-06-25T12:01:00Z"))));

            CheckTokenResponse checkTokenResponse = controller.checkToken(new CheckTokenRequest("dummy", "generated-token"));
            assertTrue(checkTokenResponse.valid());
            assertEquals("dummy", checkTokenResponse.clientId());

            String content = Files.readString(tempDir.resolve("auth-server.txt"));
            assertTrue(content.contains("Login request received for clientId=dummy"));
            assertTrue(content.contains("Issued login token for clientId=dummy"));
            assertTrue(content.contains("Token check request received for clientId=dummy"));
            assertTrue(content.contains("Token check completed for clientId=dummy, valid=true"));
        } finally {
            if (originalLoggerDir == null) {
                System.clearProperty("custom.logger.dir");
            } else {
                System.setProperty("custom.logger.dir", originalLoggerDir);
            }
        }
    }
}
