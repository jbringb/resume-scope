package dev.jbringb.resume_scope.service;

import java.util.UUID;
import reactor.core.publisher.Mono;

/** PostgreSQL {@code pg_advisory_xact_lock} scoped to a single API key (or a fixed key when auth is disabled). */
public interface ApiKeyBudgetLock {

    /**
     * Runs {@code action} while holding the advisory lock for apiKeyId. Concurrent budget-check-and-insert
     * attempts for the same key are serialized: the lock is held until {@code action} completes.
     */
    <T> Mono<T> withApiKeyLock(UUID apiKeyId, Mono<T> action);
}
