package dev.clippy.combined;

import dev.clippy.auth.ClientIdentityRepository;
import dev.clippy.auth.ClientTokenRepository;
import dev.clippy.server.ClipboardEntry;
import dev.clippy.server.ClipboardEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = CombinedServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
                "clippy.auth.route-prefix=/auth",
                "clippy.server.route-prefix=/api",
                "clippy.auth.base-url=http://localhost:18080/auth",
                "clippy.auth.jpa.hibernate.ddl-auto=create-drop",
                "clippy.clipboard.jpa.hibernate.ddl-auto=create-drop"
        }
)
class CombinedServerHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> authPostgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final PostgreSQLContainer<?> clipboardPostgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("AUTH_DATASOURCE_URL", authPostgres::getJdbcUrl);
        registry.add("AUTH_DATASOURCE_USERNAME", authPostgres::getUsername);
        registry.add("AUTH_DATASOURCE_PASSWORD", authPostgres::getPassword);
        registry.add("SPRING_DATASOURCE_URL", clipboardPostgres::getJdbcUrl);
        registry.add("SPRING_DATASOURCE_USERNAME", clipboardPostgres::getUsername);
        registry.add("SPRING_DATASOURCE_PASSWORD", clipboardPostgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ClientIdentityRepository identityRepository;

    @Autowired
    private ClientTokenRepository tokenRepository;

    @Autowired
    private ClipboardEntryRepository clipboardEntryRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void clearDatabases() {
        clipboardEntryRepository.deleteAll();
        tokenRepository.deleteAll();
        identityRepository.deleteAll();
    }

    @Test
    void combinedServerCreatesIdentityLogsInAndStoresClipboardEntry() throws Exception {
        HttpResponse<String> createIdentity = post(
                "/auth/identities",
                """
                        {
                          "clientId": "android-pixel-8",
                          "secret": "change-me-please"
                        }
                        """,
                null
        );

        HttpResponse<String> login = post(
                "/auth/login",
                """
                        {
                          "clientId": "android-pixel-8",
                          "secret": "change-me-please"
                        }
                        """,
                null
        );

        String token = jsonValue(login.body(), "token");
        HttpResponse<String> clipboardWrite = post(
                "/api/clipboard",
                """
                        {
                          "clientId": "android-pixel-8",
                          "content": "clipboard text",
                          "timestamp": "2026-06-23T12:00:00Z"
                        }
                        """,
                token
        );

        assertThat(createIdentity.statusCode()).isEqualTo(201);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(token).isNotBlank();
        assertThat(clipboardWrite.statusCode()).isEqualTo(201);
        assertThat(identityRepository.count()).isEqualTo(1);
        assertThat(clipboardEntryRepository.count()).isEqualTo(1);
        ClipboardEntry saved = clipboardEntryRepository.findAll().getFirst();
        assertThat(saved.getClientId()).isEqualTo("android-pixel-8");
        assertThat(saved.getContent()).isEqualTo("clipboard text");
    }

    @Test
    void combinedServerRejectsClipboardWriteWhenTokenIsInvalid() throws Exception {
        post(
                "/auth/identities",
                """
                        {
                          "clientId": "android-pixel-8",
                          "secret": "change-me-please"
                        }
                        """,
                null
        );

        HttpResponse<String> response = post(
                "/api/clipboard",
                """
                        {
                          "clientId": "android-pixel-8",
                          "content": "clipboard text",
                          "timestamp": "2026-06-23T12:00:00Z"
                        }
                        """,
                "invalid-token"
        );

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(clipboardEntryRepository.count()).isZero();
    }

    private HttpResponse<String> post(String path, String json, String bearerToken) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing JSON key: " + key);
        }
        int valueStart = start + marker.length();
        int valueEnd = json.indexOf('"', valueStart);
        return json.substring(valueStart, valueEnd);
    }
}
