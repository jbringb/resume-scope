package dev.jbringb.resume_scope.service;

import java.util.function.Function;
import org.jooq.DSLContext;
import reactor.core.publisher.Mono;

/**
 * Runs an action inside a single Postgres transaction. The action is handed the transactional
 * {@link DSLContext} and must use it (not a repository's own connection) for every query that needs
 * to participate — otherwise those queries auto-commit independently and the transaction can't roll
 * them back.
 */
public interface TransactionRunner {

    <T> Mono<T> runInTransaction(Function<DSLContext, Mono<T>> action);
}
