package com.endpointguard.repository.domain;

import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.project.domain.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "repositories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GithubRepository {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "github_repo_full_name", nullable = false, unique = true, length = 255)
    private String githubRepoFullName;

    @Column(name = "webhook_secret_ref", nullable = false, length = 255)
    private String webhookSecretRef;

    @Column(name = "installed_at", nullable = false)
    private LocalDateTime installedAt;

    @jakarta.persistence.PrePersist
    protected void onCreate() {
        installedAt = UtcDateTime.now();
    }
}