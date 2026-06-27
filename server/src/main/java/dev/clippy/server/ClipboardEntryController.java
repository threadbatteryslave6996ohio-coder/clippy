package dev.clippy.server;

import dev.clippy.utils.logger.CustomLogger;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
public class ClipboardEntryController {
    private static final CustomLogger LOGGER = new CustomLogger("clippy-server");

    private final ClipboardEntryRepository repository;
    private final AuthTokenVerifier authTokenVerifier;

    public ClipboardEntryController(ClipboardEntryRepository repository, AuthTokenVerifier authTokenVerifier) {
        this.repository = repository;
        this.authTokenVerifier = authTokenVerifier;
    }

    @PostMapping("/clipboard")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized ClipboardEntryResponse create(
            @Valid @RequestBody ClipboardEntryRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        String token = bearerToken(authorization);
        if (!authTokenVerifier.isTokenValidForClient(request.clientId(), token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client token.");
        }

        ClipboardEntry latest = repository.findFirstByClientIdOrderByIdDesc(request.clientId()).orElse(null);
        if (latest != null && latest.getContent().equals(request.content())) {
            return new ClipboardEntryResponse(latest.getId(), latest.getClientId(), latest.getTimestamp());
        }

        Instant timestamp = request.timestamp() == null ? Instant.now() : request.timestamp();
        ClipboardEntry saved = repository.save(new ClipboardEntry(
                request.clientId(),
                request.content(),
                timestamp
        ));
        logClipboardEntrySaved(saved);
        return new ClipboardEntryResponse(saved.getId(), saved.getClientId(), saved.getTimestamp());
    }

    @GetMapping("/clipboard")
    public List<ClipboardEntryDetailsResponse> findWithinTimeframe(
            @RequestParam("clientId") String clientId,
            @RequestParam("from") Instant from,
            @RequestParam("to") Instant to,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        String token = bearerToken(authorization);
        if (!authTokenVerifier.isTokenValidForClient(clientId, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client token.");
        }
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before or equal to to.");
        }

        return repository.findByClientIdAndTimestampBetweenOrderByTimestampAscIdAsc(clientId, from, to)
                .stream()
                .map(ClipboardEntryDetailsResponse::from)
                .toList();
    }

    private static void logClipboardEntrySaved(ClipboardEntry saved) {
        try {
            LOGGER.log("Added clipboard entry for clientId=" + saved.getClientId()
                    + ", entryId=" + saved.getId()
                    + " at " + saved.getTimestamp());
        } catch (RuntimeException exception) {
            // Audit logging is best-effort; a logging failure must not reject the write.
        }
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }

        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expected bearer token.");
        }

        String token = authorization.substring(prefix.length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }
        return token;
    }
}
