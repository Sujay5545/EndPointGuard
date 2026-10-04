package com.endpointguard.pullrequest.domain;

import com.endpointguard.common.time.UtcDateTime;
import com.endpointguard.repository.domain.GithubRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "pull_requests",
        uniqueConstraints = @UniqueConstraint(columnNames = {"repository_id", "github_pr_number"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    private GithubRepository repository;

    @Column(name = "github_pr_number", nullable = false)
    private Integer githubPrNumber;

    @Column(nullable = false, length = 1000)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, length = 255)
    private String author;

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "merged_at")
    private LocalDateTime mergedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "head_sha", length = 255)
    private String headSha;

    @Column(name = "review_status", nullable = false, length = 32)
    @Builder.Default
    private String reviewStatus = "NOT_REQUESTED";

    @Column(name = "review_result", columnDefinition = "TEXT")
    private String reviewResult;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = UtcDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (openedAt == null) {
            openedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = UtcDateTime.now();
    }
}
