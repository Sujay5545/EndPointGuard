package com.endpointguard.repository.repository;

import com.endpointguard.repository.domain.GithubRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GithubRepositoryRepository extends JpaRepository<GithubRepository, Long> {
    List<GithubRepository> findByProjectIdOrderByInstalledAtDesc(Long projectId);

    boolean existsByGithubRepoFullName(String githubRepoFullName);

    Optional<GithubRepository> findByGithubRepoFullNameIgnoreCase(String githubRepoFullName);
}