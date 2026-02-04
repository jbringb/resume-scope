CREATE TABLE analysis (
    id              UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    candidate_id    UUID    NOT NULL REFERENCES candidate(id) ON DELETE CASCADE,
    analysis_run_id UUID    NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
    overall_score   INTEGER NOT NULL CHECK (overall_score >= 0 AND overall_score <= 100),
    rank            INTEGER,
    strengths       JSONB   NOT NULL DEFAULT '[]',
    weaknesses      JSONB   NOT NULL DEFAULT '[]',
    summary         TEXT,
    recommendation  TEXT,
    extracted_name  VARCHAR(255),
    extracted_email VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (candidate_id, analysis_run_id)
);

CREATE INDEX idx_analysis_run_id ON analysis(analysis_run_id);
CREATE INDEX idx_analysis_candidate_id ON analysis(candidate_id);
