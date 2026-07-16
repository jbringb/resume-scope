package dev.jbringb.resume_scope.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class PostgresApiKeyBudgetLock implements ApiKeyBudgetLock {

    private final DSLContext dsl;

    @Override
    public <T> Mono<T> withApiKeyLock(UUID apiKeyId, Mono<T> action) {
        long lockKey = advisoryLockKey(apiKeyId);
        // Same pattern as PostgresAnalysisTriggerIdempotencyLock: acquire the lock and run the action
        // inside one reactive transaction, so a concurrent same-key budget check blocks on lock
        // acquisition until the previous check-and-insert has fully committed.
        return Mono.from(dsl.transactionPublisher(
                cfg -> Mono.from(cfg.dsl().resultQuery("SELECT pg_advisory_xact_lock({0})", DSL.val(lockKey)))
                        .then(action)));
    }

    // "budget\n" prefix keeps this lock's key space distinct in intent from
    // PostgresAnalysisTriggerIdempotencyLock's (jobRoleId, idempotencyKey) keys — both hash into the
    // same 64-bit pg_advisory_xact_lock namespace, so an accidental collision would only ever cause
    // harmless extra contention, never incorrect behavior (same accepted risk as the idempotency lock).
    private static long advisoryLockKey(UUID apiKeyId) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update("budget\n".getBytes(StandardCharsets.UTF_8));
            md.update((apiKeyId == null ? "no-auth" : apiKeyId.toString()).getBytes(StandardCharsets.UTF_8));
            var digest = md.digest();
            return ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
