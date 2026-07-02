CREATE TABLE api_key (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(255)  NOT NULL,
    key_hash            VARCHAR(64)   NOT NULL UNIQUE,
    monthly_budget_eur  NUMERIC(10,4),
    active              BOOLEAN       NOT NULL DEFAULT true,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

ALTER TABLE analysis_run
    ADD COLUMN api_key_id UUID REFERENCES api_key(id);

CREATE INDEX idx_analysis_run_api_key_id ON analysis_run(api_key_id);
