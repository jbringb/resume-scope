package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.API_KEY;

import dev.jbringb.resume_scope.db.generated.tables.records.ApiKeyRecord;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class ApiKeyRepository {

    private final DSLContext dsl;

    public Mono<ApiKeyRecord> findActiveByHash(String keyHash) {
        return Mono.from(
                dsl.selectFrom(API_KEY).where(API_KEY.KEY_HASH.eq(keyHash).and(API_KEY.ACTIVE.isTrue())));
    }

    public Mono<ApiKeyRecord> findById(UUID id) {
        return Mono.from(dsl.selectFrom(API_KEY).where(API_KEY.ID.eq(id)));
    }

    public Mono<Boolean> hasAnyActiveKey() {
        return Flux.from(dsl.select(API_KEY.ID)
                        .from(API_KEY)
                        .where(API_KEY.ACTIVE.isTrue())
                        .limit(1))
                .hasElements();
    }

    // Idempotent seeding hook (see config.ApiKeySeeder): safe to call on every startup once the
    // legacy single-shared-secret env var is configured, without duplicating the row.
    public Mono<Void> ensureKeyExists(String name, String keyHash, BigDecimal monthlyBudgetEur) {
        return Mono.from(dsl.insertInto(API_KEY, API_KEY.NAME, API_KEY.KEY_HASH, API_KEY.MONTHLY_BUDGET_EUR)
                        .values(name, keyHash, monthlyBudgetEur)
                        .onConflict(API_KEY.KEY_HASH)
                        .doNothing())
                .then();
    }
}
