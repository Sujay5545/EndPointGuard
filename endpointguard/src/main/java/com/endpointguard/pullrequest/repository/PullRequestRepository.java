package com.endpointguard.pullrequest.repository;

import com.endpointguard.pullrequest.domain.PullRequest;
import com.endpointguard.repository.domain.GithubRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PullRequestRepository extends JpaRepository<PullRequest, Long> {

    List<PullRequest> findByRepository_Id(Long repositoryId);

    Optional<PullRequest> findByRepositoryAndGithubPrNumber(GithubRepository repository, Integer githubPrNumber);

    @Query("""
        SELECT pullRequest FROM PullRequest pullRequest
        WHERE pullRequest.repository.project.id = :projectId
        AND pullRequest.repository.project.owner.email = :ownerEmail
        ORDER BY pullRequest.openedAt DESC
        """)
    List<PullRequest> findOwnedForProject(
            @Param("projectId") Long projectId,
            @Param("ownerEmail") String ownerEmail);

    @Query(value = """
        SELECT pullRequest FROM PullRequest pullRequest
        WHERE pullRequest.repository.project.id = :projectId
        AND pullRequest.repository.project.owner.email = :ownerEmail
        AND (:status IS NULL OR UPPER(pullRequest.status) = :status)
        ORDER BY pullRequest.openedAt DESC, pullRequest.id DESC
        """,
        countQuery = """
        SELECT COUNT(pullRequest) FROM PullRequest pullRequest
        WHERE pullRequest.repository.project.id = :projectId
        AND pullRequest.repository.project.owner.email = :ownerEmail
        AND (:status IS NULL OR UPPER(pullRequest.status) = :status)
        """)
    Page<PullRequest> findOwnedPage(
            @Param("projectId") Long projectId,
            @Param("ownerEmail") String ownerEmail,
            @Param("status") String status,
            Pageable pageable);

    @Query("""
        SELECT pullRequest FROM PullRequest pullRequest
        WHERE pullRequest.id = :pullRequestId
        AND pullRequest.repository.project.id = :projectId
        AND pullRequest.repository.project.owner.email = :ownerEmail
        """)
    Optional<PullRequest> findOwnedInProject(
            @Param("pullRequestId") Long pullRequestId,
            @Param("projectId") Long projectId,
            @Param("ownerEmail") String ownerEmail);
}
