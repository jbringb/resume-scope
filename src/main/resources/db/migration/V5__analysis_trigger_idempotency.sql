CREATE TABLE analysis_trigger_idempotency (
    job_role_id       UUID NOT NULL REFERENCES job_role(id) ON DELETE CASCADE,
    idempotency_key   VARCHAR(255) NOT NULL,
    analysis_run_id   UUID NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (job_role_id, idempotency_key)
);
