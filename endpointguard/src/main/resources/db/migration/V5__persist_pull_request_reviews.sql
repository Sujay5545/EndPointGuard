ALTER TABLE pull_requests
    ADD COLUMN review_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    ADD COLUMN review_result TEXT;

CREATE INDEX idx_pull_requests_review_status ON pull_requests(review_status, updated_at);