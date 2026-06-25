package dev.clippy.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClipboardEntryHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ClipboardEntryRepository repository;

    @MockBean
    private AuthTokenVerifier authTokenVerifier;

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    @Test
    void createsClipboardEntryFromHttpRequestAndPersistsItInPostgres() {
        Instant timestamp = Instant.parse("2026-06-23T12:00:00Z");
        String json = """
                {
                  "clientId": "android-pixel-8",
                  "content": "clipboard text",
                  "timestamp": "%s"
                }
                """.formatted(timestamp);

        when(authTokenVerifier.isTokenValidForClient("android-pixel-8", "valid-token")).thenReturn(true);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth("valid-token");

        ResponseEntity<ClipboardEntryResponse> response = restTemplate.postForEntity(
                "http://localhost:%d/clipboard".formatted(port),
                new HttpEntity<>(json, headers),
                ClipboardEntryResponse.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isNotNull();
        assertThat(response.getBody().clientId()).isEqualTo("android-pixel-8");
        assertThat(response.getBody().timestamp()).isEqualTo(timestamp);

        List<ClipboardEntry> entries = repository.findAll();
        assertThat(entries).hasSize(1);

        ClipboardEntry saved = entries.get(0);
        assertThat(saved.getId()).isEqualTo(response.getBody().id());
        assertThat(saved.getClientId()).isEqualTo("android-pixel-8");
        assertThat(saved.getContent()).isEqualTo("clipboard text");
        assertThat(saved.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    void rejectsClipboardEntryWithoutBearerToken() {
        String json = """
                {
                  "clientId": "android-pixel-8",
                  "content": "clipboard text"
                }
                """;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "http://localhost:%d/clipboard".formatted(port),
                new HttpEntity<>(json, headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(repository.findAll()).isEmpty();
    }
}
