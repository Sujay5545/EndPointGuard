ALTER TABLE repositories
    ADD CONSTRAINT uq_repositories_id_project UNIQUE (id, project_id);

ALTER TABLE endpoints
    ADD COLUMN repository_id BIGINT;

WITH single_repository_projects AS (
    SELECT project_id, MIN(id) AS repository_id
    FROM repositories
    GROUP BY project_id
    HAVING COUNT(*) = 1
)
UPDATE endpoints endpoint
SET repository_id = repository.repository_id
FROM single_repository_projects repository
WHERE endpoint.project_id = repository.project_id;

ALTER TABLE endpoints
    ADD CONSTRAINT fk_endpoints_repository_project
        FOREIGN KEY (repository_id, project_id)
        REFERENCES repositories (id, project_id)
        ON DELETE CASCADE;

CREATE INDEX idx_endpoints_repository_path
    ON endpoints (repository_id, path_pattern);

ALTER TABLE risk_rule_weights
    ADD CONSTRAINT ck_risk_rule_weights_range
        CHECK (weight >= 0 AND weight <= 1);