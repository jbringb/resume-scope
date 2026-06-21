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
public class PostgresAnalysisTriggerIdempotencyLock implements AnalysisTriggerIdempotencyLock {

    private final DSLContext dsl;

    @Override
    public <T> Mono<T> withJobRoleKeyLock(UUID jobRoleId, String idempotencyKey, Mono<T> action) {
        long lockKey = advisoryLockKey(jobRoleId, idempotencyKey);
        // Acquire the lock and run the action inside one reactive transaction. The xact-scoped
        // advisory lock is held until the transaction completes (i.e. until the action finishes),
        // so a concurrent same-key trigger blocks on lock acquisition.
        return Mono.from(dsl.transactionPublisher(
                cfg -> Mono.from(cfg.dsl().resultQuery("SELECT pg_advisory_xact_lock({0})", DSL.val(lockKey)))
                        .then(action)));
    }

    private static long advisoryLockKey(UUID jobRoleId, String idempotencyKey) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(jobRoleId.toString().getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            md.update(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            var digest = md.digest();
            return ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
