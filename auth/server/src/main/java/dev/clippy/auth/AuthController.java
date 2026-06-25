package dev.clippy.auth;

import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
public class AuthController {
    private final ClientIdentityRepository identities;
    private final ClientTokenRepository tokens;
    private final CredentialHasher credentialHasher;
    private final TokenGenerator tokenGenerator;

    public AuthController(
            ClientIdentityRepository identities,
            ClientTokenRepository tokens,
            CredentialHasher credentialHasher,
            TokenGenerator tokenGenerator
    ) {
        this.identities = identities;
        this.tokens = tokens;
        this.credentialHasher = credentialHasher;
        this.tokenGenerator = tokenGenerator;
    }

    @PostMapping("/identities")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityResponse createIdentity(@Valid @RequestBody CreateIdentityRequest request) {
        if (identities.existsByClientId(request.clientId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Client identity already exists.");
        }

        ClientIdentity identity = new ClientIdentity(
                request.clientId(),
                credentialHasher.hashSecret(request.secret()),
                Instant.now()
        );

        try {
            ClientIdentity saved = identities.save(identity);
            return new IdentityResponse(saved.getClientId(), saved.getCreatedAt());
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Client identity already exists.", exception);
        }
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        ClientIdentity identity = identities.findByClientId(request.clientId())
                .filter(ClientIdentity::isActive)
                .filter(candidate -> credentialHasher.matches(request.secret(), candidate.getSecretHash()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client credentials."));

        String token = tokenGenerator.newToken();
        tokens.save(new ClientToken(identity, credentialHasher.hashToken(token), Instant.now()));

        return new LoginResponse(identity.getClientId(), token);
    }

    @PostMapping("/tokens/check")
    public CheckTokenResponse checkToken(@Valid @RequestBody CheckTokenRequest request) {
        boolean valid = tokens.findByTokenHash(credentialHasher.hashToken(request.token()))
                .map(ClientToken::getIdentity)
                .filter(ClientIdentity::isActive)
                .map(ClientIdentity::getClientId)
                .filter(request.clientId()::equals)
                .isPresent();

        return new CheckTokenResponse(valid, request.clientId());
    }
}
