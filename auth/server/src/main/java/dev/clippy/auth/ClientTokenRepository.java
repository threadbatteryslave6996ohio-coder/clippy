package dev.clippy.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClientTokenRepository extends JpaRepository<ClientToken, Long> {
    Optional<ClientToken> findByTokenHash(String tokenHash);
}
