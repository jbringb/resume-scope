package dev.jbringb.resume_scope.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PostgresAnalysisTriggerIdempotencyLock implements AnalysisTriggerIdempotencyLock {

    private final DSLContext dsl;

    @Override
    public <T> T withJobRoleKeyLock(UUID jobRoleId, String idempotencyKey, Supplier<T> action) {
        long lockKey = advisoryLockKey(jobRoleId, idempotencyKey);
        dsl.fetch(DSL.resultQuery("SELECT pg_advisory_xact_lock({0})", DSL.val(lockKey)));
        return action.get();
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
