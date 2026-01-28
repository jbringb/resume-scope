CREATE TABLE candidate (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_role_id       UUID        NOT NULL REFERENCES job_role(id) ON DELETE CASCADE,
    original_filename VARCHAR(500) NOT NULL,
    cv_text           TEXT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_candidate_job_role_id ON candidate(job_role_id);
