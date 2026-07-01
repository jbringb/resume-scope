ALTER TABLE analysis_run
    ADD COLUMN prompt_tokens       INT           NOT NULL DEFAULT 0,
    ADD COLUMN completion_tokens   INT           NOT NULL DEFAULT 0,
    ADD COLUMN estimated_cost_eur  NUMERIC(10,4) NOT NULL DEFAULT 0;
