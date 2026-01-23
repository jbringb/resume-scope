package dev.jbringb.resume_scope.repository;

import static dev.jbringb.resume_scope.db.generated.Tables.JOB_ROLE;

import dev.jbringb.resume_scope.db.generated.tables.records.JobRoleRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JobRoleRepository {

    private final DSLContext dsl;

    public List<JobRoleRecord> findAll() {
        return dsl.selectFrom(JOB_ROLE).orderBy(JOB_ROLE.CREATED_AT.desc()).fetchInto(JobRoleRecord.class);
    }

    public Optional<JobRoleRecord> findById(UUID id) {
        return dsl.selectFrom(JOB_ROLE).where(JOB_ROLE.ID.eq(id)).fetchOptionalInto(JobRoleRecord.class);
    }

    public JobRoleRecord insert(String title, String description, String requirements) {
        return dsl.insertInto(JOB_ROLE, JOB_ROLE.TITLE, JOB_ROLE.DESCRIPTION, JOB_ROLE.REQUIREMENTS)
                .values(title, description, requirements)
                .returning()
                .fetchSingleInto(JobRoleRecord.class);
    }

    public boolean update(UUID id, String title, String description, String requirements) {
        return dsl.update(JOB_ROLE)
                        .set(JOB_ROLE.TITLE, title)
                        .set(JOB_ROLE.DESCRIPTION, description)
                        .set(JOB_ROLE.REQUIREMENTS, requirements)
                        .where(JOB_ROLE.ID.eq(id))
                        .execute()
                > 0;
    }

    public boolean deleteById(UUID id) {
        return dsl.deleteFrom(JOB_ROLE).where(JOB_ROLE.ID.eq(id)).execute() > 0;
    }
}
