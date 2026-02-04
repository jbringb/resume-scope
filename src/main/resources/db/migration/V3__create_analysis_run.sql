CREATE TABLE analysis_run (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    job_role_id   UUID        NOT NULL REFERENCES job_role(id) ON DELETE CASCADE,
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    triggered_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at  TIMESTAMP WITH TIME ZONE,
    error_message TEXT
);

CREATE INDEX idx_analysis_run_job_role_id ON analysis_run(job_role_id);
