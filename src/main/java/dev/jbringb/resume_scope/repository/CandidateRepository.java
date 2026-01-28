package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.CANDIDATE;

import dev.jbringb.resume_scope.db.generated.tables.records.CandidateRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CandidateRepository {

    private final DSLContext dsl;

    public List<CandidateRecord> findByJobRoleId(UUID jobRoleId) {
        return dsl.selectFrom(CANDIDATE)
                .where(CANDIDATE.JOB_ROLE_ID.eq(jobRoleId))
                .orderBy(CANDIDATE.CREATED_AT.asc())
                .fetchInto(CandidateRecord.class);
    }

    public Optional<CandidateRecord> findByIdAndJobRoleId(UUID candidateId, UUID jobRoleId) {
        return dsl.selectFrom(CANDIDATE)
                .where(CANDIDATE.ID.eq(candidateId).and(CANDIDATE.JOB_ROLE_ID.eq(jobRoleId)))
                .fetchOptionalInto(CandidateRecord.class);
    }

    public CandidateRecord insert(UUID jobRoleId, String originalFilename, String cvText) {
        return dsl.insertInto(CANDIDATE, CANDIDATE.JOB_ROLE_ID, CANDIDATE.ORIGINAL_FILENAME, CANDIDATE.CV_TEXT)
                .values(jobRoleId, originalFilename, cvText)
                .returning()
                .fetchSingleInto(CandidateRecord.class);
    }

    public boolean deleteByIdAndJobRoleId(UUID candidateId, UUID jobRoleId) {
        return dsl.deleteFrom(CANDIDATE)
                        .where(CANDIDATE.ID.eq(candidateId).and(CANDIDATE.JOB_ROLE_ID.eq(jobRoleId)))
                        .execute()
                > 0;
    }
}
