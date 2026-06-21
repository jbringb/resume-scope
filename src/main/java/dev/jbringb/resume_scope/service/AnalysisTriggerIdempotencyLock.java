package dev.jbringb.resume_scope.service;

import java.util.UUID;
import reactor.core.publisher.Mono;

/** PostgreSQL {@code pg_advisory_xact_lock} scoped to (job role id, idempotency key). */
public interface AnalysisTriggerIdempotencyLock {

    /**
     * Runs {@code action} while holding the advisory lock for (jobRoleId, key). Concurrent callers
     * with the same key are serialized: the lock is held until {@code action} completes.
     */
    <T> Mono<T> withJobRoleKeyLock(UUID jobRoleId, String idempotencyKey, Mono<T> action);
}
