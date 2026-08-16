package dev.jbringb.resume_scope.service;

import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class PostgresTransactionRunner implements TransactionRunner {

    private final DSLContext dsl;

    @Override
    public <T> Mono<T> runInTransaction(Function<DSLContext, Mono<T>> action) {
        return Mono.from(dsl.transactionPublisher(cfg -> action.apply(cfg.dsl())));
    }
}
