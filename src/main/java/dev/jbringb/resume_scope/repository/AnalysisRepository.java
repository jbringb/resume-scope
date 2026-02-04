package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.ANALYSIS;

import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRecord;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AnalysisRepository {

    private final DSLContext dsl;

    public List<AnalysisRecord> findByAnalysisRunId(UUID analysisRunId) {
        return dsl.selectFrom(ANALYSIS)
                .where(ANALYSIS.ANALYSIS_RUN_ID.eq(analysisRunId))
                .orderBy(ANALYSIS.RANK.asc())
                .fetchInto(AnalysisRecord.class);
    }

    public UUID insert(
            UUID candidateId,
            UUID analysisRunId,
            int overallScore,
            Integer rank,
            JSONB strengths,
            JSONB weaknesses,
            String summary,
            String recommendation,
            String extractedName,
            String extractedEmail) {
        return dsl.insertInto(
                        ANALYSIS,
                        ANALYSIS.CANDIDATE_ID,
                        ANALYSIS.ANALYSIS_RUN_ID,
                        ANALYSIS.OVERALL_SCORE,
                        ANALYSIS.RANK,
                        ANALYSIS.STRENGTHS,
                        ANALYSIS.WEAKNESSES,
                        ANALYSIS.SUMMARY,
                        ANALYSIS.RECOMMENDATION,
                        ANALYSIS.EXTRACTED_NAME,
                        ANALYSIS.EXTRACTED_EMAIL)
                .values(
                        candidateId,
                        analysisRunId,
                        overallScore,
                        rank,
                        strengths,
                        weaknesses,
                        summary,
                        recommendation,
                        extractedName,
                        extractedEmail)
                .returning()
                .fetchSingleInto(AnalysisRecord.class)
                .getId();
    }

    public void updateRank(UUID analysisId, int rank) {
        dsl.update(ANALYSIS)
                .set(ANALYSIS.RANK, rank)
                .where(ANALYSIS.ID.eq(analysisId))
                .execute();
    }

    public List<AnalysisRecord> findByAnalysisRunIdOrderByScoreDesc(UUID analysisRunId) {
        return dsl.selectFrom(ANALYSIS)
                .where(ANALYSIS.ANALYSIS_RUN_ID.eq(analysisRunId))
                .orderBy(ANALYSIS.OVERALL_SCORE.desc())
                .fetchInto(AnalysisRecord.class);
    }
}
