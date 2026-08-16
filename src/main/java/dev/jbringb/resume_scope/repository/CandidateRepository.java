package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.CANDIDATE;

import dev.jbringb.resume_scope.db.generated.tables.records.CandidateRecord;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class CandidateRepository {

    private final DSLContext dsl;

    public Flux<CandidateRecord> findByJobRoleId(UUID jobRoleId) {
        return Flux.from(dsl.selectFrom(CANDIDATE)
                .where(CANDIDATE.JOB_ROLE_ID.eq(jobRoleId))
                .orderBy(CANDIDATE.CREATED_AT.asc()));
    }

    public Mono<CandidateRecord> insert(UUID jobRoleId, String originalFilename, String cvText) {
        return insert(dsl, jobRoleId, originalFilename, cvText);
    }

    // For a caller running inside an explicit transaction (e.g. an atomic multi-file upload) — must
    // use the SAME DSLContext the transaction was opened on, not this repository's own connection,
    // or the insert would auto-commit independently and the transaction couldn't roll it back.
    public Mono<CandidateRecord> insert(DSLContext ctx, UUID jobRoleId, String originalFilename, String cvText) {
        return Mono.from(
                ctx.insertInto(CANDIDATE, CANDIDATE.JOB_ROLE_ID, CANDIDATE.ORIGINAL_FILENAME, CANDIDATE.CV_TEXT)
                        .values(jobRoleId, originalFilename, cvText)
                        .returning());
    }

    public Mono<Boolean> deleteByIdAndJobRoleId(UUID candidateId, UUID jobRoleId) {
        return Mono.from(dsl.deleteFrom(CANDIDATE)
                        .where(CANDIDATE.ID.eq(candidateId).and(CANDIDATE.JOB_ROLE_ID.eq(jobRoleId))))
                .map(rows -> rows > 0);
    }
}
