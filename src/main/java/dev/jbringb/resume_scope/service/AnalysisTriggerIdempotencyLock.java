package dev.jbringb.resume_scope.service;

import java.util.UUID;
import java.util.function.Supplier;

/** PostgreSQL {@code pg_advisory_xact_lock} scoped to (job role id, idempotency key). */
public interface AnalysisTriggerIdempotencyLock {

    <T> T withJobRoleKeyLock(UUID jobRoleId, String idempotencyKey, Supplier<T> action);
}
