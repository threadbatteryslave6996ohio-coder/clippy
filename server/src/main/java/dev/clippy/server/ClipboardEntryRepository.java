package dev.clippy.server;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ClipboardEntryRepository extends JpaRepository<ClipboardEntry, Long> {
    List<ClipboardEntry> findByClientIdAndTimestampBetweenOrderByTimestampAscIdAsc(
            String clientId,
            Instant from,
            Instant to
    );
}
