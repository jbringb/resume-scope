package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.ANALYSIS;

import dev.jbringb.resume_scope.db.generated.tables.records.AnalysisRecord;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class AnalysisRepository {

    private final DSLContext dsl;

    public Flux<AnalysisRecord> findByAnalysisRunId(UUID analysisRunId) {
        return Flux.from(dsl.selectFrom(ANALYSIS)
                .where(ANALYSIS.ANALYSIS_RUN_ID.eq(analysisRunId))
                .orderBy(ANALYSIS.RANK.asc()));
    }

    public Mono<UUID> insert(
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
        return Mono.from(dsl.insertInto(
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
                        .returning(ANALYSIS.ID))
                .map(AnalysisRecord::getId);
    }

    public Mono<Void> updateRank(UUID analysisId, int rank) {
        return Mono.from(dsl.update(ANALYSIS).set(ANALYSIS.RANK, rank).where(ANALYSIS.ID.eq(analysisId)))
                .then();
    }

    // Secondary sort key makes rank assignment deterministic: without it, Postgres doesn't guarantee
    // any particular order among tied overall_score rows (they could reorder across repeated runs of
    // the identical query). ID isn't meaningful (a random UUID), just stable — that's all this needs.
    public Flux<AnalysisRecord> findByAnalysisRunIdOrderByScoreDesc(UUID analysisRunId) {
        return Flux.from(dsl.selectFrom(ANALYSIS)
                .where(ANALYSIS.ANALYSIS_RUN_ID.eq(analysisRunId))
                .orderBy(ANALYSIS.OVERALL_SCORE.desc(), ANALYSIS.ID.asc()));
    }
}
