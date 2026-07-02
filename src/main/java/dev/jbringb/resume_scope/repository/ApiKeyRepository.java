package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.API_KEY;

import dev.jbringb.resume_scope.db.generated.tables.records.ApiKeyRecord;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
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

    // Idempotent seeding hook (see config.ApiKeySeeder): inserts a new row or reactivates an
    // existing row with the same hash (handles rotate-back to a previously deactivated key).
    public Mono<Void> upsertActiveKey(String name, String keyHash, BigDecimal monthlyBudgetEur) {
        return Mono.from(dsl.insertInto(API_KEY, API_KEY.NAME, API_KEY.KEY_HASH, API_KEY.MONTHLY_BUDGET_EUR)
                        .values(name, keyHash, monthlyBudgetEur)
                        .onConflict(API_KEY.KEY_HASH)
                        .doUpdate()
                        .set(API_KEY.ACTIVE, true)
                        .set(API_KEY.NAME, name))
                .then();
    }

    // Deactivates all rows with the given name whose hash differs from activeKeyHash. Called by
    // ApiKeySeeder after upserting the current key so rotation revokes the old key immediately.
    public Mono<Void> deactivateByNameExcludingHash(String name, String activeKeyHash) {
        return Mono.from(dsl.update(API_KEY)
                        .set(API_KEY.ACTIVE, false)
                        .where(API_KEY.NAME.eq(name).and(API_KEY.KEY_HASH.notEqual(activeKeyHash))))
                .then();
    }
}
