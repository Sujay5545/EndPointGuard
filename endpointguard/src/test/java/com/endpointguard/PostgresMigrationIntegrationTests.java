package com.endpointguard;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import com.endpointguard.audit.domain.AuditLog;
import com.endpointguard.audit.repository.AuditLogRepository;
import com.endpointguard.pullrequest.domain.WebhookEvent;
import com.endpointguard.pullrequest.repository.WebhookEventRepository;
import com.endpointguard.risk.domain.RiskAssessment;
import com.endpointguard.risk.repository.RiskAssessmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "JWT_SECRET=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
        "app.metrics.demo-api-base-url=http://localhost:1"
})
@ActiveProfiles("postgres-it")
@Testcontainers(disabledWithoutDocker = true)
class PostgresMigrationIntegrationTests {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private WebhookEventRepository webhookEventRepository;

        @Autowired
        private RiskAssessmentRepository riskAssessmentRepository;

        @Autowired
        private AuditLogRepository auditLogRepository;

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Test
        void migrationsJsonbForeignKeysAndRepositoryCascadesWorkOnPostgres() {
                assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(6);

        Long userId = jdbcTemplate.queryForObject(
                "insert into users (email, password_hash, role) values (?, ?, ?) returning id",
                Long.class, "phase8-postgres@example.com", "not-a-login-credential", "MEMBER");
        Long projectId = jdbcTemplate.queryForObject(
                "insert into projects (name, owner_user_id) values (?, ?) returning id",
                Long.class, "Phase 8 PostgreSQL", userId);
        Long repositoryId = jdbcTemplate.queryForObject(
                "insert into repositories (project_id, github_repo_full_name, webhook_secret_ref) values (?, ?, ?) returning id",
                Long.class, projectId, "acme/phase8-postgres", "REDACTED_REF");
        Long endpointId = jdbcTemplate.queryForObject(
                "insert into endpoints (project_id, repository_id, method, path_pattern, criticality) values (?, ?, ?, ?, ?) returning id",
                Long.class, projectId, repositoryId, "GET", "/api/phase8", "LOW");
        jdbcTemplate.update("insert into endpoint_mappings (endpoint_id, source_pattern) values (?, ?)",
                endpointId, "**/PhaseEightController.java");
        Long pullRequestId = jdbcTemplate.queryForObject(
                "insert into pull_requests (repository_id, github_pr_number, title, author, description) values (?, ?, ?, ?, ?) returning id",
                Long.class, repositoryId, 808, "Phase 8 diff persistence", "phase8-test", "Sanitized PR body");
        assertThat(jdbcTemplate.queryForObject(
                "select description from pull_requests where id = ?", String.class, pullRequestId))
                .isEqualTo("Sanitized PR body");
        jdbcTemplate.update("""
                insert into pr_changed_files (pull_request_id, file_path, additions, deletions, patch)
                values (?, ?, ?, ?, ?)
                """, pullRequestId, "src/PhaseEightController.java", 3, 1, "@@ -1 +1,3 @@\n+diff line");
        jdbcTemplate.update("""
                insert into risk_assessments (pull_request_id, score, tier, factor_breakdown, evaluation_status, data_quality_notes)
                values (?, 0.1, 'LOW', cast(? as jsonb), 'INSUFFICIENT_DATA', cast(? as jsonb))
                """, pullRequestId, "{}", "[\"RECENT_SAMPLE_COUNT_BELOW_MINIMUM\"]");
        assertThat(jdbcTemplate.queryForObject(
                "select patch from pr_changed_files where pull_request_id = ?", String.class, pullRequestId))
                .contains("diff line");
        assertThat(jdbcTemplate.queryForObject(
                "select data_quality_notes ->> 0 from risk_assessments where pull_request_id = ?", String.class, pullRequestId))
                .isEqualTo("RECENT_SAMPLE_COUNT_BELOW_MINIMUM");

        jdbcTemplate.update("""
                insert into audit_logs (entity_type, entity_id, action, actor, details)
                values ('READINESS', 'postgres', 'JSONB_CHECK', 'phase8', cast(? as jsonb))
                """, "{\"probe\":\"jsonb-ok\"}");
        assertThat(jdbcTemplate.queryForObject(
                "select details ->> 'probe' from audit_logs where action = 'JSONB_CHECK'", String.class))
                .isEqualTo("jsonb-ok");

        WebhookEvent webhookEvent = webhookEventRepository.saveAndFlush(WebhookEvent.builder()
                .githubDeliveryId("postgres-jpa-delivery")
                .eventType("pull_request")
                .repositoryFullName("acme/phase8-postgres")
                .payload(objectMapper.createObjectNode().put("action", "opened"))
                .processingStatus("PROCESSING")
                .build());
        webhookEvent.setProcessingStatus("PROCESSED");
        webhookEvent = webhookEventRepository.saveAndFlush(webhookEvent);
        assertThat(webhookEventRepository.findByGithubDeliveryId("postgres-jpa-delivery"))
                .get().satisfies(event -> {
                    assertThat(event.getProcessingStatus()).isEqualTo("PROCESSED");
                    assertThat(event.getPayload().path("action").asText()).isEqualTo("opened");
                });

        RiskAssessment assessment = riskAssessmentRepository.saveAndFlush(RiskAssessment.builder()
                .pullRequestId(pullRequestId)
                .score(0.72)
                .tier("HIGH")
                .factorBreakdown(objectMapper.createObjectNode().put("TRAFFIC_VOLUME", 0.8))
                .dataQualityNotes(objectMapper.createArrayNode().add("POSTGRES_JPA_JSONB"))
                .evaluationStatus("EVALUATED")
                .build());
        assertThat(riskAssessmentRepository.findByPullRequestIdOrderByComputedAtDesc(pullRequestId))
                .anySatisfy(saved -> {
                    assertThat(saved.getId()).isEqualTo(assessment.getId());
                    assertThat(saved.getFactorBreakdown().path("TRAFFIC_VOLUME").asDouble()).isEqualTo(0.8);
                    assertThat(saved.getDataQualityNotes().path(0).asText()).isEqualTo("POSTGRES_JPA_JSONB");
                });

        AuditLog auditLog = auditLogRepository.saveAndFlush(AuditLog.builder()
                .entityType("READINESS")
                .entityId("postgres-jpa")
                .action("JPA_JSONB_CHECK")
                .actor("phase8")
                .details(objectMapper.createObjectNode().put("provider", "test"))
                .build());
        assertThat(auditLogRepository.findById(auditLog.getId()))
                .get().satisfies(saved -> assertThat(saved.getDetails().path("provider").asText()).isEqualTo("test"));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into endpoints (project_id, method, path_pattern, criticality)
                values (99999999, 'GET', '/api/invalid', 'LOW')
                """))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        jdbcTemplate.update("delete from repositories where id = ?", repositoryId);
        assertThat(jdbcTemplate.queryForObject("select count(*) from endpoints where id = ?", Integer.class, endpointId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from endpoint_mappings where endpoint_id = ?", Integer.class, endpointId))
                .isZero();
    }
}