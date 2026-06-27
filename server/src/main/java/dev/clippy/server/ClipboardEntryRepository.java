package dev.clippy.server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ClipboardEntryRepository extends JpaRepository<ClipboardEntry, Long> {
    Optional<ClipboardEntry> findFirstByClientIdOrderByIdDesc(String clientId);

    List<ClipboardEntry> findByClientIdAndTimestampBetweenOrderByTimestampAscIdAsc(
            String clientId,
            Instant from,
            Instant to
    );
}
