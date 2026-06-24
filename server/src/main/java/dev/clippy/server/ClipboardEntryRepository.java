package dev.clippy.server;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClipboardEntryRepository extends JpaRepository<ClipboardEntry, Long> {
}
