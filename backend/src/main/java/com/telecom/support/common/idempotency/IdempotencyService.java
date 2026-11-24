package com.telecom.support.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.telecom.support.common.error.ApiExceptions.ConflictException;
import com.telecom.support.common.error.ApiExceptions.IdempotencyMismatchException;
import com.telecom.support.common.idempotency.IdempotencyRepository.StoredKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retry-safe execution of create / payment / reactivation endpoints (NFR-REL; 00-overview §7, 01 backend §6.1).
 *
 * <p>Called from the controller outside any business transaction; the supplier owns its own transaction.
 * <ol>
 *   <li>Claim: an IN_PROGRESS row is inserted and committed in its own {@code REQUIRES_NEW} transaction, so a concurrent
 *       duplicate fails at once with 409 {@code conflict}. For an existing row: a different request hash: 422
 *       {@code idempotency-mismatch}; COMPLETED: the stored status and body are replayed; IN_PROGRESS older than
 *       {@code app.idempotency.stale-after}: deleted and claimed again, otherwise 409.</li>
 *   <li>The supplier runs. A 2xx response is stored (COMPLETED, {@code REQUIRES_NEW}); an exception or a non-2xx
 *       response deletes the key so the client can retry, and is passed on unchanged.</li>
 *   <li>The request hash is the hex SHA-256 of the request serialised by the app {@link ObjectMapper} with sorted
 *       properties and map keys.</li>
 * </ol>
 * Only status and body are stored and replayed, not response headers. A replayed body is a Jackson tree of the
 * stored JSON.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final Duration staleAfter;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager, Clock clock,
                              @Value("${app.idempotency.stale-after:2m}") Duration staleAfter) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        ObjectMapper canonical = objectMapper.copy();
        canonical.setConfig(canonical.getSerializationConfig()
                .with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS));
        this.canonicalMapper = canonical;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.staleAfter = staleAfter;
    }

    /**
     * Runs {@code supplier} at most once per (user, operation, key) for the same request, or replays its stored 2xx.
     *
     * @param userId    the caller's user id (from the principal)
     * @param operation stable operation name, e.g. {@code CASE_CREATE} (at most 64 chars)
     * @param key       the {@code Idempotency-Key} header (1-100 chars; validated by the controller)
     * @param request   the request DTO whose canonical JSON is hashed
     * @param supplier  the business call; owns its transaction
     */
    public ResponseEntity<?> execute(long userId, String operation, String key, Object request,
                                     Supplier<? extends ResponseEntity<?>> supplier) {
        String hash = hash(request);
        Claim claim = requiresNew.execute(status -> claim(userId, operation, key, hash));
        if (claim.replay() != null) {
            return claim.replay();
        }
        ResponseEntity<?> response;
        try {
            response = supplier.get();
        } catch (RuntimeException | Error e) {
            release(claim.id());
            throw e;
        }
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            release(claim.id());
            return response;
        }
        String body = toJson(response.getBody());
        int status = response.getStatusCode().value();
        requiresNew.executeWithoutResult(tx -> repository.complete(claim.id(), status, body));
        return response;
    }

    /** Either a fresh claim ({@code id}) or a stored response to replay. */
    private record Claim(long id, ResponseEntity<?> replay) {}

    private Claim claim(long userId, String operation, String key, String hash) {
        Instant now = clock.instant();
        try {
            return new Claim(repository.insertInProgress(userId, operation, key, hash, now), null);
        } catch (DuplicateKeyException duplicate) {
            Optional<StoredKey> found = repository.find(userId, operation, key);
            if (found.isEmpty()) {
                throw new ConflictException("Idempotency key changed state during the claim");
            }
            StoredKey stored = found.get();
            if (!stored.requestHash().equals(hash)) {
                throw new IdempotencyMismatchException("Idempotency key reused with a different request");
            }
            if (IdempotencyRepository.COMPLETED.equals(stored.status())) {
                return new Claim(stored.id(), replay(stored));
            }
            if (repository.deleteStale(stored.id(), now.minus(staleAfter))) {
                try {
                    return new Claim(repository.insertInProgress(userId, operation, key, hash, now), null);
                } catch (DuplicateKeyException reclaimedElsewhere) {
                    throw new ConflictException("Idempotency key reclaimed by a concurrent request");
                }
            }
            throw new ConflictException("Idempotency key is still in progress");
        }
    }

    private void release(long id) {
        requiresNew.executeWithoutResult(tx -> repository.release(id));
    }

    private ResponseEntity<?> replay(StoredKey stored) {
        try {
            Object body = stored.responseBody() == null ? null : objectMapper.readTree(stored.responseBody());
            return ResponseEntity.status(stored.responseStatus()).body(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response is not valid JSON", e);
        }
    }

    private String toJson(Object body) {
        if (body == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Response body is not serialisable", e);
        }
    }

    private String hash(Object request) {
        try {
            byte[] canonical = canonicalMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Request is not serialisable", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
