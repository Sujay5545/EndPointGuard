ALTER TABLE pr_changed_files
    ADD COLUMN patch TEXT;

ALTER TABLE risk_assessments
    ADD COLUMN evaluation_status VARCHAR(32) NOT NULL DEFAULT 'EVALUATED';

ALTER TABLE risk_assessments
    ADD COLUMN data_quality_notes JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE INDEX idx_pull_requests_repository_opened
    ON pull_requests(repository_id, opened_at DESC, id DESC);

CREATE INDEX idx_webhook_events_processing_status
    ON webhook_events(processing_status, received_at);