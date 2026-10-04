-- V1: Baseline schema for EndpointGuard

-- Users
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'MEMBER',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Projects
CREATE TABLE projects (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Repositories
CREATE TABLE repositories (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    github_repo_full_name VARCHAR(255) NOT NULL,
    webhook_secret_ref VARCHAR(255) NOT NULL,
    installed_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_repositories_full_name ON repositories(github_repo_full_name);

-- Endpoints
CREATE TABLE endpoints (
    id BIGSERIAL PRIMARY KEY,
    project_id BIGINT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    method VARCHAR(10) NOT NULL,
    path_pattern VARCHAR(500) NOT NULL,
    criticality VARCHAR(50) NOT NULL DEFAULT 'MEDIUM',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_endpoints_project_path ON endpoints(project_id, path_pattern);

-- Endpoint Mappings (source file/class → endpoint)
CREATE TABLE endpoint_mappings (
    id BIGSERIAL PRIMARY KEY,
    endpoint_id BIGINT NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    source_pattern VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_endpoint_mappings_source ON endpoint_mappings(source_pattern);

-- Traffic Metrics (time-series)
CREATE TABLE traffic_metrics (
    id BIGSERIAL PRIMARY KEY,
    endpoint_id BIGINT NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    bucket_start TIMESTAMP NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    error_4xx_count BIGINT NOT NULL DEFAULT 0,
    error_5xx_count BIGINT NOT NULL DEFAULT 0,
    avg_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
    rate_limit_utilization_pct DOUBLE PRECISION NOT NULL DEFAULT 0,
    collected_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_traffic_metrics_endpoint_bucket ON traffic_metrics(endpoint_id, bucket_start);
CREATE INDEX idx_traffic_metrics_bucket ON traffic_metrics(bucket_start);

-- Pull Requests
CREATE TABLE pull_requests (
    id BIGSERIAL PRIMARY KEY,
    repository_id BIGINT NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
    github_pr_number INTEGER NOT NULL,
    title VARCHAR(1000) NOT NULL,
    author VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'OPEN',
    opened_at TIMESTAMP NOT NULL DEFAULT NOW(),
    merged_at TIMESTAMP,
    closed_at TIMESTAMP,
    head_sha VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_pull_requests_repo_number ON pull_requests(repository_id, github_pr_number);

-- PR Changed Files
CREATE TABLE pr_changed_files (
    id BIGSERIAL PRIMARY KEY,
    pull_request_id BIGINT NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    file_path VARCHAR(1000) NOT NULL,
    additions INTEGER NOT NULL DEFAULT 0,
    deletions INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_pr_changed_files_pr ON pr_changed_files(pull_request_id);

-- PR Affected Endpoints (many-to-many result)
CREATE TABLE pr_affected_endpoints (
    pull_request_id BIGINT NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    endpoint_id BIGINT NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    PRIMARY KEY (pull_request_id, endpoint_id)
);

-- Risk Rule Weights
CREATE TABLE risk_rule_weights (
    id BIGSERIAL PRIMARY KEY,
    factor_name VARCHAR(100) NOT NULL UNIQUE,
    weight DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    description VARCHAR(500),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Risk Assessments
CREATE TABLE risk_assessments (
    id BIGSERIAL PRIMARY KEY,
    pull_request_id BIGINT NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    score DOUBLE PRECISION NOT NULL,
    tier VARCHAR(20) NOT NULL,
    computed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    factor_breakdown JSONB NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX idx_risk_assessments_pr ON risk_assessments(pull_request_id);

-- Webhook Events (idempotency + audit)
CREATE TABLE webhook_events (
    id BIGSERIAL PRIMARY KEY,
    github_delivery_id VARCHAR(255) NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    repository_full_name VARCHAR(255),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    received_at TIMESTAMP NOT NULL DEFAULT NOW(),
    processed_at TIMESTAMP,
    processing_status VARCHAR(50) NOT NULL DEFAULT 'RECEIVED'
);
CREATE INDEX idx_webhook_events_delivery ON webhook_events(github_delivery_id);
CREATE INDEX idx_webhook_events_status ON webhook_events(processing_status);

-- Post-Merge Monitoring
CREATE TABLE post_merge_monitoring (
    id BIGSERIAL PRIMARY KEY,
    pull_request_id BIGINT NOT NULL REFERENCES pull_requests(id) ON DELETE CASCADE,
    endpoint_id BIGINT NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    window_start TIMESTAMP NOT NULL,
    window_end TIMESTAMP NOT NULL,
    baseline_error_rate DOUBLE PRECISION,
    observed_error_rate DOUBLE PRECISION,
    baseline_latency_ms DOUBLE PRECISION,
    observed_latency_ms DOUBLE PRECISION,
    baseline_request_count BIGINT,
    observed_request_count BIGINT,
    verdict VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    evaluated_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_post_merge_monitoring_pr ON post_merge_monitoring(pull_request_id);
CREATE INDEX idx_post_merge_monitoring_verdict ON post_merge_monitoring(verdict);
CREATE INDEX idx_post_merge_monitoring_window ON post_merge_monitoring(window_end) WHERE verdict = 'PENDING';

-- Audit Logs
CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(100) NOT NULL,
    entity_id VARCHAR(255),
    action VARCHAR(100) NOT NULL,
    actor VARCHAR(255),
    details JSONB DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_logs_created ON audit_logs(created_at);
