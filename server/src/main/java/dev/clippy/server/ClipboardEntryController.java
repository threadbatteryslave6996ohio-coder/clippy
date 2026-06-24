package dev.clippy.server;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
public class ClipboardEntryController {
    private final ClipboardEntryRepository repository;

    public ClipboardEntryController(ClipboardEntryRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/clipboard")
    @ResponseStatus(HttpStatus.CREATED)
    public ClipboardEntryResponse create(@Valid @RequestBody ClipboardEntryRequest request) {
        Instant timestamp = request.timestamp() == null ? Instant.now() : request.timestamp();
        ClipboardEntry saved = repository.save(new ClipboardEntry(
                request.clientId(),
                request.content(),
                timestamp
        ));
        return new ClipboardEntryResponse(saved.getId(), saved.getClientId(), saved.getTimestamp());
    }
}
