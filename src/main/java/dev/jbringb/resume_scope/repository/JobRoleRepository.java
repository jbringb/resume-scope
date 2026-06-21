package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.JOB_ROLE;

import dev.jbringb.resume_scope.db.generated.tables.records.JobRoleRecord;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
@RequiredArgsConstructor
public class JobRoleRepository {

    private final DSLContext dsl;

    public Flux<JobRoleRecord> findAll() {
        return Flux.from(dsl.selectFrom(JOB_ROLE).orderBy(JOB_ROLE.CREATED_AT.desc()));
    }

    public Mono<JobRoleRecord> findById(UUID id) {
        return Mono.from(dsl.selectFrom(JOB_ROLE).where(JOB_ROLE.ID.eq(id)));
    }

    public Mono<JobRoleRecord> insert(String title, String description, String requirements) {
        return Mono.from(dsl.insertInto(JOB_ROLE, JOB_ROLE.TITLE, JOB_ROLE.DESCRIPTION, JOB_ROLE.REQUIREMENTS)
                .values(title, description, requirements)
                .returning());
    }

    public Mono<Boolean> update(UUID id, String title, String description, String requirements) {
        return Mono.from(dsl.update(JOB_ROLE)
                        .set(JOB_ROLE.TITLE, title)
                        .set(JOB_ROLE.DESCRIPTION, description)
                        .set(JOB_ROLE.REQUIREMENTS, requirements)
                        .where(JOB_ROLE.ID.eq(id)))
                .map(rows -> rows > 0);
    }

    public Mono<Boolean> deleteById(UUID id) {
        return Mono.from(dsl.deleteFrom(JOB_ROLE).where(JOB_ROLE.ID.eq(id))).map(rows -> rows > 0);
    }
}
